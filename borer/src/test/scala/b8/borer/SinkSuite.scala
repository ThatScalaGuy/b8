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

package b8.borer

import b8.ArraySink
import b8.ByteBufferSink
import b8.Encoder
import b8.Format
import b8.OutputStreamSink
import b8.SinkPool
import b8.laws.Fixtures
import b8.laws.Nested

import java.io.ByteArrayOutputStream
import java.nio.BufferOverflowException
import java.nio.ByteBuffer

/** Where the bytes go, and whether that changes what they are.
  *
  * borer writes straight into the sink instead of into a buffer of its own, so
  * the sink is part of the encode path rather than something the bridge fills
  * at the end. That makes two questions worth asking. The first is whether the
  * four ways out — a growing array, a fixed buffer, a stream, a pooled buffer —
  * really agree byte for byte. The second is the one this suite exists for: the
  * bytes are the ones plain borer would have written, so a b8 user and a borer
  * user can read each other's messages.
  *
  * `Nested` is the fixture throughout because it is the large one: nested maps,
  * a vector, an option and both enums, long enough that a sink has to grow
  * several times on the way.
  */
class SinkSuite extends munit.FunSuite:

  import Codecs.given

  /** What a borer user gets without b8 in the picture, for both formats. */
  private val plainCbor: Array[Byte] =
    io.bullet.borer.Cbor.encode(Fixtures.nested1).toByteArray

  private val plainJson: Array[Byte] =
    io.bullet.borer.Json.encode(Fixtures.nested1).toByteArray

  /** Everything the buffer holds, from index 0 up to where writing stopped. */
  private def contentsOf(bb: ByteBuffer): Array[Byte] =
    bb.flip()
    val out = new Array[Byte](bb.remaining())
    bb.get(out)
    out

  private def assertSameBytes(
      actual: Array[Byte],
      expected: Array[Byte]
  )(using munit.Location): Unit =
    // The lengths first. Two arrays of several hundred bytes that differ
    // somewhere report nothing useful on their own, while "734 was not 756"
    // usually names the cause outright.
    assertEquals(actual.length, expected.length)
    assert(actual.sameElements(expected))

  /** Registers the whole set for one format.
    *
    * Both formats are asked the same questions and the answers must be the
    * same; only the encoder that gets summoned and the bytes plain borer writes
    * differ, so those two are the parameters.
    *
    * @param plain
    *   what `io.bullet.borer.Cbor.encode` / `Json.encode` produce for the very
    *   same value
    */
  private def sinkTests[F <: Format](name: String, plain: Array[Byte])(using
      enc: Encoder[Nested, F]
  ): Unit =

    val expected = enc.encode(Fixtures.nested1)

    test(s"$name: the bridge writes what plain borer writes") {
      // The point of the suite. Going through b8 must not change the wire
      // format in any way, otherwise the bridge would quietly fork borer's two
      // formats into b8 dialects of them. The sizes here are 734 bytes of CBOR
      // and 932 of JSON today, but they are not asserted: a change to the
      // fixture should move them without turning this suite red.
      assertSameBytes(expected, plain)
    }

    test(s"$name: every sink receives the same bytes") {
      // Capacity of one, deliberately far too small, so the encode runs
      // through the growth path from the first byte on.
      val sink = ArraySink(1)
      enc.encodeTo(Fixtures.nested1, sink)
      assertSameBytes(sink.result(), expected)

      // Exactly sized: the encoder must not ask for one byte more than it
      // wrote into the sink that could have given it any number.
      val bb = ByteBuffer.allocate(expected.length)
      enc.encodeTo(Fixtures.nested1, ByteBufferSink(bb))
      assertSameBytes(contentsOf(bb), expected)

      val os = new ByteArrayOutputStream()
      enc.encodeTo(Fixtures.nested1, OutputStreamSink(os))
      assertSameBytes(os.toByteArray, expected)
    }

    test(s"$name: a sink that already holds bytes keeps them") {
      // A sink is append-only, and an encoder that reset it or wrote from
      // index 0 would destroy a framing header the caller put there first.
      val prefix = Array[Byte](1, 2, 3)

      val sink = ArraySink(1)
      sink.write(prefix)
      enc.encodeTo(Fixtures.nested1, sink)
      assertSameBytes(sink.result(), prefix ++ expected)

      val bb = ByteBuffer.allocate(prefix.length + expected.length)
      val buffered = ByteBufferSink(bb)
      buffered.write(prefix)
      enc.encodeTo(Fixtures.nested1, buffered)
      assertSameBytes(contentsOf(bb), prefix ++ expected)
    }

    test(s"$name: a pooled buffer produces the same bytes") {
      // `expected` came from the default pool, which allocates a fresh sink
      // every time. Twice through a pooling one, because the second encode is
      // the one that gets the buffer the first left behind: a sink handed back
      // without being reset would show up here and nowhere else.
      val pool = SinkPool.threadLocal()
      assertSameBytes(enc.encode(Fixtures.nested1)(using pool), expected)
      assertSameBytes(enc.encode(Fixtures.nested1)(using pool), expected)
    }

    test(s"$name: a buffer too small raises the buffer's own exception") {
      // Room for eight bytes of a message that needs hundreds. What comes out
      // is `java.nio.BufferOverflowException`, unwrapped — not a
      // `Borer.Error`, and not a `b8.DecodeError`, which belongs to the read
      // side anyway. This is what the bridge builds its writer with
      // `Cbor.writer` / `Json.writer` for: the `encode(a).to(…)` DSL catches
      // every non-fatal exception in its terminal operations and re-throws it
      // as a `Borer.Error.General`, so a caller who sized their own buffer
      // would have to unwrap a borer error to learn that it was too small.
      val bb = ByteBuffer.allocate(8)
      intercept[BufferOverflowException] {
        enc.encodeTo(Fixtures.nested1, ByteBufferSink(bb))
      }
    }

  sinkTests[Format.Cbor]("cbor", plainCbor)
  sinkTests[Format.Json]("json", plainJson)
