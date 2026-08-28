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

import b8.Format.Proto
import b8.laws.CodecLaws
import b8.laws.LawsSuite
import b8.scalapb.protos.PFlat
import b8.scalapb.protos.PNested
import b8.scalapb.protos.PShape

/** The shared law set, run against the bridge for every message fixture.
  *
  * Three sets and not four. `PKind` is absent because a protobuf enum is not a
  * message: it has no `GeneratedMessageCompanion`, no `writeTo` and no
  * `serializedSize`, and on the wire it has no framing of its own — an enum
  * value is a bare varint that only means anything because a tag in some
  * enclosing message said which field it belongs to. There is therefore no
  * `Codec[PKind, Proto]` to put under law, and there must not be one: an enum
  * cannot be a top-level payload, so a bridge that offered a codec for it would
  * be promising something protobuf does not define. The Scala `Kind` that every
  * other bridge tests directly is still covered here, in the only place
  * protobuf lets it appear — the `kind` field of `PNested`.
  *
  * The trailing input is the law set's own default, a single NUL byte, and it
  * is the right choice here for a reason worth spelling out. A protobuf tag is
  * a varint whose low three bits are the wire type and whose remaining bits are
  * the field number, so `0x00` reads as field number 0 with wire type 0. Field
  * number 0 is forbidden by the spec, and `CodedInputStream.readTag` rejects it
  * outright rather than treating it as an unknown field. That is what turns one
  * extra byte after a complete message into a decode failure.
  *
  * Unlike the JSON and CBOR bridges there is no second, harder trailing byte
  * worth adding, and the plain reason is that protobuf cannot detect trailing
  * bytes in general. Bytes appended after a message that happen to form a
  * well-formed field are not garbage to a protobuf reader — they are a field
  * from a schema this reader has not been told about, so they are parsed, kept
  * in `unknownFields`, and written back out on the next encode. That is
  * protobuf's forward-compatibility guarantee working as designed, not a hole
  * in the bridge, and nothing `ScalapbDecoder` could do would close it without
  * breaking the guarantee. `trailingRejected` therefore pins down the one case
  * protobuf really does reject, and `ProtoSemanticsSuite` covers the case it
  * deliberately does not.
  *
  * The generators come from `ProtoFixtures`, which is the shared law fixtures
  * mapped through total conversions, so the distribution of values measured
  * here is the same one every other bridge's law run is measured on.
  */
class ScalapbLawsSuite extends LawsSuite:

  import ProtoFixtures.given

  checkAll(CodecLaws[PFlat, Proto]("scalapb.PFlat"))
  checkAll(CodecLaws[PShape, Proto]("scalapb.PShape"))
  checkAll(CodecLaws[PNested, Proto]("scalapb.PNested"))
