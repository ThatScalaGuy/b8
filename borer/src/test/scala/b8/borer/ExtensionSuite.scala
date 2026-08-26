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
import b8.ByteSource
import b8.Decoder
import b8.Format
import b8.OutputStreamSink
import b8.array.*
import b8.borer.given
import b8.laws.Fixtures
import b8.laws.Nested

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** The bridge as a user meets it: one borer codec for the type, the two b8
  * imports, and from there encoding and decoding are methods on the values
  * themselves.
  *
  * The suite sits inside `b8.borer`, so `import b8.borer.given` names givens
  * that are package members here anyway. It is written out because it is the
  * line a user elsewhere has to write, and showing that shape is the point of
  * the suite.
  */
class ExtensionSuite extends munit.FunSuite:

  import Codecs.given

  private val asCbor: Array[Byte] = Fixtures.nested1.encode[Format.Cbor]
  private val asJson: Array[Byte] = Fixtures.nested1.encode[Format.Json]

  test("encode and decode are inverses, in either format") {
    assertEquals(
      Fixtures.nested1.encode[Format.Cbor].decodeAs[Nested, Format.Cbor],
      Right(Fixtures.nested1)
    )
    assertEquals(
      Fixtures.nested1.encode[Format.Json].decodeAs[Nested, Format.Json],
      Right(Fixtures.nested1)
    )
  }

  test("one borer codec, two formats") {
    // The reason this bridge is worth having. `Codecs` derives a single borer
    // codec for `Nested` and nothing in it mentions CBOR or JSON: borer's
    // instances describe the shape of a value, not its spelling, so the format
    // in the type parameter is the only thing that picks the bytes.
    assert(!asCbor.sameElements(asJson))
    // Both spellings carry the field names as text, so the saving is not in
    // the keys: CBOR writes lengths and numbers as bytes where JSON writes
    // them as digits, punctuation and quotes.
    assert(
      asCbor.length < asJson.length,
      s"cbor ${asCbor.length}, json ${asJson.length}"
    )
    // And neither spelling loses anything on the way.
    assertEquals(asCbor.decodeAs[Nested, Format.Cbor], Right(Fixtures.nested1))
    assertEquals(asJson.decodeAs[Nested, Format.Json], Right(Fixtures.nested1))
  }

  test("encodeTo writes the same bytes into every sink") {
    // `SinkSuite` walks the sinks for both formats; here the smaller fixture
    // and one format are enough to show that the sink is the caller's choice
    // and changes nothing about the bytes.
    val encoded = Fixtures.flat1.encode[Format.Cbor]

    // Deliberately far too small, so the encode runs through the growth path.
    val sink = ArraySink(1)
    Fixtures.flat1.encodeTo[Format.Cbor](sink)
    assert(sink.result().sameElements(encoded))

    val bb = ByteBuffer.allocate(encoded.length)
    Fixtures.flat1.encodeTo[Format.Cbor](ByteBufferSink(bb))
    bb.flip()
    val fromBuffer = new Array[Byte](bb.remaining())
    bb.get(fromBuffer)
    assert(fromBuffer.sameElements(encoded))

    val os = new ByteArrayOutputStream()
    Fixtures.flat1.encodeTo[Format.Cbor](OutputStreamSink(os))
    assert(os.toByteArray.sameElements(encoded))
  }

  test("a window of a larger array decodes to the same value") {
    val padded = Array[Byte](0, 0, 0) ++ asCbor ++ Array[Byte](0)
    // `decodeAs` reads a whole array, so a window into a bigger one is spelled
    // out with the `ByteSource` that extension wraps around it.
    val window = ByteSource(padded, 3, asCbor.length)
    assertEquals(
      Decoder[Nested, Format.Cbor].decode(window),
      Right(Fixtures.nested1)
    )
    // The padding is not quietly skipped. A NUL byte is a complete CBOR value
    // of its own — the integer zero — so the whole array is a different input,
    // and it fails on the very first byte.
    assert(padded.decodeAs[Nested, Format.Cbor].isLeft)
  }
