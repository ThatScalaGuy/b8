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

import b8.ByteSource
import b8.DecodeError
import b8.Decoder
import b8.Format

import io.bullet.borer.Borer
import io.bullet.borer.Json

/** Decodes `A` from JSON, reading the source where it lies.
  *
  * Trailing input is borer's own business here, and it is worth knowing exactly
  * what borer does: it reads one value and then insists on end of input, so `}`
  * or a stray `x` after the value is rejected, and whitespace after it is not.
  * Two bytes fall on the accepting side that a reader might not expect — every
  * byte up to `0x20` counts as whitespace, so a trailing NUL passes, and `0xFF`
  * is the parser's own end-of-input marker, so a trailing `0xFF` passes too.
  * Neither is a gap the bridge papers over: a second scan of the input to catch
  * two byte values would cost every well-formed message, and CBOR — the format
  * b8 reaches for when the bytes matter — rejects both.
  *
  * @param config
  *   borer's decoding config, with one change b8 makes on its behalf; see
  *   `defaultDecodingConfig`
  */
final class JsonDecoder[A](config: Json.DecodingConfig)(using
    dec: io.bullet.borer.Decoder[A]
) extends Decoder[A, Format.Json]:

  def decodeUnsafe(in: ByteSource): A =
    JsonDecoder.decodeUnsafe(in, config, dec)

object JsonDecoder:

  /** The decode path, shared with `JsonCodec` so that neither class carries a
    * second copy of it.
    *
    * Two ways in, both zero-copy. A source that covers its whole array goes in
    * as the array, which is the only input borer's fast direct parser accepts —
    * it reads and UTF-8-decodes in one pass and is skipped for every other
    * input type. A window into a larger array goes in as a `ByteBuffer`, whose
    * `position` and `limit` borer bounds every read on, so the parser never
    * sees a byte outside the window. `toArray` appears in neither branch.
    *
    * Only `Borer.Error` is wrapped. Anything else — a bug in a hand-written
    * borer decoder, say — surfaces as itself rather than being reported as
    * malformed input. borer's message already ends in `(input position N)`, so
    * the position is not appended a second time.
    */
  private[json] def decodeUnsafe[A](
      in: ByteSource,
      config: Json.DecodingConfig,
      dec: io.bullet.borer.Decoder[A]
  ): A =
    val setup =
      if in.offset == 0 && in.length == in.array.length then
        Json.decode(in.array)
      else Json.decode(in.asByteBuffer)
    try setup.withConfig(config).to[A](using dec).value
    catch case e: Borer.Error[?] => throw DecodeError(e.getMessage, "Json", e)
