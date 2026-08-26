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

import b8.Codec
import b8.Decoder
import b8.Encoder
import b8.Format

import io.bullet.borer.derivation.MapBasedCodecs.deriveCodec

/** What the bridge resolves for a type, and what it deliberately does not.
  *
  * Every case is checked for both formats. The six givens of this package are
  * three pairs that differ only in the format tag, so a guard that is right for
  * CBOR and wrong for JSON is a mistake a single-format suite cannot see.
  *
  * The negative half is checked with `compileErrors`: "there is no decoder for
  * this type" has to be a compile error rather than a runtime surprise, which
  * is what the `NotGiven` guards on the one-way givens are for.
  *
  * The suite sits inside `b8.borer`, so the aggregate givens are already
  * package members here and need no import. A user elsewhere writes
  * `import b8.borer.given`, or one of the two per-format imports that the last
  * test covers.
  */
class ResolutionSuite extends munit.FunSuite:

  /** How the compiler starts every implicit-not-found message. Asserting on it
    * keeps the negative cases honest: a typo inside a snippet would make
    * `compileErrors` non-empty too, and would prove nothing.
    */
  private val noGiven = "No given instance of type"

  /** A scope that takes the CBOR sub-package on its own.
    *
    * The import sits deeper than the package members, so inside here it is the
    * one that applies and the two do not compete.
    */
  private object cborOnly:
    import b8.borer.cbor.given

    def codec: Codec[WriteAndRead, Format.Cbor] = summon
    def encoder: Encoder[WriteOnly, Format.Cbor] = summon
    def decoder: Decoder[ReadOnly, Format.Cbor] = summon

  /** The same for JSON, in a scope of its own. */
  private object jsonOnly:
    import b8.borer.json.given

    def codec: Codec[WriteAndRead, Format.Json] = summon
    def encoder: Encoder[WriteOnly, Format.Json] = summon
    def decoder: Decoder[ReadOnly, Format.Json] = summon

  test("both directions resolve, and both come from the codec given") {
    // Stronger than "all three resolve": the encoder and the decoder are the
    // instance the codec given built, so for a type borer knows fully the two
    // directions cannot end up configured apart.
    assert(
      summon[Encoder[WriteAndRead, Format.Cbor]]
        .isInstanceOf[cbor.CborCodec[?]]
    )
    assert(
      summon[Decoder[WriteAndRead, Format.Cbor]]
        .isInstanceOf[cbor.CborCodec[?]]
    )
    assert(
      summon[Codec[WriteAndRead, Format.Cbor]]
        .isInstanceOf[cbor.CborCodec[?]]
    )
    assert(
      summon[Encoder[WriteAndRead, Format.Json]]
        .isInstanceOf[json.JsonCodec[?]]
    )
    assert(
      summon[Decoder[WriteAndRead, Format.Json]]
        .isInstanceOf[json.JsonCodec[?]]
    )
    assert(
      summon[Codec[WriteAndRead, Format.Json]]
        .isInstanceOf[json.JsonCodec[?]]
    )
  }

  test("a write-only type resolves as an encoder and nothing else") {
    assert(
      summon[Encoder[WriteOnly, Format.Cbor]]
        .isInstanceOf[cbor.CborEncoder[?]]
    )
    assert(
      summon[Encoder[WriteOnly, Format.Json]]
        .isInstanceOf[json.JsonEncoder[?]]
    )
    assert(
      compileErrors("summon[Decoder[WriteOnly, Format.Cbor]]")
        .contains(noGiven)
    )
    assert(
      compileErrors("summon[Codec[WriteOnly, Format.Cbor]]").contains(noGiven)
    )
    assert(
      compileErrors("summon[Decoder[WriteOnly, Format.Json]]")
        .contains(noGiven)
    )
    assert(
      compileErrors("summon[Codec[WriteOnly, Format.Json]]").contains(noGiven)
    )
  }

  test("a read-only type resolves as a decoder and nothing else") {
    assert(
      summon[Decoder[ReadOnly, Format.Cbor]]
        .isInstanceOf[cbor.CborDecoder[?]]
    )
    assert(
      summon[Decoder[ReadOnly, Format.Json]]
        .isInstanceOf[json.JsonDecoder[?]]
    )
    assert(
      compileErrors("summon[Encoder[ReadOnly, Format.Cbor]]").contains(noGiven)
    )
    assert(
      compileErrors("summon[Codec[ReadOnly, Format.Cbor]]").contains(noGiven)
    )
    assert(
      compileErrors("summon[Encoder[ReadOnly, Format.Json]]").contains(noGiven)
    )
    assert(
      compileErrors("summon[Codec[ReadOnly, Format.Json]]").contains(noGiven)
    )
  }

  test("a type borer knows nothing about resolves as nothing") {
    assert(
      compileErrors("summon[Encoder[Unsupported, Format.Cbor]]")
        .contains(noGiven)
    )
    assert(
      compileErrors("summon[Decoder[Unsupported, Format.Cbor]]")
        .contains(noGiven)
    )
    assert(
      compileErrors("summon[Encoder[Unsupported, Format.Json]]")
        .contains(noGiven)
    )
    assert(
      compileErrors("summon[Decoder[Unsupported, Format.Json]]")
        .contains(noGiven)
    )
  }

  test("one sub-package import is enough on its own") {
    // The aggregate import is a convenience, not the only way in: a caller who
    // wants borer for one format only imports that half, and gets the same
    // bridge class the aggregate would have handed out. All three givens of
    // each sub-package are summoned here, not just the codec, because the
    // `NotGiven` guards are per package and the aggregate's copies of them
    // would otherwise be the only ones any test ever resolves.
    assert(cborOnly.codec.isInstanceOf[cbor.CborCodec[?]])
    assert(cborOnly.encoder.isInstanceOf[cbor.CborEncoder[?]])
    assert(cborOnly.decoder.isInstanceOf[cbor.CborDecoder[?]])
    assert(jsonOnly.codec.isInstanceOf[json.JsonCodec[?]])
    assert(jsonOnly.encoder.isInstanceOf[json.JsonEncoder[?]])
    assert(jsonOnly.decoder.isInstanceOf[json.JsonDecoder[?]])
  }

/** borer can write this one and read it back. */
final case class WriteAndRead(n: Int)
object WriteAndRead:
  given io.bullet.borer.Codec[WriteAndRead] = deriveCodec

/** Write-only. The encoder is written out by hand because a `derives` clause
  * would hand out both directions, and a type with exactly one is the case
  * under test.
  */
final case class WriteOnly(n: Int)
object WriteOnly:
  given io.bullet.borer.Encoder[WriteOnly] =
    io.bullet.borer.Encoder.forInt.contramap(_.n)

/** Read-only, the mirror image. */
final case class ReadOnly(n: Int)
object ReadOnly:
  given io.bullet.borer.Decoder[ReadOnly] =
    io.bullet.borer.Decoder.forInt.map(ReadOnly(_))

/** borer has no instance for this one in either direction. */
final case class Unsupported(n: Int)
