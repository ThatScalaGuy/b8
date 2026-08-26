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

package b8

/** Reads values of `A` from bytes in format `F`.
  *
  * A decoder consumes the whole source: bytes left over after one value are
  * malformed input, not a partial read.
  */
trait Decoder[A, F <: Format]:

  /** Decodes exactly one value from `in`.
    *
    * @throws DecodeError
    *   if the input is malformed, truncated, or has bytes left over
    */
  def decodeUnsafe(in: ByteSource): A

  /** Decodes exactly one value from `in`, turning a decode failure into a
    * `Left`.
    *
    * Costs one `Either` per message, never per field: inside the decoder,
    * failures stay exceptions.
    */
  final def decode(in: ByteSource): Either[DecodeError, A] =
    try Right(decodeUnsafe(in))
    catch case e: DecodeError => Left(e)

object Decoder:
  /** Summons the decoder of `A` for format `F`. */
  def apply[A, F <: Format](using d: Decoder[A, F]): Decoder[A, F] = d
