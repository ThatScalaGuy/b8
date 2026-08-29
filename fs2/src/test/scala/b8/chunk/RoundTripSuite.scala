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

package b8.chunk

import b8.ByteSink
import b8.ByteSource
import b8.Codec
import b8.Encoder
import b8.Format
import b8.SinkPool

import java.nio.charset.StandardCharsets.UTF_8

import fs2.Chunk
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

/** Test-only format: the encoding is the string's UTF-8 bytes, nothing around
  * them. The same one `b8-core`'s own round-trip suite uses, written out again
  * here because it lives in that module's test sources.
  */
trait Utf8 extends Format.Text

given Codec[String, Utf8] with
  def encodeTo(a: String, out: ByteSink): Unit = out.write(a.getBytes(UTF_8))
  override def sizeHint(a: String): Int = a.length * 3
  def decodeUnsafe(in: ByteSource): String =
    new String(in.array, in.offset, in.length, UTF_8)

/** The container over a codec that has no opinions, so that anything that goes
  * wrong is the container's.
  *
  * The array the chunk is compared against is taken from `Encoder.encode`
  * directly rather than through `b8.array`. Not because the two imports would
  * clash here — this file is inside `b8.chunk`, where the extensions are
  * package members, so an explicit `import b8.array.*` would quietly outrank
  * them and `encode` would return an `Array[Byte]` with nothing to show for it.
  * Silent shadowing is the worse failure of the two, and naming the encoder is
  * how this suite stays out of reach of it. `b8.container.ImportSuite` is where
  * the ambiguity a user actually meets is pinned down.
  *
  * The last property is the one `b8-scodec`'s copy of this suite cannot have:
  * it puts the chunk, the array and a chunk rebuilt over that array side by
  * side. There is no `ByteVector` in it — `b8-fs2` does not depend on
  * `b8-scodec` and never should — so the array is the third point of
  * comparison, which is also the only one every container has in common.
  */
class RoundTripSuite extends ScalaCheckSuite:

  private val genChunk: Gen[Chunk[Byte]] =
    Gen
      .choose(0, 8)
      .flatMap(n =>
        Gen
          .listOfN(n, Gen.choose(Byte.MinValue, Byte.MaxValue))
          .map(bs => Chunk.array(bs.toArray))
      )

  property("the chunk holds exactly the bytes the encoder produced") {
    forAll { (s: String) =>
      s.encode[Utf8].toArray[Byte].sameElements(Encoder[String, Utf8].encode(s))
    }
  }

  property("the chunk is exact-size") {
    forAll { (s: String) =>
      s.encode[Utf8].size == Encoder[String, Utf8].encode(s).length
    }
  }

  property("encode then decode gives the value back") {
    forAll { (s: String) =>
      s.encode[Utf8].decodeAs[String, Utf8] == Right(s)
    }
  }

  property("a slice of a concatenation decodes to the same value") {
    forAll(Gen.alphaNumStr, genChunk, genChunk) {
      (s: String, prefix: Chunk[Byte], suffix: Chunk[Byte]) =>
        val payload = s.encode[Utf8]
        val framed = prefix ++ payload ++ suffix
        framed
          .drop(prefix.size)
          .take(payload.size)
          .decodeAs[String, Utf8] == Right(s)
    }
  }

  property("decodeAsUnsafe agrees with decodeAs") {
    forAll { (s: String) =>
      val bytes = s.encode[Utf8]
      bytes.decodeAsUnsafe[String, Utf8] == bytes
        .decodeAs[String, Utf8]
        .getOrElse(fail("decodeAs rejected bytes it had just produced"))
    }
  }

  property("a thread-local pool changes neither the bytes nor the round trip") {
    given SinkPool = SinkPool.threadLocal()
    forAll { (s: String) =>
      val bytes = s.encode[Utf8]
      bytes.toArray[Byte].sameElements(Encoder[String, Utf8].encode(s)) &&
      bytes.decodeAs[String, Utf8] == Right(s)
    }
  }

  property("encodeTo writes the same bytes into a sink the caller brought") {
    forAll { (s: String) =>
      // Deliberately far too small, so every encode goes through the sink's
      // growth path before the extension method's caller ever sees it.
      val sink = b8.ArraySink(1)
      s.encodeTo[Utf8](sink)
      Chunk.array(sink.result()) == s.encode[Utf8]
    }
  }

  property("chunk and array hold the same bytes, and both decode back") {
    forAll { (s: String) =>
      val array = Encoder[String, Utf8].encode(s)
      // The chunk built by the container and the chunk a caller builds by hand
      // over the encoder's array are the same bytes, and the container reads
      // both — which is what makes `b8.chunk` and `b8.array` interchangeable
      // rather than merely similar.
      s.encode[Utf8].toArray[Byte].sameElements(array) &&
      Chunk.array(array).decodeAs[String, Utf8] == Right(s)
    }
  }
