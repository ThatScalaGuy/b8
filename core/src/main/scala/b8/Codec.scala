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

/** Both directions of format `F` for the same type `A`. */
trait Codec[A, F <: Format] extends Encoder[A, F], Decoder[A, F]

object Codec:
  /** Summons the codec of `A` for format `F`. */
  def apply[A, F <: Format](using c: Codec[A, F]): Codec[A, F] = c

  /** Combines an encoder and a decoder for the same type and format. */
  def from[A, F <: Format](e: Encoder[A, F], d: Decoder[A, F]): Codec[A, F] =
    new Codec[A, F]:
      def encodeTo(a: A, out: ByteSink): Unit = e.encodeTo(a, out)
      override def sizeHint(a: A): Int = e.sizeHint(a)
      def decodeUnsafe(in: ByteSource): A = d.decodeUnsafe(in)
