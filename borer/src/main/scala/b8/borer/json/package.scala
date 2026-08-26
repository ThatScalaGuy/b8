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

package b8.borer.json

import b8.Codec
import b8.Decoder
import b8.Encoder
import b8.Format

import io.bullet.borer.Json

import scala.util.NotGiven

/** borer's decoding defaults, with the one number limit b8 raises.
  *
  * borer caps the absolute exponent of a JSON number at 64, which is a sensible
  * guard against a megabyte of digits arriving from a stranger. It is also
  * lower than what borer's own JSON *encoder* writes: `Double.MaxValue` prints
  * as `1.7976931348623157E308` and `Double.MinPositiveValue` as `4.9E-324`, and
  * both come back as a decode failure under the default. A codec that cannot
  * read its own output is not a codec, so the bridge raises the cap to 999, the
  * highest value borer accepts.
  *
  * Nothing else is touched, and borer's own setting is one argument away:
  * `b8.borer.json.decoder[A](Json.DecodingConfig.default)`.
  */
val defaultDecodingConfig: Json.DecodingConfig =
  Json.DecodingConfig.default.copy(maxNumberAbsExponent = 999)

/** Builds the b8 JSON encoder for `A` from its borer encoder.
  *
  * @param config
  *   borer's defaults: no indentation, which is what a wire format wants, and a
  *   1 KB buffer size, which the bridge reads as its `sizeHint`
  */
def encoder[A](config: Json.EncodingConfig = Json.EncodingConfig.default)(using
    io.bullet.borer.Encoder[A]
): Encoder[A, Format.Json] =
  JsonEncoder(config)

/** Builds the b8 JSON decoder for `A` from its borer decoder.
  *
  * @param config
  *   `defaultDecodingConfig` — borer's own settings with the number exponent
  *   cap raised so that every `Double` the encoder writes reads back
  */
def decoder[A](config: Json.DecodingConfig = defaultDecodingConfig)(using
    io.bullet.borer.Decoder[A]
): Decoder[A, Format.Json] =
  JsonDecoder(config)

/** Builds the b8 JSON codec for `A` from its borer encoder and decoder. */
def codec[A](
    encoding: Json.EncodingConfig = Json.EncodingConfig.default,
    decoding: Json.DecodingConfig = defaultDecodingConfig
)(using
    io.bullet.borer.Encoder[A],
    io.bullet.borer.Decoder[A]
): Codec[A, Format.Json] =
  JsonCodec(encoding, decoding)

/** Every type borer can both write and read gets the JSON codec.
  *
  * This one and the two below never compete: the one-way givens ask for the
  * absence of the other borer instance, so for any given type at most one of
  * the three applies. A type with both instances therefore also summons as an
  * `Encoder` or a `Decoder` on its own — through this codec, unambiguously.
  */
given [A](using
    io.bullet.borer.Encoder[A],
    io.bullet.borer.Decoder[A]
): Codec[A, Format.Json] =
  codec()

/** Write-only bridge, for the types borer can write but not read back. */
given [A](using
    io.bullet.borer.Encoder[A],
    NotGiven[io.bullet.borer.Decoder[A]]
): Encoder[A, Format.Json] =
  encoder()

/** Read-only bridge, for the types borer can read but not write. */
given [A](using
    io.bullet.borer.Decoder[A],
    NotGiven[io.bullet.borer.Encoder[A]]
): Decoder[A, Format.Json] =
  decoder()
