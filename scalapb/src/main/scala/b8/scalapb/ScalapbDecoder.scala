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

package b8.scalapb

import b8.ByteSource
import b8.DecodeError
import b8.Decoder
import b8.Format.Proto

import com.google.protobuf.CodedInputStream
import com.google.protobuf.InvalidProtocolBufferException
import scalapb.GeneratedMessage
import scalapb.GeneratedMessageCompanion

/** The read half of the ScalaPB bridge for one type.
  *
  * @param recursionLimit
  *   protobuf's nesting bound, set on the stream before parsing; see
  *   `b8.scalapb.decoder` for why ScalaPB does not act on it
  */
final class ScalapbDecoder[A <: GeneratedMessage](recursionLimit: Int)(using
    cmp: GeneratedMessageCompanion[A]
) extends Decoder[A, Proto]:

  def decodeUnsafe(in: ByteSource): A =
    ScalapbDecoder.decodeUnsafe(in, recursionLimit, cmp)

object ScalapbDecoder:

  /** The body `ScalapbDecoder` and `ScalapbCodec` both run.
    *
    * `CodedInputStream.newInstance` takes an array with an offset and a length,
    * which is exactly what a `ByteSource` is, so the window is parsed where it
    * lies: `in.toArray` appears nowhere and nothing is copied. The stream stops
    * at the end of the window, so a decode never reads the bytes a caller's
    * frame put after the message.
    *
    * Only `InvalidProtocolBufferException` becomes a `DecodeError`, and only
    * here. Protobuf raises it for everything that is wrong with the input at
    * the wire level — a truncated field, an invalid tag, a wire type protobuf
    * has no rule for — and both protobuf-java and ScalaPB's own unknown-field
    * reader throw that one type. Its message names what went wrong but never
    * where, and b8 does not invent an offset it would then have to keep true.
    *
    * Anything else propagates unwrapped, which for ScalaPB is worth naming
    * rather than leaving to be discovered: nesting deep enough to exhaust the
    * stack raises `StackOverflowError`, not a `DecodeError`. See
    * `b8.scalapb.decoder` for why `recursionLimit` does not prevent that.
    */
  private[scalapb] def decodeUnsafe[A <: GeneratedMessage](
      in: ByteSource,
      recursionLimit: Int,
      cmp: GeneratedMessageCompanion[A]
  ): A =
    val cis = CodedInputStream.newInstance(in.array, in.offset, in.length)
    // Hands back the limit it replaced, which nothing here has a use for.
    val _ = cis.setRecursionLimit(recursionLimit)
    try cmp.parseFrom(cis)
    catch
      case e: InvalidProtocolBufferException =>
        throw DecodeError(e.getMessage, "Proto", e)
