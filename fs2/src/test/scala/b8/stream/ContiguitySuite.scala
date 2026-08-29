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

package b8.stream

import b8.Encoder
import b8.Format.Json
import b8.jsoniter.given
import b8.laws.Fixtures
import b8.laws.Flat
import b8.laws.Nested
import b8.stream.Codecs.given

import com.google.protobuf.CodedOutputStream
import fs2.Chunk
import fs2.Fallible
import fs2.Pure
import fs2.Stream

/** One message, one chunk, prefix included.
  *
  * The encode side promises more than "the right bytes come out in the right
  * order": it promises where the boundaries between the emitted chunks fall.
  * One chunk per message, never one per input chunk, and each of them holding a
  * whole frame — length prefix or terminator and body together, contiguous.
  * That is what lets a downstream consumer treat a chunk as a unit: write it to
  * a socket in one call, hand it to a decoder without stitching, count messages
  * by counting chunks.
  *
  * Nothing about that guarantee is visible in a round trip. `mapChunks` would
  * pass every round-trip test in this module and still emit one chunk per
  * *input* chunk, so the moment an upstream operator batched two values the
  * boundaries would be wrong and nothing else would notice. Hence a suite that
  * varies the source chunking on purpose and counts what comes out.
  *
  * The prefixes are checked against numbers computed here rather than against
  * the pipe's own arithmetic: the fixed32 length by hand, and the varint
  * against protobuf's writer, which is the implementation `Framing.Varint`
  * claims to be wire-compatible with.
  */
class ContiguitySuite extends munit.FunSuite:

  /** Five values that differ from one another, so that a boundary in the wrong
    * place cannot pass for one in the right place.
    */
  private val values: List[Nested] =
    List.tabulate(5)(i =>
      Fixtures.nested1.copy(flat = Fixtures.flat1.copy(id = i.toLong))
    )

  private val framings: List[Framing[Json]] =
    List(Framing.Fixed32, Framing.Varint, Framing.Newline)

  /** All five values in a single chunk — what `Stream.emits` and any upstream
    * batching produce.
    */
  private def batched: Stream[Pure, Nested] = Stream.chunk(Chunk.from(values))

  /** The same five values as five chunks of one — the other extreme. */
  private def singles: Stream[Pure, Nested] =
    Stream.emits(values).chunkLimit(1).unchunks

  private def framed(
      in: Stream[Pure, Nested],
      framing: Framing[Json]
  ): List[Chunk[Byte]] =
    in.through(encode[Json](framing)).chunks.compile.toList

  private def frameOf[A](a: A, framing: Framing[Json])(using
      Encoder[A, Json]
  ): Chunk[Byte] =
    val out =
      Stream.emit(a).through(encode[Json](framing)).chunks.compile.toList
    assertEquals(out.size, 1, clue(out))
    out.head

  test("one chunk per message, whatever the source chunking was") {
    for framing <- framings do
      // Five values in, five chunks out. The source shape is the variable and
      // the result is not allowed to depend on it: one chunk of five and five
      // chunks of one have to give the same five frames.
      assertEquals(framed(batched, framing).size, values.size, framing)
      assertEquals(framed(singles, framing).size, values.size, framing)
  }

  test("every emitted chunk is a whole message on its own") {
    for framing <- framings do
      for (c, a) <- framed(batched, framing).zip(values) do
        // A chunk fed to the decode pipe by itself yields exactly one value and
        // no truncation, which is the operational form of "contiguous": nothing
        // from this message lives in the chunk before or after it.
        val one: Stream[Fallible, Nested] =
          Stream.chunk(c).covary[Fallible].through(decode[Json](framing))
        assertEquals(one.compile.toList, Right(List(a)), framing)
  }

  test("a fixed32 chunk is the big-endian length of the rest, then the rest") {
    for (c, a) <- framed(batched, Framing.Fixed32).zip(values) do
      // The expected length comes from the encoder, not from the chunk. A
      // prefix that agreed with a body the pipe had written wrongly would still
      // be caught, because both halves are measured against the same
      // independent encoding.
      val body = Encoder[Nested, Json].encode(a)
      assertEquals(c.size, body.length + 4)
      assertEquals(c(0), (body.length >>> 24).toByte)
      assertEquals(c(1), (body.length >>> 16).toByte)
      assertEquals(c(2), (body.length >>> 8).toByte)
      assertEquals(c(3), body.length.toByte)
      assert(c.drop(4).toArray.sameElements(body))
  }

  /** protobuf's own varint writer, used as the reference.
    *
    * The point of a reference implementation is that somebody else wrote it.
    * Re-deriving LEB128 in this file and comparing it against the production
    * loop would only show that the same idea was had twice, and a shared
    * misreading of the spec would agree with itself. `CodedOutputStream` is the
    * encoder `writeDelimitedTo` uses for exactly this prefix, so agreeing with
    * it is precisely the interop claim `Framing.Varint` makes — and it is on
    * this module's test classpath already, through `scalapb % "test->test"`.
    */
  private def protoVarint(n: Int): Array[Byte] =
    val out = new Array[Byte](CodedOutputStream.computeUInt32SizeNoTag(n))
    val cos = CodedOutputStream.newInstance(out)
    cos.writeUInt32NoTag(n)
    cos.flush()
    out

  private def assertVarintFrame[A](a: A, width: Int)(using
      e: Encoder[A, Json]
  ): Unit =
    val body = e.encode(a)
    val prefix = protoVarint(body.length)
    assertEquals(prefix.length, width, clue(body.length))
    val frame = frameOf(a, Framing.Varint)
    assertEquals(frame.size, body.length + width)
    assert(
      frame.take(width).toArray.sameElements(prefix),
      clue(frame.take(width).toArray.toList)
    )
    assert(frame.drop(width).toArray.sameElements(body))

  test("a varint prefix is protobuf's own encoding of the body length") {
    // Both widths an ordinary stream of messages actually meets. A `Flat` this
    // small encodes to well under 128 bytes, so its length is one byte; the
    // 1 KB `nested1` needs two. One byte is the case where the reserve-and-
    // patch buffer is written at an offset of four rather than zero, which is
    // where a varint prefix differs from a fixed one and where an off-by-one
    // would show.
    assertVarintFrame(Flat(1L, "a", true, 1.0, Nil), width = 1)
    assertVarintFrame(Fixtures.nested1, width = 2)
  }

  test("a newline chunk is the encoding and exactly one 0x0a") {
    for (c, a) <- framed(batched, Framing.Newline).zip(values) do
      val body = Encoder[Nested, Json].encode(a)
      assertEquals(c.size, body.length + 1)
      assert(c.take(c.size - 1).toArray.sameElements(body))
      assertEquals(c(c.size - 1), 0x0a.toByte)
      // Exactly one, and it is the last byte. Not vacuous: `flat1` carries a
      // literal newline inside one of its tags, and JSON writes it as the two
      // characters `\` and `n`. That a text encoding cannot emit a raw 0x0a of
      // its own is the entire premise of newline framing, and the reason
      // `Framing.Newline` is typed as a `Framing[Format.Text]`.
      assertEquals(c.toArray.count(_ == 0x0a.toByte), 1)
  }

  test("an emitted chunk is a view, and its array may be larger than it") {
    val c = framed(batched, Framing.Fixed32).head
    val slice = c.toArraySlice[Byte]
    // `toArraySlice` hands an array-backed chunk straight back rather than
    // copying, so this being the same object is what says the pipe emitted a
    // window onto the buffer it encoded into and not a trimmed copy of it.
    assert(slice eq c, "the emitted chunk was not array-backed")
    // That buffer is sized from `sizeHint`, so the array behind the window is
    // at least as long as the window and usually longer. This is the documented
    // price of reserve-and-patch — writing the length prefix into space left
    // for it, instead of encoding into one buffer and copying it in behind a
    // header in another — and it is not a leak: the sink is fresh per message,
    // nothing else ever holds a reference to it, and it becomes garbage
    // together with the chunk that views it.
    assert(slice.values.length >= c.size, clue(slice.values.length))
  }
