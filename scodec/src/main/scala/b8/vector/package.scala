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

package b8.vector

import b8.*

import scodec.bits.ByteVector

extension [A](a: A)
  /** Encodes `a` in format `F` into an exact-size `ByteVector`. */
  def encode[F <: Format](using e: Encoder[A, F], pool: SinkPool): ByteVector =
    ByteVector.view(e.encode(a))

  /** Encodes `a` in format `F` straight into `out`, allocating nothing in
    * between.
    */
  def encodeTo[F <: Format](out: ByteSink)(using e: Encoder[A, F]): Unit =
    e.encodeTo(a, out)

extension (bytes: ByteVector)
  /** Decodes the whole vector as an `A` in format `F`. Zero-copy for a single
    * array-backed chunk.
    */
  def decodeAs[A, F <: Format](using d: Decoder[A, F]): Either[DecodeError, A] =
    d.decode(bytes.asByteSource)

  /** Decodes the whole vector as an `A` in format `F`, throwing `DecodeError`
    * on malformed input.
    */
  def decodeAsUnsafe[A, F <: Format](using d: Decoder[A, F]): A =
    d.decodeUnsafe(bytes.asByteSource)

  /** A `ByteSource` over this vector's bytes.
    *
    * Shares the underlying array when the vector is a single chunk backed by an
    * array or by a heap `ByteBuffer` — slices included, at the right offset.
    * Everything else is copied once per call: a concatenation, because a
    * decoder needs its input in one piece, and a chunk over a direct
    * `ByteBuffer`, because it exposes no array at all.
    *
    * If you decode the same vector repeatedly, materialize it first: `.compact`
    * for a concatenation, `.copy` for a direct buffer, which `.compact` leaves
    * alone since it is already one chunk.
    */
  def asByteSource: ByteSource =
    if bytes.isEmpty then ByteSource.empty
    else ByteSource(bytes.toByteBufferUnsafe)
