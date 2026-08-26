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

package b8.borer.cbor

import b8.Codec
import b8.Decoder
import b8.Encoder
import b8.Format

import io.bullet.borer.Cbor

import scala.util.NotGiven

/** Builds the b8 CBOR encoder for `A` from its borer encoder.
  *
  * @param config
  *   borer's defaults: floating point values compressed to the smallest form
  *   that loses nothing, and a 1 KB buffer size, which the bridge reads as its
  *   `sizeHint`
  */
def encoder[A](config: Cbor.EncodingConfig = Cbor.EncodingConfig.default)(using
    io.bullet.borer.Encoder[A]
): Encoder[A, Format.Cbor] =
  CborEncoder(config)

/** Builds the b8 CBOR decoder for `A` from its borer decoder.
  *
  * @param config
  *   borer's defaults, unchanged: at most 1000 nesting levels, integers
  *   readable as floating point values, and no limit worth the name on array
  *   and map lengths
  */
def decoder[A](config: Cbor.DecodingConfig = Cbor.DecodingConfig.default)(using
    io.bullet.borer.Decoder[A]
): Decoder[A, Format.Cbor] =
  CborDecoder(config)

/** Builds the b8 CBOR codec for `A` from its borer encoder and decoder. */
def codec[A](
    encoding: Cbor.EncodingConfig = Cbor.EncodingConfig.default,
    decoding: Cbor.DecodingConfig = Cbor.DecodingConfig.default
)(using
    io.bullet.borer.Encoder[A],
    io.bullet.borer.Decoder[A]
): Codec[A, Format.Cbor] =
  CborCodec(encoding, decoding)

/** Every type borer can both write and read gets the CBOR codec, with borer's
  * own configs.
  *
  * This one and the two below never compete: the one-way givens ask for the
  * absence of the other borer instance, so for any given type at most one of
  * the three applies. A type with both instances therefore also summons as an
  * `Encoder` or a `Decoder` on its own — through this codec, unambiguously.
  */
given [A](using
    io.bullet.borer.Encoder[A],
    io.bullet.borer.Decoder[A]
): Codec[A, Format.Cbor] =
  codec()

/** Write-only bridge, for the types borer can write but not read back. */
given [A](using
    io.bullet.borer.Encoder[A],
    NotGiven[io.bullet.borer.Decoder[A]]
): Encoder[A, Format.Cbor] =
  encoder()

/** Read-only bridge, for the types borer can read but not write. */
given [A](using
    io.bullet.borer.Decoder[A],
    NotGiven[io.bullet.borer.Encoder[A]]
): Decoder[A, Format.Cbor] =
  decoder()
