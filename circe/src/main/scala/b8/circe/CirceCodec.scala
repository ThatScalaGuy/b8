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

import b8.ByteSink
import b8.ByteSource
import b8.Codec
import b8.Format.Json

import io.circe.Printer
import io.circe.jawn.JawnParser

/** Both directions of the circe bridge for one type.
  *
  * Implements `Codec` itself instead of pairing a `CirceEncoder` with a
  * `CirceDecoder` through `Codec.from`, which would put a forwarding call in
  * front of every encode and every decode for no gain. The bodies are the same
  * ones the two one-way classes run.
  *
  * @param printer
  *   decides spacing, key order and number rendering
  * @param parser
  *   jawn parser deciding the value-size limit and duplicate-key handling
  */
final class CirceCodec[A](printer: Printer, parser: JawnParser)(using
    enc: io.circe.Encoder[A],
    dec: io.circe.Decoder[A]
) extends Codec[A, Json]:

  // No `sizeHint` override, for the reason spelled out in `CirceEncoder`.

  def encodeTo(a: A, out: ByteSink): Unit =
    CirceEncoder.encodeTo(a, out, printer, enc)

  def decodeUnsafe(in: ByteSource): A =
    CirceDecoder.decodeUnsafe(in, parser, dec)
