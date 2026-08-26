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

import b8.ByteSource
import b8.Codec
import b8.DecodeError
import b8.Format
import b8.array.*
import b8.laws.Fixtures
import b8.laws.Nested

import java.nio.charset.StandardCharsets.UTF_8

import io.bullet.borer.Borer
import io.bullet.borer.Cbor
import io.bullet.borer.Json

/** Whether the config factories are real, knob by knob.
  *
  * A bridge that takes a config object and quietly ignores half of it still
  * compiles, so every setting the bridge exposes is checked here by the
  * behaviour it changes: fewer nesting levels reject a document the default
  * accepts, uncompressed floats take more bytes, an indent adds newlines, and
  * `bufferSize` arrives as b8's size hint. The one deviation from borer's own
  * defaults — the number exponent cap — gets a test that pins both sides of it.
  *
  * Passing a config never means giving up the givens: a codec built by hand
  * wins over the imported default wherever it is in scope.
  */
class ConfigSuite extends munit.FunSuite:

  import Codecs.given

  /** A scope of its own for the shallow codec, so the rest of the suite still
    * sees the bridge's default. Inside it the hand-built codec outranks the
    * imported given, because it is a plain value of exactly the type being
    * summoned, while the bridge's given is parameterised and needs arguments of
    * its own — that makes the bridge's the less specific of the two.
    */
  private object shallow:
    given Codec[Nested, Format.Cbor] =
      cbor.codec(decoding =
        Cbor.DecodingConfig.default.copy(maxNestingLevels = 2)
      )
    def decode(bytes: Array[Byte]): Either[DecodeError, Nested] =
      bytes.decodeAs[Nested, Format.Cbor]

  test("a lower nesting limit rejects what the default accepts") {
    val bytes = Fixtures.nested1.encode[Format.Cbor]
    assertEquals(bytes.decodeAs[Nested, Format.Cbor], Right(Fixtures.nested1))

    shallow.decode(bytes) match
      case Right(a) => fail(s"expected a rejection, got $a")
      case Left(e)  =>
        assertEquals(e.format, "Cbor")
        assert(e.getCause.isInstanceOf[Borer.Error[?]], e.getCause)
        assert(clue(e.message).contains("nesting levels"))
  }

  test("switching float compression off makes the encoding longer") {
    val compressed = Fixtures.nested1.encode[Format.Cbor]
    val verbose = cbor
      .encoder[Nested](
        Cbor.EncodingConfig.default.copy(compressFloatingPointValues = false)
      )
      .encode(Fixtures.nested1)
    // borer writes a Double as a float, or even a float16, whenever that loses
    // nothing. The exact numbers are borer's business; that the setting costs
    // bytes is the bridge's.
    assert(verbose.length > compressed.length, clue(verbose.length))
    // Longer, not different: the default decoder reads the wide form back.
    assertEquals(verbose.decodeAs[Nested, Format.Cbor], Right(Fixtures.nested1))
  }

  test("an indented encoder changes only the spacing") {
    val compact = new String(Fixtures.nested1.encode[Format.Json], UTF_8)
    assert(!compact.contains("\n"), compact)

    val indented = json
      .encoder[Nested](Json.EncodingConfig.default.copy(indent = 2))
      .encode(Fixtures.nested1)
    assert(new String(indented, UTF_8).contains("\n"))
    assertEquals(
      indented.decodeAs[Nested, Format.Json],
      Right(Fixtures.nested1)
    )
  }

  test("the raised exponent cap reads back what the encoder writes") {
    // borer caps the absolute exponent of a JSON number at 64, which is a good
    // guard against a stranger sending a megabyte of digits, but it is lower
    // than what borer's own JSON encoder writes: `Double.MaxValue` prints as
    // `1.7976931348623157E308`. A codec that cannot read its own output is not
    // a codec, so the bridge raises the cap.
    val max = Double.MaxValue.toString.getBytes(UTF_8)
    val min = Double.MinPositiveValue.toString.getBytes(UTF_8)
    assertEquals(max.decodeAs[Double, Format.Json], Right(Double.MaxValue))
    assertEquals(
      min.decodeAs[Double, Format.Json],
      Right(Double.MinPositiveValue)
    )

    // borer's own setting stays one argument away, and still rejects them.
    val strict = json.decoder[Double](Json.DecodingConfig.default)
    strict.decode(ByteSource(max)) match
      case Right(d) => fail(s"expected a rejection, got $d")
      case Left(e)  =>
        assertEquals(e.format, "Json")
        assert(clue(e.message).contains("configured maximum 64"))

    assertEquals(json.defaultDecodingConfig.maxNumberAbsExponent, 999)
  }

  test("bufferSize arrives as the size hint") {
    // borer documents `bufferSize` as the size of the buffer its `Output` gets.
    // The bridge supplies the Output itself, so the number would be inert if it
    // went nowhere; read as b8's size hint it keeps its meaning, and pre-sizes
    // the sink instead of borer's buffer.
    val wide = cbor
      .encoder[Nested](Cbor.EncodingConfig.default.copy(bufferSize = 4096))
    assertEquals(wide.sizeHint(Fixtures.nested1), 4096)
    assertEquals(cbor.encoder[Nested]().sizeHint(Fixtures.nested1), 1024)
  }
