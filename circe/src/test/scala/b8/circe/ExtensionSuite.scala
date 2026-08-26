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

package b8.circe

import b8.ArraySink
import b8.ByteBufferSink
import b8.ByteSource
import b8.Decoder
import b8.Format.Json
import b8.OutputStreamSink
import b8.array.*
import b8.circe.given
import b8.laws.Fixtures
import b8.laws.Flat

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

import io.circe.generic.semiauto.deriveCodec

/** The bridge as a user meets it: a circe codec for the type, the two b8
  * imports, and from there encoding and decoding are methods on the values
  * themselves.
  */
class ExtensionSuite extends munit.FunSuite:

  given io.circe.Codec[Flat] = deriveCodec

  private val encoded: Array[Byte] = Fixtures.flat1.encode[Json]

  test("encode and decode are inverses") {
    assertEquals(
      Fixtures.flat1.encode[Json].decodeAs[Flat, Json],
      Right(Fixtures.flat1)
    )
  }

  test("encodeTo writes the same bytes into every sink") {
    // Deliberately far too small, so the encode runs through the growth path.
    val sink = ArraySink(1)
    Fixtures.flat1.encodeTo[Json](sink)
    assert(sink.result().sameElements(encoded))

    val bb = ByteBuffer.allocate(encoded.length)
    Fixtures.flat1.encodeTo[Json](ByteBufferSink(bb))
    bb.flip()
    val fromBuffer = new Array[Byte](bb.remaining())
    bb.get(fromBuffer)
    assert(fromBuffer.sameElements(encoded))

    val os = new ByteArrayOutputStream()
    Fixtures.flat1.encodeTo[Json](OutputStreamSink(os))
    assert(os.toByteArray.sameElements(encoded))
  }

  test("a window of a larger array decodes to the same value") {
    val padded = Array[Byte](0x7f, 0x7f, 0x7f) ++ encoded ++ Array[Byte](0x7f)
    // `decodeAs` reads a whole array, so a window into a bigger one is spelled
    // out with the `ByteSource` that extension wraps around it.
    assertEquals(
      Decoder[Flat, Json].decode(ByteSource(padded, 3, encoded.length)),
      Right(Fixtures.flat1)
    )
    // And the whole array really is a different input: the padding is not
    // quietly skipped.
    assert(padded.decodeAs[Flat, Json].isLeft)
  }
