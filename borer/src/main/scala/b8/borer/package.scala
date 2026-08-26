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

import scala.util.NotGiven

/** Both formats at once.
  *
  * borer's `Encoder` and `Decoder` say what the shape of a value is, not how it
  * is spelled, so the same borer codec drives CBOR and JSON alike. That is why
  * this package can hand out six givens where a single-format bridge hands out
  * three, and why `import b8.borer.given` is enough to make
  * `encode[Format.Cbor]` and `encode[Format.Json]` both work on the same type.
  *
  * There are three ways in, and they are alternatives rather than layers:
  *
  *   - `import b8.borer.given` — both formats
  *   - `import b8.borer.cbor.given` — CBOR only
  *   - `import b8.borer.json.given` — JSON only
  *
  * Never combine `b8.borer.given` with one of the sub-packages in the same
  * scope: both would offer a `Codec[A, Format.Cbor]`, and the summon becomes
  * ambiguous. The sub-packages exist so that the two formats can be sourced
  * from different backends — borer for CBOR next to another bridge for JSON —
  * which is exactly the case the aggregate import cannot serve.
  *
  * Every given here is a call into `b8.borer.cbor` or `b8.borer.json`. The
  * configuration factories live there too, and there are no forwarders for them
  * here: a caller who is configuring the backend is already naming the format.
  */
given [A](using
    io.bullet.borer.Encoder[A],
    io.bullet.borer.Decoder[A]
): Codec[A, Format.Cbor] =
  cbor.codec()

/** Write-only CBOR bridge, for the types borer can write but not read back. */
given [A](using
    io.bullet.borer.Encoder[A],
    NotGiven[io.bullet.borer.Decoder[A]]
): Encoder[A, Format.Cbor] =
  cbor.encoder()

/** Read-only CBOR bridge, for the types borer can read but not write. */
given [A](using
    io.bullet.borer.Decoder[A],
    NotGiven[io.bullet.borer.Encoder[A]]
): Decoder[A, Format.Cbor] =
  cbor.decoder()

/** Every type borer can both write and read also gets the JSON codec. */
given [A](using
    io.bullet.borer.Encoder[A],
    io.bullet.borer.Decoder[A]
): Codec[A, Format.Json] =
  json.codec()

/** Write-only JSON bridge, for the types borer can write but not read back. */
given [A](using
    io.bullet.borer.Encoder[A],
    NotGiven[io.bullet.borer.Decoder[A]]
): Encoder[A, Format.Json] =
  json.encoder()

/** Read-only JSON bridge, for the types borer can read but not write. */
given [A](using
    io.bullet.borer.Decoder[A],
    NotGiven[io.bullet.borer.Encoder[A]]
): Decoder[A, Format.Json] =
  json.decoder()
