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

import b8.Codec
import b8.Decoder
import b8.Encoder
import b8.Format.Proto

import scalapb.GeneratedMessage
import scalapb.GeneratedMessageCompanion

/** Builds the b8 Protobuf encoder for a ScalaPB message type.
  *
  * Needs no `GeneratedMessageCompanion`: writing a message asks only the value
  * itself, and every `GeneratedMessage` carries both `serializedSize` and
  * `writeTo`.
  *
  * @param deterministic
  *   turns on protobuf's deterministic serialization for the write, and it is
  *   worth being exact about what that buys behind ScalaPB, because today the
  *   answer is nothing. The flag lives on the `CodedOutputStream` and is read
  *   by protobuf-java's own map writer; ScalaPB-generated code does not go
  *   through it. Its `writeTo` walks the Scala `Map` a map field is and never
  *   asks the stream whether it was meant to be deterministic — checked against
  *   the 0.11.20 generator, which mentions the flag nowhere. So two messages
  *   that are `==` but whose maps were built in a different insertion order can
  *   still encode to different bytes with this on. What holds either way is the
  *   weaker property most callers actually want: one value encodes to the same
  *   bytes every time, because one `Map` iterates the same way twice. The
  *   parameter is here because it is the switch protobuf offers and because a
  *   ScalaPB that started honouring it would need no change on this side — read
  *   it as an intent, not as a guarantee, and do not hash or sign the bytes of
  *   a message with a map field on the strength of it.
  */
def encoder[A <: GeneratedMessage](
    deterministic: Boolean = false
): Encoder[A, Proto] =
  ScalapbEncoder(deterministic)

/** Builds the b8 Protobuf decoder for a ScalaPB message type.
  *
  * @param recursionLimit
  *   protobuf's bound on how deeply messages may nest, set on the
  *   `CodedInputStream` before parsing. The same caveat as `deterministic`
  *   applies and it is sharper here, so plainly: **ScalaPB does not enforce
  *   this.** A nested message field is read through
  *   `scalapb.LiteParser.readMessage`, which pushes a length limit and recurses
  *   without touching protobuf's recursion counter, so nothing ever compares a
  *   depth against this number. Input nested past what the JVM stack holds does
  *   not come back as a `DecodeError`; it comes back as a `StackOverflowError`,
  *   which `decode` does not catch and no `Try` would either. Against untrusted
  *   input, bound the *length* instead: every level of nesting costs at least
  *   two bytes on the wire, so a size cap is a depth cap. The parameter is set
  *   for the same reason as the other one — it is protobuf's own knob, and it
  *   costs one call.
  */
def decoder[A <: GeneratedMessage](
    recursionLimit: Int = 100
)(using GeneratedMessageCompanion[A]): Decoder[A, Proto] =
  ScalapbDecoder(recursionLimit)

/** Builds both directions at once. See `encoder` and `decoder` for the two
  * parameters.
  */
def codec[A <: GeneratedMessage](
    deterministic: Boolean = false,
    recursionLimit: Int = 100
)(using GeneratedMessageCompanion[A]): Codec[A, Proto] =
  ScalapbCodec(deterministic, recursionLimit)

/** Every ScalaPB message type gets the Protobuf bridge, with protobuf's own
  * settings.
  *
  * One given, as in the jsoniter bridge and for the same reason: a generated
  * message and its companion arrive together, so ScalaPB has no write-only and
  * no read-only type and there is nothing for a `NotGiven` to keep apart. The
  * bound is `A <: GeneratedMessage` with a `GeneratedMessageCompanion[A]` in
  * scope, which every message ScalaPB generates satisfies through the
  * `implicit def` in its own companion object — so nothing has to be imported
  * per type. It answers for `Encoder[A, Proto]` and `Decoder[A, Proto]` as
  * well, since `Codec` extends both.
  *
  * Nothing else in b8 claims `Format.Proto`, so this import sits next to a JSON
  * or CBOR bridge without either shadowing the other.
  */
given [A <: GeneratedMessage](using
    GeneratedMessageCompanion[A]
): Codec[A, Proto] =
  codec()
