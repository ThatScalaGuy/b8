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

package b8.stream

import b8.Decoder
import b8.Encoder
import b8.Format

import fs2.Pipe
import fs2.RaiseThrowable

/** A pipe that turns values into a framed byte stream.
  *
  * The format is the only type argument you give; `F` and `A` are inferred
  * where the pipe meets the stream:
  *
  * {{{
  * values.through(b8.stream.encode[Format.Json](Framing.Newline))
  * }}}
  *
  * Which backend does the encoding is decided the usual way, by whichever
  * `Encoder[A, Fmt]` is in scope.
  */
def encode[Fmt <: Format]: EncodeBuilder[Fmt] = new EncodeBuilder[Fmt]

/** A pipe that reads framed bytes back into values.
  *
  * {{{
  * bytes.through(b8.stream.decode[Format.Proto](Framing.Varint, maxFrame = 1024))
  * }}}
  */
def decode[Fmt <: Format]: DecodeBuilder[Fmt] = new DecodeBuilder[Fmt]

/** Holds the format while `F` and `A` are still open.
  *
  * Scala applies type arguments per list, all or nothing, so a single method
  * would force the caller to spell out the effect and the element type as well
  * — the two the compiler can work out on its own. Splitting the application in
  * two is what keeps the format the only thing that has to be written down.
  */
final class EncodeBuilder[Fmt <: Format] private[stream] ():

  /** @param framing
    *   how messages are delimited; `Framing.Fixed32` unless you say otherwise
    */
  def apply[F[_], A](
      framing: Framing[Fmt] = Framing.Fixed32
  )(using Encoder[A, Fmt]): Pipe[F, A, Byte] =
    Framed.encodePipe(framing)

/** The decode half of [[EncodeBuilder]]. */
final class DecodeBuilder[Fmt <: Format] private[stream] ():

  /** @param framing
    *   how messages are delimited; `Framing.Fixed32` unless you say otherwise
    * @param maxFrame
    *   largest frame accepted, in bytes. A declared or accumulated frame beyond
    *   it fails the stream before the frame is buffered.
    */
  def apply[F[_], A](
      framing: Framing[Fmt] = Framing.Fixed32,
      maxFrame: Int = Framing.DefaultMaxFrame
  )(using Decoder[A, Fmt], RaiseThrowable[F]): Pipe[F, Byte, A] =
    Framed.decodePipe(framing, maxFrame)
