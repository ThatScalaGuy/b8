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

package b8.chunk

import b8.*

import fs2.Chunk

extension [A](a: A)
  /** Encodes `a` in format `F` into an exact-size `Chunk[Byte]`. */
  def encode[F <: Format](using e: Encoder[A, F], pool: SinkPool): Chunk[Byte] =
    Chunk.array(e.encode(a))

  /** Encodes `a` in format `F` straight into `out`, allocating nothing in
    * between.
    */
  def encodeTo[F <: Format](out: ByteSink)(using e: Encoder[A, F]): Unit =
    e.encodeTo(a, out)

extension (bytes: Chunk[Byte])
  /** Decodes the whole chunk as an `A` in format `F`. Zero-copy for an
    * array-backed chunk.
    */
  def decodeAs[A, F <: Format](using d: Decoder[A, F]): Either[DecodeError, A] =
    d.decode(bytes.asByteSource)

  /** Decodes the whole chunk as an `A` in format `F`, throwing `DecodeError` on
    * malformed input.
    */
  def decodeAsUnsafe[A, F <: Format](using d: Decoder[A, F]): A =
    d.decodeUnsafe(bytes.asByteSource)

  /** A `ByteSource` over this chunk's bytes.
    *
    * Shares the underlying array when the chunk is array-backed and holds two
    * or more bytes — `Chunk.array` and every `drop`, `take` or `splitAt` of one
    * stay views on that array, at the right offset — and when it is a view over
    * a heap `ByteBuffer`, whose array offset and position are honoured.
    * Everything else is copied once per call: a concatenation, because a
    * decoder needs its input in one piece, and a chunk over a direct
    * `ByteBuffer`, because it exposes no array at all.
    *
    * The two-byte floor is fs2's, not b8's: `Chunk.array` answers a one-element
    * array with a `Chunk.Singleton` and an empty one with `Chunk.empty`, and
    * neither keeps the array it was handed. A one-byte chunk is therefore
    * copied, which costs one byte.
    *
    * If you decode the same chunk repeatedly, materialize it first with
    * `.compact`, which resolves a concatenation and a direct buffer alike into
    * a single array-backed chunk.
    */
  def asByteSource: ByteSource =
    if bytes.isEmpty then ByteSource.empty
    else
      // The type argument is not decoration. `toArraySlice[O2 >: O]` picks its
      // `ClassTag` from `O2`, and an `O2` inferred as anything wider than
      // `Byte` makes the array-backed fast path miss and hands back a boxed
      // `Array[Object]` copy instead — with no warning and no compile error.
      val slice = bytes.toArraySlice[Byte]
      ByteSource(slice.values, slice.offset, slice.length)
