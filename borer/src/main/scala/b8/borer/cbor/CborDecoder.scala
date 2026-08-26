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

import b8.ByteSource
import b8.DecodeError
import b8.Decoder
import b8.Format

import io.bullet.borer.Borer
import io.bullet.borer.Cbor

/** Decodes `A` from CBOR, reading the source where it lies.
  *
  * Trailing input needs no check of its own: borer reads one value and then
  * insists on end of input, so a byte left over is a `Borer.Error` and becomes
  * a `DecodeError` like any other malformed input. Only `withPrefixOnly` would
  * relax that, and the bridge never asks for it — a b8 decoder consumes the
  * whole source.
  *
  * @param config
  *   borer's decoding config: the nesting, length and number limits
  */
final class CborDecoder[A](config: Cbor.DecodingConfig)(using
    dec: io.bullet.borer.Decoder[A]
) extends Decoder[A, Format.Cbor]:

  def decodeUnsafe(in: ByteSource): A =
    CborDecoder.decodeUnsafe(in, config, dec)

object CborDecoder:

  /** The decode path, shared with `CborCodec` so that neither class carries a
    * second copy of it.
    *
    * Two ways in, both zero-copy, and the difference is only which one borer
    * reads faster. A source that covers its whole array goes in as the array,
    * which is the input borer optimises hardest for; a window into a larger
    * array goes in as a `ByteBuffer`, whose `position` and `limit` borer bounds
    * every read on, so the parser never sees a byte outside the window.
    * `toArray` appears in neither branch: copying the input to decode it is the
    * thing this bridge exists to avoid.
    *
    * Only `Borer.Error` is caught, and it is worth being precise about how
    * little that excludes. borer's own decoding DSL catches every non-fatal
    * exception first and re-throws it as a `Borer.Error.General`, so a bug in a
    * hand-written borer decoder arrives here already dressed as a borer error
    * and does become a `DecodeError` — with the original exception two links
    * down the cause chain. That is borer's decision, not the bridge's, and
    * undoing it would mean unwrapping `General` and guessing which of its
    * causes were really malformed input. What still propagates untouched is
    * everything `NonFatal` does not cover: `StackOverflowError` and the rest.
    *
    * borer's message already ends in `(input position N)`, so the position is
    * not appended a second time.
    */
  private[cbor] def decodeUnsafe[A](
      in: ByteSource,
      config: Cbor.DecodingConfig,
      dec: io.bullet.borer.Decoder[A]
  ): A =
    val setup =
      if in.offset == 0 && in.length == in.array.length then
        Cbor.decode(in.array)
      else Cbor.decode(in.asByteBuffer)
    try setup.withConfig(config).to[A](using dec).value
    catch case e: Borer.Error[?] => throw DecodeError(e.getMessage, "Cbor", e)
