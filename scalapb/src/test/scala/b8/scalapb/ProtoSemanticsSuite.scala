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

import b8.ByteSource
import b8.Codec
import b8.DecodeError
import b8.Format.Proto
import b8.array.*
import b8.scalapb.ProtoFixtures.pNested1
import b8.scalapb.protos.PFlat
import b8.scalapb.protos.PKind
import b8.scalapb.protos.PNested
import b8.scalapb.protos.PRecursive
import b8.scalapb.protos.PShape

import com.google.protobuf.CodedOutputStream
import scalapb.UnknownFieldSet

/** Protobuf behaviours that read like bridge bugs and are not.
  *
  * Every test here has the same shape: the decoder accepts something a reviewer
  * would expect it to reject, or returns something a caller would expect it to
  * complain about, and in each case the right answer is that protobuf says so.
  * None of it is something `b8-scalapb` could change. The bridge hands protobuf
  * a window of bytes and hands back what ScalaPB made of it; to refuse any of
  * these it would have to parse the input a second time and apply rules the
  * wire format does not have. Writing the cases down once is cheaper than
  * explaining them once per bug report.
  *
  * The reason underneath most of them is that a proto3 message is not
  * self-describing. The wire carries field numbers, wire types and bytes. It
  * does not carry which fields the schema declares, which of them were set,
  * what an enum value means, or where the message ends. So a decoder cannot
  * tell "this field was absent" from "this field was at its default", cannot
  * tell "these trailing bytes are garbage" from "these trailing bytes are a
  * field added after I was compiled", and cannot reject an enum value it has
  * never heard of. That is the forward compatibility protobuf is built around,
  * and the price of it is a decoder that accepts strictly more than a
  * schema-aware reader would.
  *
  * Two of the cases — the oneof with nothing set and the unrecognised enum —
  * are the ones `ProtoFixtures`' generators structurally cannot draw, because
  * the `b8.laws` types they are derived from have no counterpart for either.
  * The laws will never see them. This suite does.
  *
  * The last two are here for a different reason: they are why `b8.scalapb`
  * offers nothing to configure. Protobuf has a switch for each of them —
  * `useDeterministicSerialization` and `setRecursionLimit` — and ScalaPB reads
  * neither, so the bridge exposes neither. If a ScalaPB release ever starts
  * honouring one, the matching test here fails and says so.
  */
class ProtoSemanticsSuite extends munit.FunSuite:

  /** Field number 7, wire type 0 (varint), value 1. `PNested` declares fields 1
    * to 6, so this is a field from a schema neither side of this suite has.
    */
  private val unusedField: Array[Byte] = Array[Byte](0x38, 0x01)

  /** Unwraps a decode that is meant to succeed, and reports what came back when
    * it did not. `.toOption.get` would only ever say `None`.
    */
  private def accepted[A](result: Either[DecodeError, A]): A =
    result match
      case Right(a) => a
      case Left(e)  => fail(s"expected a decode, got ${e.message}")

  test("a field the schema does not declare survives a round trip") {
    val bytes = pNested1.encode[Proto] ++ unusedField
    val decoded = accepted(bytes.decodeAs[PNested, Proto])

    // The reader could not interpret field 7, so it kept the bytes filed
    // under the number they arrived with rather than dropping them.
    // `UnknownFieldSet.fields` is `private[scalapb]` — the root `scalapb`
    // package, not this one — so the set is compared against the empty one
    // and read through `getField`, which are the public halves of it.
    assertNotEquals(decoded.unknownFields, UnknownFieldSet.empty)
    assertEquals(
      decoded.unknownFields.getField(7).map(_.varint.toList),
      Some(List(1L))
    )

    // Everything `PNested` does declare came back exactly as it went in.
    // Note what this is not: `assertEquals(decoded, pNested1)` fails here,
    // and that failure is the point rather than a flaw in the test.
    // `unknownFields` is a constructor parameter of the generated case class,
    // so two messages that agree on all six declared fields are still `!=`
    // when one of them carries something the other never saw.
    assertEquals(decoded.discardUnknownFields, pNested1)

    // And a service that decodes, touches nothing and re-encodes does not
    // lose the field on the way through. This is what makes it safe to leave
    // an old build in the middle of a pipeline whose ends already speak a
    // newer schema; silently dropping these bytes is the classic way to
    // corrupt data in one.
    val reEncoded = decoded.encode[Proto]
    assert(
      reEncoded.toSeq.containsSlice(unusedField.toSeq),
      clue(reEncoded.length)
    )
  }

  test("a trailing zero byte is rejected and a trailing field is not") {
    val encoded = pNested1.encode[Proto]

    // Half one, and the half `CodecLaws` pins. The `0x00` is read as a tag,
    // its field number is 0, and field number 0 is illegal in every protobuf
    // version, so `readTag` refuses it instead of taking it for the end of
    // the input. `trailingRejected` therefore passes for this bridge — but it
    // passes because of that one byte, not because the decoder has any idea
    // where the message ended.
    assert((encoded ++ Array[Byte](0)).decodeAs[PNested, Proto].isLeft)

    // Half two, and the reason the first half is not the general statement it
    // looks like. Anything that parses as a field is a field. Nothing on the
    // wire marks the end of a message, so a decoder handed more bytes than
    // the sender wrote cannot notice, and protobuf depends on that: it is how
    // a message gains a field without every reader being redeployed first.
    assert((encoded ++ unusedField).decodeAs[PNested, Proto].isRight)

    // Which is how far `Decoder`'s "a decoder consumes the whole source"
    // contract can be honoured here and no further. b8 hands
    // `CodedInputStream` the entire window and protobuf reads to the end of
    // it, but "the end" is wherever the bytes stop looking like fields. A
    // caller who needs a hard boundary has to frame the message themselves.
  }

  test("zero bytes decode to the default instance") {
    // A proto3 message with every field at its default writes nothing at all,
    // so the empty array is that message's own encoding. A decoder that
    // rejected empty input would be unable to read what this encoder writes,
    // which is why there is no "empty input" error to be had.
    assertEquals(PNested().encode[Proto].length, 0)
    assertEquals(
      Array.emptyByteArray.decodeAs[PNested, Proto],
      Right(PNested())
    )

    // The consequence for a caller: "no message" and "an empty message" are
    // the same zero bytes, so a protocol that has to tell them apart must say
    // so outside the message — a length prefix, an envelope, an `Option` at
    // the call site. b8 cannot recover a distinction the wire never carried.
  }

  test("the oneof with no case set round-trips through zero bytes") {
    val encoded = PShape().encode[Proto]
    // A oneof is written as whichever field is set, and none is, so the whole
    // message is nothing.
    assertEquals(encoded.length, 0)

    val decoded = accepted(encoded.decodeAs[PShape, Proto])
    assertEquals(decoded, PShape())
    assert(decoded.shape.isEmpty, clue(decoded.shape))

    // `ProtoFixtures` cannot produce this value: it maps `b8.laws.Shape`,
    // which is a sealed enum of two cases with no third for "unset", so every
    // generated `PShape` has a case set. The law suite therefore never
    // encodes an empty message, and an encoder that wrote a stray byte for
    // the empty oneof — or a decoder that failed on zero bytes — would pass
    // every property and break here.
  }

  test("an enum value from a newer schema is carried, not rejected") {
    val bytes = PNested(kind = PKind.Unrecognized(99)).encode[Proto]
    val decoded = accepted(bytes.decodeAs[PNested, Proto])

    // 99 is not `ALPHA`, `BETA` or `GAMMA`, and the decoder does not treat
    // that as malformed input. Proto3 enums are open: an unknown number is
    // kept as `Unrecognized` so a reader compiled against an older schema can
    // pass a value it does not understand along unchanged. Mapping it to the
    // zero value instead would be data loss that no test downstream could
    // detect.
    assertEquals(decoded.kind, PKind.Unrecognized(99))
    assert(decoded.kind.isUnrecognized, clue(decoded.kind))

    // Field 5 arrived with the wire type the schema expects, so this is a
    // known field with a value out of range, not an unknown field — nothing
    // ends up in `unknownFields`.
    assertEquals(decoded.unknownFields, UnknownFieldSet.empty)

    // The second value the fixture generators cannot reach: `b8.laws.Kind`
    // has exactly three cases and `toProto` is total over them, so no
    // property ever draws an unrecognised one.
  }

  test("a declared field with the wrong wire type is not an error") {
    // `id` is field 1 of `PFlat` and an int64, which is wire type 0. These
    // bytes send field 1 as wire type 1, a fixed64, followed by the eight
    // bytes that wire type promises. Nothing about the encoding is malformed.
    // It is only wrong against a schema, and the schema is not on the wire.
    val misTyped = Array[Byte](0x09, 1, 0, 0, 0, 0, 0, 0, 0)
    val decoded = accepted(misTyped.decodeAs[PFlat, Proto])

    // The generated parser matches on the whole tag, number and wire type
    // together, so tag 9 matches no case it has and falls through to the same
    // branch an entirely unknown field number would.
    assertEquals(decoded.id, 0L)
    assertEquals(
      decoded.unknownFields.getField(1).map(_.fixed64.toList),
      Some(List(1L))
    )

    // Nothing else was written, so re-encoding gives back the nine bytes that
    // came in, bit for bit. Rejecting this instead is not something the
    // bridge could do: protobuf validates the wire format and b8 never sees
    // the schema, so refusing it would mean walking the input a second time
    // with the descriptors in hand, on every message, to catch input that a
    // conforming sender never produces.
    assert(decoded.encode[Proto].sameElements(misTyped))
  }

  test("negative zero does not survive, and equality hides that it did not") {
    // proto3 writes a scalar only when it differs from the type's default,
    // and the generated `PFlat` decides that with `if (__v != 0.0)`. In
    // Scala `-0.0 != 0.0` is false, so the field is dropped and comes back
    // as `+0.0`. It is the one value in the law fixtures' double generator
    // that the wire format cannot carry, and `genDouble` draws it often.
    val negZero = PFlat(score = -0.0)
    assertEquals(negZero.encode[Proto].length, 0)

    val back = accepted(negZero.encode[Proto].decodeAs[PFlat, Proto])
    assertEquals(
      java.lang.Double.doubleToRawLongBits(negZero.score),
      Long.MinValue
    )
    assertEquals(java.lang.Double.doubleToRawLongBits(back.score), 0L)

    // And this is why no law reports it. A case class compares its `Double`
    // fields with primitive `==`, under which the two zeros are the same
    // value, so `CodecLaws.roundTrip` passes on an input the bridge did not
    // hand back. Worth knowing in both directions: the loss is protobuf's,
    // not the bridge's — ScalaPB's own `toByteArray` drops the sign in the
    // same place — and the law suite will not warn anyone who needs a signed
    // zero to reach the other end.
    assertEquals(back, negZero)
    assert(negZero.toByteArray.isEmpty)
  }

  test("map fields encode in the Scala Map's own iteration order") {
    // Four entries, because Scala keeps `Map1` to `Map4` in the order they
    // were built and only switches to a hash-ordered `HashMap` at five. That
    // switch is the point rather than a detail to work around: which of two
    // `==` maps encodes to which bytes is decided by the collection's internal
    // representation, and nothing about protobuf or this bridge promises
    // anything about it either way.
    val m1 = Map("a" -> "1", "b" -> "2", "c" -> "3", "d" -> "4")
    val m2 = Map("d" -> "4", "c" -> "3", "b" -> "2", "a" -> "1")
    assertEquals(m1, m2)
    assertNotEquals(m1.keys.toList, m2.keys.toList)

    val first = PNested(meta = m1).encode[Proto]
    val second = PNested(meta = m2).encode[Proto]
    assert(!first.sameElements(second), clue(first.length))

    // The tripwire. protobuf-java has a map writer that sorts entries by key
    // before writing them, reached by
    // `CodedOutputStream.useDeterministicSerialization`, and ScalaPB's
    // generated `writeTo` is not it: a map field is `meta.foreach { ... }`
    // over the Scala `Map`, and the flag is never consulted. Turning it on by
    // hand here is the strongest form of the `deterministic` parameter
    // `b8.scalapb.codec` could have offered, and it changes nothing — which is
    // why there is no such parameter. If this assertion ever fails, ScalaPB
    // has started honouring the flag and the parameter becomes worth having.
    assert(
      !deterministically(m1).sameElements(deterministically(m2)),
      "ScalaPB now honours deterministic serialization: b8.scalapb.codec could offer it"
    )

    // What does hold, and what most callers reaching for that flag actually
    // want: one value encodes to the same bytes every time, because one `Map`
    // iterates the same way twice. `CodecLaws.deterministic` covers it for the
    // fixtures; this pins the reason it is not the stronger property.
    assert(first.sameElements(PNested(meta = m1).encode[Proto]))
  }

  /** `PNested(meta = m)` written through protobuf's own encoder with
    * deterministic serialization turned on.
    *
    * Deliberately not routed through the bridge: the bridge has no way to set
    * this flag, and the point of the assertion above is that setting it makes
    * no difference to what ScalaPB writes.
    */
  private def deterministically(m: Map[String, String]): Array[Byte] =
    val message = PNested(meta = m)
    val out = new Array[Byte](message.serializedSize)
    val cos = CodedOutputStream.newInstance(out)
    cos.useDeterministicSerialization()
    message.writeTo(cos)
    cos.checkNoSpaceLeft()
    out

  test("nesting is bounded by the stack, not by protobuf's recursion limit") {
    // Built as a fold, so making the value is iterative and cannot overflow
    // the stack the way parsing it can.
    def chain(depth: Int): PRecursive =
      (1 to depth).foldLeft(PRecursive(label = "leaf"))((inner, _) =>
        PRecursive(child = Some(inner))
      )

    val codec = summon[Codec[PRecursive, Proto]]
    val deep = chain(200)
    // Twice protobuf's own default of 100, and it comes back whole. ScalaPB
    // reads a nested message through `scalapb.LiteParser.readMessage`, which
    // pushes a length limit and recurses without touching protobuf's recursion
    // counter, so `CodedInputStream.setRecursionLimit` would have changed
    // nothing here. That is why `b8.scalapb.codec` takes no `recursionLimit`.
    assertEquals(codec.decode(ByteSource(codec.encode(deep))), Right(deep))

    // Equality alone would not catch a degenerate helper: a `chain` that
    // ignored `depth` would round-trip just as happily.
    assert(
      deep.serializedSize > chain(1).serializedSize,
      clue(deep.serializedSize)
    )

    // There is deliberately no test for the failure. Past a few thousand
    // levels the parse raises a `StackOverflowError`, which `decode` does not
    // catch and no `Try` would either; a suite that provoked it would leave
    // the thread in a state nothing should be asserting about, at a depth that
    // is a property of the running JVM rather than of this bridge. The
    // mitigation is a cap on input length, not on depth.
  }
