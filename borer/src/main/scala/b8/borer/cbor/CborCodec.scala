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

import b8.ByteSink
import b8.ByteSource
import b8.Codec
import b8.Format

import io.bullet.borer.Cbor

/** Both directions of the borer CBOR bridge for one type.
  *
  * Implements `Codec` itself instead of pairing a `CborEncoder` with a
  * `CborDecoder` through `Codec.from`, which would put a forwarding call in
  * front of every encode and every decode for no gain. The bodies are the same
  * ones the two one-way classes run.
  *
  * @param encoding
  *   borer's encoding config; `bufferSize` doubles as b8's `sizeHint`
  * @param decoding
  *   borer's decoding config: the nesting, length and number limits
  */
final class CborCodec[A](
    encoding: Cbor.EncodingConfig,
    decoding: Cbor.DecodingConfig
)(using
    enc: io.bullet.borer.Encoder[A],
    dec: io.bullet.borer.Decoder[A]
) extends Codec[A, Format.Cbor]:

  override def sizeHint(a: A): Int = encoding.bufferSize

  def encodeTo(a: A, out: ByteSink): Unit =
    CborEncoder.encodeTo(a, out, encoding, enc)

  def decodeUnsafe(in: ByteSource): A =
    CborDecoder.decodeUnsafe(in, decoding, dec)
