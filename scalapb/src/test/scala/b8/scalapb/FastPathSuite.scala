/*
 * Copyright 2026 ThatScalaGuy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package b8.scalapb

import b8.ArraySink
import b8.ByteBufferSink
import b8.ByteSource
import b8.Codec
import b8.Format.Proto
import b8.OutputStreamSink
import b8.SinkPool
import b8.array.*
import b8.scalapb.ProtoFixtures.pFlat1
import b8.scalapb.ProtoFixtures.pNested1
import b8.scalapb.protos.PFlat
import b8.scalapb.protos.PNested

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** The fast path, and why this bridge has only the one.
  *
  * The jsoniter suite next door is about a size hint that can be wrong and the
  * retry that follows: jsoniter renders into the sink's own array, cannot grow
  * it, and pays for a hint that was too small by throwing the work away and
  * encoding the value a second time into a bigger array. None of that exists
  * here. `serializedSize` walks a ScalaPB message once, memoizes the answer on
  * the instance, and is exact, so the bridge reserves that many bytes, writes
  * them, and is finished. There is no estimate to correct, no state carried
  * from one encode to the next, and no branch that runs only after a first
  * attempt failed.
  *
  * That absence is why the codecs below are shared `val`s. The jsoniter suite
  * had to build a fresh codec per test, because its hint is a `var` the last
  * encode wrote and a shared codec would make its results depend on the order
  * munit happened to run the tests in. Here two encodes of the same value are
  * indistinguishable no matter what ran before them, and a suite that could not
  * say that would be describing a different bridge.
  *
  * What remains to be tested is that the exactness is real and that it is spent
  * where it was meant to be: the bytes are ScalaPB's own, the hint is the
  * length rather than an approximation of it, a sink sized by the hint never
  * reallocates, and the write lands at the sink's `position` instead of at
  * zero. The decode side appears once, for the case an encoder-shaped suite
  * still owns: a message read out of the middle of a larger array.
  */
class FastPathSuite extends munit.FunSuite:

  private val nestedCodec: Codec[PNested, Proto] = Codec[PNested, Proto]
  private val flatCodec: Codec[PFlat, Proto] = Codec[PFlat, Proto]

  /** What a ScalaPB user gets with b8 nowhere in the picture. Every test
    * compares against these, so a bridge that quietly reordered a field or
    * wrote a byte of its own around the message would have nowhere to hide.
    */
  private val plainNested: Array[Byte] = pNested1.toByteArray
  private val plainFlat: Array[Byte] = pFlat1.toByteArray

  /** Bound here rather than made a `given` in class scope: as a `given` it
    * would silently become the pool every other test in this file encodes
    * through, and the default pool — the one nearly every caller actually gets
    * — would go untested while the suite still claimed to cover both.
    */
  private val pooled: SinkPool = SinkPool.threadLocal()

  test("the bytes are the bytes scalapb writes itself") {
    val nested = pNested1.encode[Proto]
    // Lengths first: two arrays of 573 bytes that differ somewhere report
    // nothing useful on their own.
    assertEquals(nested.length, plainNested.length)
    assert(nested.sameElements(plainNested))

    // The same for a message with no nesting, no map and no oneof, so the
    // suite does not rest on a single value: a bridge that mishandled one
    // field kind would still have to get the flat message right.
    val flat = pFlat1.encode[Proto]
    assertEquals(flat.length, plainFlat.length)
    assert(flat.sameElements(plainFlat))
  }

  test("the size hint is the length, not an estimate of it") {
    // No other b8 bridge can assert an equality here. jsoniter, circe and
    // borer all have to serialize a value to learn how long it is, so their
    // hints are guesses a suite can only bound from above and below. Protobuf
    // computes the length as a step of encoding and ScalaPB keeps it on the
    // message, so this hint is not near the answer, it is the answer — and a
    // pool sized by it over-allocates nothing and is never short.
    val hint = nestedCodec.sizeHint(pNested1)
    assertEquals(hint, pNested1.serializedSize)
    assertEquals(hint, pNested1.encode[Proto].length)
    assertEquals(flatCodec.sizeHint(pFlat1), pFlat1.encode[Proto].length)
  }

  test("a sink sized by the hint never grows") {
    val hint = nestedCodec.sizeHint(pNested1)
    val sink = ArraySink(hint)
    val before = sink.capacity
    nestedCodec.encodeTo(pNested1, sink)
    // `ArraySink` is `final`, so a reallocation cannot be counted by
    // subclassing it. An unchanged capacity is how it is observed instead:
    // `ensure` grows by doubling, so a single byte of growth would leave a
    // capacity visibly larger than the one asked for. The jsoniter suite uses
    // the same technique for the opposite purpose, to prove that its retry
    // path did run.
    assertEquals(sink.capacity, before)
    // And the encode filled the room it reserved, rather than reserving more
    // than it needed and leaving the tail of the array unwritten.
    assertEquals(sink.position, hint)
  }

  test("a sink that already holds bytes keeps them") {
    // A sink is append-only, and the fast path is exactly where that gets
    // lost: the `CodedOutputStream` is laid over the sink's own array, and a
    // bridge that started it at `0` instead of `position` would overwrite a
    // framing header the caller wrote first and still produce a message
    // protobuf reads back without complaint. The laws cover this too; it
    // belongs next to the test above because both are statements about the
    // `ArraySink` branch specifically.
    val prefix = Array[Byte](1, 2, 3)
    val sink = ArraySink(prefix.length + nestedCodec.sizeHint(pNested1))
    sink.write(prefix)
    nestedCodec.encodeTo(pNested1, sink)
    assert(sink.result().sameElements(prefix ++ plainNested))
  }

  test("the sinks without an array receive the same bytes") {
    // Neither of these is an `ArraySink`, so both leave the fast path for
    // protobuf's stream encoder. The buffer is allocated at exactly
    // `sizeHint` bytes and `ByteBufferSink` never grows, so a stream encoder
    // that wrote one byte more than the hint promised would fail this test
    // with a `BufferOverflowException` before any comparison happened.
    val bb = ByteBuffer.allocate(nestedCodec.sizeHint(pNested1))
    nestedCodec.encodeTo(pNested1, ByteBufferSink(bb))
    bb.flip()
    val fromBuffer = new Array[Byte](bb.remaining())
    bb.get(fromBuffer)
    assertEquals(fromBuffer.length, plainNested.length)
    assert(fromBuffer.sameElements(plainNested))

    // Two write paths in one class is two chances to disagree, and the bytes
    // are where a disagreement would show.
    val os = new ByteArrayOutputStream()
    nestedCodec.encodeTo(pNested1, OutputStreamSink(os))
    assertEquals(os.toByteArray.length, plainNested.length)
    assert(os.toByteArray.sameElements(plainNested))
  }

  test("a message is decoded out of the middle of a larger array") {
    // The padding bytes are all `0x0e`, a tag for field 1 with wire type 6,
    // and protobuf defines no wire type 6. So they are not bytes a decoder
    // might plausibly skip past — they are input no decoder can accept, which
    // is what makes the pair of assertions below mean something together: the
    // window decodes, and the array holding it does not, so `offset` and
    // `length` really did bound the read instead of being ignored in favour
    // of the whole array.
    val bytes = pNested1.encode[Proto]
    val padding = Array[Byte](0x0e, 0x0e, 0x0e)
    val suffix = Array[Byte](0x0e)
    val padded = padding ++ bytes ++ suffix

    val window = ByteSource(padded, padding.length, bytes.length)
    assertEquals(nestedCodec.decode(window), Right(pNested1))

    val whole = nestedCodec.decode(ByteSource(padded))
    assert(whole.isLeft, clue(whole))
  }

  test("a pooled sink is handed the same bytes") {
    val fromPool = nestedCodec.encode(pNested1)(using pooled)
    assertEquals(fromPool.length, plainNested.length)
    assert(fromPool.sameElements(plainNested))

    // The second encode is the one worth having. A thread-local pool hands
    // the same sink back, `reset` to position 0 but with the previous
    // message still lying in its array, so a bridge that read `position`
    // wrongly or counted on a zeroed buffer would answer correctly once and
    // wrongly here.
    val again = nestedCodec.encode(pNested1)(using pooled)
    assert(again.sameElements(plainNested))

    // The combination a service actually runs: a pool that hands the same
    // buffer back, and a hint that is the message's exact length. Together
    // they encode without allocating a buffer — the `CodedOutputStream` laid
    // over the sink is still one object per call, as `ScalapbEncoder` says —
    // which is the reason both exist.
    val sink = pooled.borrow(nestedCodec.sizeHint(pNested1))
    val before = sink.capacity
    nestedCodec.encodeTo(pNested1, sink)
    assertEquals(sink.capacity, before)
    assert(sink.result().sameElements(plainNested))
    pooled.release(sink)
  }

  test("the one-way factories carry the same exact size and bytes") {
    // `encoder` and `decoder` reach the same two static bodies the codec
    // does, but through `ScalapbEncoder` and `ScalapbDecoder`, which have
    // their own `sizeHint` and their own delegation. Nothing else in the
    // module builds either class, so without this test `ScalapbEncoder`'s
    // `sizeHint` override could be deleted — leaving `Encoder`'s inherited
    // 256, the guess this bridge exists not to make — and all of the rest
    // would still be green.
    val enc = encoder[PNested]
    val dec = decoder[PNested]
    assertEquals(enc.sizeHint(pNested1), pNested1.serializedSize)
    assert(enc.encode(pNested1).sameElements(plainNested))
    assertEquals(dec.decode(ByteSource(plainNested)), Right(pNested1))
  }
