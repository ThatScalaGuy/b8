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

package b8

import b8.array.*

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*

/** Test-only format: the encoding is the string's UTF-8 bytes, nothing around
  * them.
  */
trait Utf8 extends Format.Text

given Codec[String, Utf8] with
  def encodeTo(a: String, out: ByteSink): Unit = out.write(a.getBytes(UTF_8))
  override def sizeHint(a: String): Int = a.length * 3
  def decodeUnsafe(in: ByteSource): String =
    new String(in.array, in.offset, in.length, UTF_8)

class RoundTripSuite extends ScalaCheckSuite:

  private def roundTrips(s: String)(using SinkPool): Boolean =
    s.encode[Utf8].decodeAs[String, Utf8] == Right(s)

  /** Starts far too small on purpose, so every encode goes through the growth
    * path.
    */
  private def viaArraySink(s: String): Array[Byte] =
    val sink = ArraySink(1)
    s.encodeTo[Utf8](sink)
    sink.result()

  private def viaByteBufferSink(s: String, size: Int): Array[Byte] =
    val bb = ByteBuffer.allocate(size)
    s.encodeTo[Utf8](ByteBufferSink(bb))
    bb.flip()
    val out = new Array[Byte](bb.remaining())
    bb.get(out)
    out

  private def viaOutputStreamSink(s: String): Array[Byte] =
    val os = new ByteArrayOutputStream()
    s.encodeTo[Utf8](OutputStreamSink(os))
    os.toByteArray

  property("encode then decode gives the value back") {
    forAll { (s: String) => roundTrips(s) }
  }

  property("decoding a window at a non-zero offset gives the same value") {
    forAll { (s: String, pad: Byte) =>
      val bytes = s.encode[Utf8]
      val padded = Array(pad, pad, pad) ++ bytes ++ Array(pad)
      Decoder[String, Utf8].decode(
        ByteSource(padded, 3, bytes.length)
      ) == Right(s)
    }
  }

  property("every sink produces the same bytes") {
    forAll { (s: String) =>
      val expected = viaArraySink(s)
      expected.sameElements(viaByteBufferSink(s, expected.length)) &&
      expected.sameElements(viaOutputStreamSink(s)) &&
      expected.sameElements(s.encode[Utf8])
    }
  }

  property("a thread-local pool changes neither the bytes nor the round trip") {
    given SinkPool = SinkPool.threadLocal()
    forAll { (s: String) =>
      roundTrips(s) && s.encode[Utf8].sameElements(viaArraySink(s))
    }
  }
