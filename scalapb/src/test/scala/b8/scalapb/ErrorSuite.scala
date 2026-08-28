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
import b8.DecodeError
import b8.Decoder
import b8.Format.Proto
import b8.array.*
import b8.scalapb.protos.PFlat
import b8.scalapb.protos.PNested

import java.io.IOException

import com.google.protobuf.InvalidProtocolBufferException

/** What a caller gets to see when the input is not what it should be.
  *
  * The policy fits in a sentence: exactly one exception type is wrapped,
  * `InvalidProtocolBufferException`, only on the decode side, and everything
  * else propagates as itself. There is no encode half to this suite because
  * there is nothing to write about — a `GeneratedMessage` is a valid message by
  * construction, and generated code cannot make `serializedSize` disagree with
  * `writeTo` — so encoding is total and the bridge never raises a `DecodeError`
  * on the way out. What the error carries is the format name, protobuf's own
  * words, and protobuf's own exception as the cause. What it does not carry is
  * an offset: protobuf's messages name the fault and never the position, and b8
  * does not invent a number it would then have to keep true.
  *
  * One rejection worth naming up front, because it is invisible in the schema:
  * malformed UTF-8 in a `string` field is an error here. ScalaPB generates
  * `readStringRequireUtf8` rather than `readString`, so bytes that decode to
  * nothing valid raise instead of being repaired into U+FFFD. That is proto3's
  * documented rule, and it is pinned below because the opposite behaviour — a
  * replacement character quietly appearing in a field a caller is about to
  * store — is the kind of thing one finds out about much later.
  */
class ErrorSuite extends munit.FunSuite:

  /** A whole, well-formed message to damage in one way at a time. */
  private val encoded: Array[Byte] = ProtoFixtures.pNested1.encode[Proto]

  /** Tag zero: field number zero, which no message can declare. The shortest
    * input protobuf rejects, and the one this suite asks most of.
    */
  private val invalidTag: Array[Byte] = Array[Byte](0)

  private def rejected[A](result: Either[DecodeError, A]): DecodeError =
    result match
      case Left(e)  => e
      case Right(a) => fail(s"expected a rejection, got $a")

  test("an invalid tag keeps protobuf's own failure as cause") {
    val e = rejected(invalidTag.decodeAs[PNested, Proto])
    assertEquals(e.format, "Proto")
    assert(e.message != null)
    assert(e.message.nonEmpty)
    assert(
      e.getCause.isInstanceOf[InvalidProtocolBufferException],
      e.getCause
    )
    // A rejected message is data, not a crash, so `DecodeError` never fills a
    // trace in — it is built with `writableStackTrace` off. A caller who logs
    // one gets the format, the message and the cause, and pays nothing for
    // the frames of a parser they cannot fix anyway.
    assert(e.getStackTrace.isEmpty)
    // The measured message is "Protocol message contained an invalid tag
    // (zero)." Only the words that name the fault are pinned; protobuf has
    // reworded these between releases and the bridge passes through whatever
    // it says.
    assert(clue(e.message).contains("invalid tag"))
  }

  test("a message cut short is rejected") {
    // The last field of `pNested1` is `shape`, a length-delimited submessage,
    // so five bytes fewer than its length promises is a field that never
    // ends. Five is arbitrary: every truncation from one byte to twenty was
    // measured and all of them fail the same way.
    val e = rejected(encoded.dropRight(5).decodeAs[PNested, Proto])
    assertEquals(e.format, "Proto")
    assert(
      e.getCause.isInstanceOf[InvalidProtocolBufferException],
      e.getCause
    )
    assert(clue(e.message).contains("ended unexpectedly"))
  }

  test("a wire type protobuf has no rule for is rejected") {
    // 0x0e is field number 1, wire type 6. Wire types 6 and 7 were never
    // given a meaning, so a parser cannot even tell how many bytes the field
    // occupies and skipping it is not an option either.
    val e = rejected(Array[Byte](0x0e, 0x01).decodeAs[PNested, Proto])
    assertEquals(e.format, "Proto")
    assertEquals(e.message, "Protocol message tag had invalid wire type: 6")
    // The wire type is what makes the tag unrecognisable, so the generated
    // parser does not match it against field 1 and hands it to the
    // unknown-field branch. The exception is therefore constructed by
    // ScalaPB's own `UnknownFieldSet.Field.Builder`, not by protobuf-java,
    // whose wording for the same fault stops at "wire type." and leaves the
    // number out. The bridge does not care which of the two threw it: both
    // throw `InvalidProtocolBufferException`, and one catch covers the pair.
    assert(
      e.getCause.isInstanceOf[InvalidProtocolBufferException],
      e.getCause
    )
  }

  test("decodeUnsafe throws the error that decode returns") {
    // `Decoder.decode` is final and does nothing but run `decodeUnsafe` in a
    // `try`, so the two cannot disagree about what is wrong with an input.
    // The unsafe door is there for callers who already have an exception
    // boundary, not for callers who want a different verdict.
    val thrown = intercept[DecodeError] {
      Decoder[PNested, Proto].decodeUnsafe(ByteSource(invalidTag))
    }
    assertEquals(thrown.format, "Proto")
    assertEquals(
      thrown.message,
      rejected(invalidTag.decodeAs[PNested, Proto]).message
    )
  }

  test("the cause chain stops at protobuf's exception") {
    val e = rejected(invalidTag.decodeAs[PNested, Proto])
    // Two links and no more. Protobuf builds these from a message alone, so
    // there is no third exception underneath that the bridge could be
    // accused of hiding, and `getCause` is the whole of what b8 wrapped.
    assert(e.getCause.getCause == null, e.getCause.getCause)
    // What the one catch does not cover is worth stating as a type:
    // `InvalidProtocolBufferException` is an `IOException`, and the bridge
    // names the subtype. Catching the supertype instead would report a
    // failing stream as malformed data, which is a different bug in a
    // different place.
    assert(e.getCause.isInstanceOf[IOException], e.getCause)
  }

  test("malformed UTF-8 in a string field is a decode error") {
    // A `PFlat` written by hand: tag 0x12 is field 2, `name`, wire type 2,
    // then a length of three and three bytes. 0xff begins no UTF-8 sequence,
    // so the field is not a string at all.
    val bytes = Array[Byte](0x12, 0x03, 0xff.toByte, 0x61, 0x62)
    val e = rejected(bytes.decodeAs[PFlat, Proto])
    assertEquals(e.format, "Proto")
    assertEquals(e.message, "Protocol message had invalid UTF-8.")
    // `readString`, the other method protobuf offers, would have substituted
    // U+FFFD and returned a `PFlat` — the generator's choice of
    // `readStringRequireUtf8` is the only reason this is a `Left`. A caller
    // reading proto written by a different runtime therefore does not have to
    // validate strings again on this side.
    assert(
      e.getCause.isInstanceOf[InvalidProtocolBufferException],
      e.getCause
    )
  }
