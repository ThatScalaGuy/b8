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
  */
def encoder[A <: GeneratedMessage]: Encoder[A, Proto] =
  ScalapbEncoder()

/** Builds the b8 Protobuf decoder for a ScalaPB message type. */
def decoder[A <: GeneratedMessage](using
    GeneratedMessageCompanion[A]
): Decoder[A, Proto] =
  ScalapbDecoder()

/** Builds both directions at once.
  *
  * There is nothing to configure here, which is worth an explanation rather
  * than a shrug: protobuf offers exactly two knobs a reader will come looking
  * for, and neither of them does anything behind ScalaPB.
  *
  * `CodedOutputStream.useDeterministicSerialization` is protobuf's switch for
  * writing map entries in a stable order, and protobuf-java's own map writer
  * reads it. ScalaPB-generated code never goes through that writer: a
  * `map<string, string>` field is a Scala `Map`, and the generated `writeTo`
  * iterates it directly and writes the entries in whatever order it gets them.
  * Setting the flag changes no byte of the output, so exposing it would have
  * been offering a guarantee the backend does not make. What a map field's
  * order on the wire actually follows is the Scala `Map`'s iteration order,
  * which is worse to depend on than it sounds: two messages that are `==` but
  * whose maps were built in different insertion orders encode differently at
  * four entries or fewer, where `Map1` to `Map4` keep insertion order, and
  * identically from five on, where Scala switches to a hash-ordered `HashMap`.
  * If the bytes have to be stable, compare or hash the parsed values, or carry
  * the entries in a `repeated` field sorted by the sender.
  *
  * `CodedInputStream.setRecursionLimit` is protobuf's bound on nesting depth,
  * and ScalaPB does not enforce that either: a nested message field is read
  * through `scalapb.LiteParser.readMessage`, which pushes a length limit and
  * then recurses without ever touching protobuf's recursion counter, so nothing
  * compares a depth against the limit. Input nested past what the JVM stack
  * holds raises a `StackOverflowError` — an `Error`, so neither `decode` nor a
  * `Try` turns it into a value — and no limit set here would have stopped it.
  * Bound the *length* of untrusted input instead: every level of nesting costs
  * at least two bytes on the wire, a tag and a length, so a byte cap is a depth
  * cap.
  *
  * Both facts are pinned by `ProtoSemanticsSuite`, so a ScalaPB release that
  * starts honouring either one breaks a test and brings someone back to this
  * paragraph.
  */
def codec[A <: GeneratedMessage](using
    GeneratedMessageCompanion[A]
): Codec[A, Proto] =
  ScalapbCodec()

/** Every ScalaPB message type gets the Protobuf bridge.
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
): Codec[A, Proto] = codec
