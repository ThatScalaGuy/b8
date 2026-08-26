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

/** Marker for a serialization format.
  *
  * A `Format` is never instantiated; it only exists as a phantom type parameter
  * that keeps `Encoder[A, Format.Json]` and `Encoder[A, Format.Cbor]` apart.
  * Not sealed on purpose: downstream code is free to add its own tags.
  */
trait Format

object Format:

  /** Formats whose output is text, and which may therefore be framed by a
    * delimiter such as a newline.
    */
  trait Text extends Format

  /** JSON, as produced by the jsoniter-scala, circe and borer backends. */
  trait Json extends Text

  /** Concise Binary Object Representation (RFC 8949). */
  trait Cbor extends Format

  /** Protocol Buffers wire format. */
  trait Proto extends Format
