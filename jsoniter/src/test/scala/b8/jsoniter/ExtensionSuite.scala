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
import b8.ByteSource
import b8.Decoder
import b8.Format.Json
import b8.array.*
import b8.jsoniter.given
import b8.laws.Fixtures
import b8.laws.Flat
import b8.laws.Nested

/** The bridge as a user meets it: a jsoniter codec for the type, the two b8
  * imports, and from there encoding and decoding are methods on the values
  * themselves.
  *
  * The suite sits inside `b8.jsoniter`, so `import b8.jsoniter.given` names a
  * given that is a package member here anyway. It is written out because it is
  * the line a user elsewhere has to write, and showing that shape is the point
  * of the suite.
  */
class ExtensionSuite extends munit.FunSuite:

  import Codecs.given

  private val encoded: Array[Byte] = Fixtures.nested1.encode[Json]

  test("encode and decode are inverses") {
    assertEquals(
      Fixtures.flat1.encode[Json].decodeAs[Flat, Json],
      Right(Fixtures.flat1)
    )
    assertEquals(
      Fixtures.nested1.encode[Json].decodeAs[Nested, Json],
      Right(Fixtures.nested1)
    )
  }

  test("encodeTo writes into a sink the caller brought") {
    // Deliberately far too small, so the encode runs through the growth path
    // before it ever reaches the extension method's caller.
    val sink = ArraySink(1)
    Fixtures.nested1.encodeTo[Json](sink)
    assert(sink.result().sameElements(encoded))
  }

  test("a window of a larger array decodes to the same value") {
    val padded = Array[Byte](0x7f, 0x7f, 0x7f) ++ encoded ++ Array[Byte](0x7f)
    // `decodeAs` reads a whole array, so a window into a bigger one is spelled
    // out with the `ByteSource` that extension wraps around it.
    assertEquals(
      Decoder[Nested, Json].decode(ByteSource(padded, 3, encoded.length)),
      Right(Fixtures.nested1)
    )
    // And the whole array really is a different input: the padding is not
    // quietly skipped.
    assert(padded.decodeAs[Nested, Json].isLeft)
  }
