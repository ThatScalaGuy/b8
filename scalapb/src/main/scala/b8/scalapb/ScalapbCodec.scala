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

import b8.ByteSink
import b8.ByteSource
import b8.Codec
import b8.Format.Proto

import scalapb.GeneratedMessage
import scalapb.GeneratedMessageCompanion

/** Both directions of the ScalaPB bridge for one type.
  *
  * Implements `Codec` itself instead of pairing a `ScalapbEncoder` with a
  * `ScalapbDecoder` through `Codec.from`, which would put a forwarding call in
  * front of every encode and every decode for no gain. The bodies are the same
  * ones the two one-way classes run.
  *
  * @param deterministic
  *   sets protobuf's deterministic-serialization flag on the stream
  * @param recursionLimit
  *   protobuf's nesting bound, set on the stream before parsing
  */
final class ScalapbCodec[A <: GeneratedMessage](
    deterministic: Boolean,
    recursionLimit: Int
)(using cmp: GeneratedMessageCompanion[A])
    extends Codec[A, Proto]:

  /** Exact, not a guess; see `ScalapbEncoder`. */
  override def sizeHint(a: A): Int = a.serializedSize

  def encodeTo(a: A, out: ByteSink): Unit =
    ScalapbEncoder.encodeTo(a, out, deterministic)

  def decodeUnsafe(in: ByteSource): A =
    ScalapbDecoder.decodeUnsafe(in, recursionLimit, cmp)
