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

package b8.array

import b8.*

extension [A](a: A)
  /** Encodes `a` in format `F` into a fresh, exact-size array. */
  def encode[F <: Format](using e: Encoder[A, F], pool: SinkPool): Array[Byte] =
    e.encode(a)

  /** Encodes `a` in format `F` straight into `out`, allocating nothing in
    * between.
    */
  def encodeTo[F <: Format](out: ByteSink)(using e: Encoder[A, F]): Unit =
    e.encodeTo(a, out)

extension (bytes: Array[Byte])
  /** Decodes the whole array as an `A` in format `F`. */
  def decodeAs[A, F <: Format](using d: Decoder[A, F]): Either[DecodeError, A] =
    d.decode(ByteSource(bytes))

  /** Decodes the whole array as an `A` in format `F`, throwing `DecodeError` on
    * malformed input.
    */
  def decodeAsUnsafe[A, F <: Format](using d: Decoder[A, F]): A =
    d.decodeUnsafe(ByteSource(bytes))
