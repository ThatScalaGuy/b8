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

import b8.ByteSource
import b8.DecodeError
import b8.Decoder
import b8.Format.Json

import io.circe.DecodingFailure
import io.circe.ParsingFailure
import io.circe.jawn.JawnParser

/** Decodes `A` from JSON with circe's jawn parser.
  *
  * The source goes to jawn as a `ByteBuffer` view of its window, so the bytes
  * are read where they lie and no `String` is built on the way in.
  *
  * Trailing input needs no check of its own here: jawn reads one value and then
  * insists on end of input, accepting only space, tab, CR and LF after it.
  * `{"a":1} x`, `[1,2,3]xyz` and even a single trailing NUL byte all come back
  * as a `ParsingFailure`. That is worth stating because it is easy to assume
  * otherwise and add a second, redundant check.
  *
  * @param parser
  *   jawn parser deciding the value-size limit and duplicate-key handling
  */
final class CirceDecoder[A](parser: JawnParser)(using
    dec: io.circe.Decoder[A]
) extends Decoder[A, Json]:

  def decodeUnsafe(in: ByteSource): A =
    CirceDecoder.decodeUnsafe(in, parser, dec)

object CirceDecoder:

  /** The decode path, shared with `CirceCodec` so that neither class carries a
    * second copy of it.
    *
    * Both malformed-input cases become a `DecodeError` that keeps circe's own
    * failure as its cause. For a `DecodingFailure` the message comes from
    * `getMessage` rather than `message`: only `getMessage` carries the cursor
    * history, which is the part saying *where* the document went wrong.
    */
  private[circe] def decodeUnsafe[A](
      in: ByteSource,
      parser: JawnParser,
      dec: io.circe.Decoder[A]
  ): A =
    parser.decodeByteBuffer[A](in.asByteBuffer)(using dec) match
      case Right(a)                 => a
      case Left(pf: ParsingFailure) =>
        throw DecodeError(pf.message, "Json", pf)
      case Left(df: DecodingFailure) =>
        throw DecodeError(df.getMessage, "Json", df)
      // Two cases and no catch-all: `io.circe.Error` is sealed over exactly
      // these two. The accumulating `io.circe.Errors` extends `Exception`
      // directly rather than `Error`, so it cannot come out of a
      // `decodeByteBuffer`, and a third case here would be dead code the
      // compiler rejects.
