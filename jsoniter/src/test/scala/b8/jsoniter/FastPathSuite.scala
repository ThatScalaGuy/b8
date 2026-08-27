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

package b8.jsoniter

import b8.ArraySink
import b8.ByteBufferSink
import b8.Codec
import b8.Format.Json
import b8.OutputStreamSink
import b8.SinkPool
import b8.laws.Fixtures
import b8.laws.Nested

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

import com.github.plokhotnyuk.jsoniter_scala.core.writeToArray

/** The fast path, and the adaptive hint that keeps it fast.
  *
  * `writeToSubArray` renders straight into the sink's own array and cannot grow
  * it, so the bridge sizes the sink from a hint, and retries with a bigger one
  * when the hint was too small. The retry is the expensive case: it throws,
  * reallocates and re-encodes the whole value. Every test here is about whether
  * it happens when it should and stops happening once it need not.
  *
  * `ArraySink` is `final`, so the retries cannot be counted by subclassing it.
  * They are observed through `capacity` instead, which works because the retry
  * path is the only thing that ever asks for more room than the hint: a sink
  * whose capacity is unchanged after an encode did not retry.
  *
  * Each test builds its own codec. `sizeHint` reads a `var` that the last
  * encode wrote, so the state that decides whether a retry happens is
  * per-instance — a codec shared across the suite would make these tests pass
  * or fail depending on the order munit ran them in.
  */
class FastPathSuite extends munit.FunSuite:

  import Codecs.given

  /** What a jsoniter user gets with b8 nowhere in the picture. Every test
    * compares against this, so a bridge that quietly reformatted anything would
    * have nowhere to hide.
    */
  private val plain: Array[Byte] = writeToArray(Fixtures.nested1)

  /** A codec that has never encoded anything, and therefore still carries the
    * bridge's initial 256-byte guess.
    */
  private def fresh: Codec[Nested, Json] = codec[Nested]()

  test("a sink far too small still produces jsoniter's own bytes") {
    val c = fresh
    val sink = ArraySink(8)
    c.encodeTo(Fixtures.nested1, sink)
    // Lengths first: two arrays of a thousand bytes that differ somewhere
    // report nothing useful on their own.
    assertEquals(sink.result().length, plain.length)
    assert(sink.result().sameElements(plain))
    // Eight bytes could not hold the first field, so the retry path ran, and
    // it grew the sink rather than truncating the value.
    assert(sink.capacity > 8, clue(sink.capacity))
  }

  test("the hint adapts to what was written") {
    val c = fresh
    c.encodeTo(Fixtures.nested1, ArraySink(8))
    val hint = c.sizeHint(Fixtures.nested1)
    // Above the encoding, or the next encode of a value this size retries for
    // the sake of the handful of bytes jsoniter reserves ahead of a token.
    assert(hint > plain.length, clue(hint))
    // And not far above it: the hint is what a pool allocates, so a generous
    // one is paid for on every message.
    assert(hint <= plain.length + plain.length / 2, clue(hint))
  }

  test("a sink sized by the adapted hint never grows") {
    val c = fresh
    c.encodeTo(Fixtures.nested1, ArraySink(8))

    val sink = ArraySink(c.sizeHint(Fixtures.nested1))
    val before = sink.capacity
    c.encodeTo(Fixtures.nested1, sink)
    assert(sink.result().sameElements(plain))
    // The whole point of the adaptive hint. The first encode of this value
    // reallocated twice; the second, with the same codec, writes it in one
    // pass into an array that was right the first time.
    assertEquals(sink.capacity, before)
  }

  test("a sink that already holds bytes keeps them") {
    // A sink is append-only, and the fast path is exactly where that gets
    // lost: `writeToSubArray` is handed the sink's array, and a bridge that
    // passed `0` instead of `position` would overwrite a framing header the
    // caller wrote first and still produce a perfectly valid message.
    val c = fresh
    val prefix = Array[Byte](1, 2, 3)
    val sink = ArraySink(8)
    sink.write(prefix)
    c.encodeTo(Fixtures.nested1, sink)
    assert(sink.result().sameElements(prefix ++ plain))
  }

  test("the sinks without an array receive the same bytes") {
    // Neither of these is an `ArraySink`, so both leave the fast path and go
    // through `writeToStream`. Two encoders in one class is two chances to
    // disagree, and the bytes are where that would show.
    val c = fresh

    val bb = ByteBuffer.allocate(plain.length * 2)
    c.encodeTo(Fixtures.nested1, ByteBufferSink(bb))
    bb.flip()
    val fromBuffer = new Array[Byte](bb.remaining())
    bb.get(fromBuffer)
    assert(fromBuffer.sameElements(plain))

    val os = new ByteArrayOutputStream()
    c.encodeTo(Fixtures.nested1, OutputStreamSink(os))
    assert(os.toByteArray.sameElements(plain))
  }

  test("a pooled sink sized by the adapted hint does not grow either") {
    // The combination a service actually runs: a pool that hands back the same
    // buffer, and a codec whose hint has settled on the size of the messages
    // going through it. Together they encode without allocating and without
    // retrying, which is the reason both exist.
    val c = fresh
    c.encodeTo(Fixtures.nested1, ArraySink(8))

    val pool = SinkPool.threadLocal()
    val sink = pool.borrow(c.sizeHint(Fixtures.nested1))
    val before = sink.capacity
    c.encodeTo(Fixtures.nested1, sink)
    assertEquals(sink.capacity, before)
    assert(sink.result().sameElements(plain))
    pool.release(sink)
  }
