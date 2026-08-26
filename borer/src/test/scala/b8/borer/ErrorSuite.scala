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

package b8.borer

import b8.DecodeError
import b8.Format
import b8.array.*
import b8.laws.Fixtures
import b8.laws.Nested

import java.nio.charset.StandardCharsets.UTF_8

import io.bullet.borer.Borer
import io.bullet.borer.Cbor
import io.bullet.borer.derivation.MapBasedCodecs.deriveCodec

/** What a caller gets to see when the input is not what it should be.
  *
  * b8 raises exactly one error type, so everything borer knows about a failure
  * has to survive the trip into it: which format rejected the input, what the
  * backend said, and where in the input it happened. Both formats are asked the
  * same questions here, because one borer codec serves both and only the
  * answers differ.
  */
class ErrorSuite extends munit.FunSuite:

  import Codecs.given

  private def decodeCbor(bytes: Array[Byte]): Either[DecodeError, Row] =
    bytes.decodeAs[Row, Format.Cbor]

  private def decodeJson(json: String): Either[DecodeError, Row] =
    json.getBytes(UTF_8).decodeAs[Row, Format.Json]

  private def rejected(result: Either[DecodeError, Row]): DecodeError =
    result match
      case Left(e)  => e
      case Right(a) => fail(s"expected a rejection, got $a")

  test("malformed CBOR keeps borer's own failure as cause") {
    // A break stop code closes an indefinite-length item, and nothing is open
    // at the outermost level.
    val e = rejected(decodeCbor(Array[Byte](0xff.toByte, 0x01)))
    assertEquals(e.format, "Cbor")
    assert(e.getCause.isInstanceOf[Borer.Error[?]], e.getCause)
    // A decode failure is data, not a crash: no stack trace was filled in.
    assert(e.getStackTrace.isEmpty)
    assert(clue(e.message).contains("input position"))
  }

  test("a CBOR value cut short is rejected") {
    val nested = Fixtures.nested1.encode[Format.Cbor]
    assert(nested.take(20).decodeAs[Nested, Format.Cbor].isLeft)
  }

  test("CBOR of the wrong shape says what was expected instead") {
    val e = rejected(decodeCbor(Cbor.encode(42).toByteArray))
    // The integer is well-formed CBOR, so the complaint comes from the codec
    // rather than the parser, and it names both sides of the mismatch.
    assert(clue(e.message).contains("Expected Map Start or Map Header"))
    assert(clue(e.message).contains("Row"))
  }

  test("empty CBOR input is not an empty value") {
    assert(decodeCbor(Array.emptyByteArray).isLeft)
  }

  test("malformed JSON keeps borer's own failure as cause") {
    val e = rejected(decodeJson("""{"id":1,"label":"""))
    assertEquals(e.format, "Json")
    assert(e.getCause.isInstanceOf[Borer.Error[?]], e.getCause)
  }

  test("bytes after the JSON value are malformed input") {
    assert(decodeJson("""{"id":1,"label":"ok"}}""").isLeft)
    assert(decodeJson("""{"id":1,"label":"ok"} x""").isLeft)
  }

  test("whitespace after the JSON value is not") {
    assertEquals(
      decodeJson("""{"id":1,"label":"ok"}   """),
      Right(Row(1, "ok"))
    )
  }

  test("a JSON value of the wrong type says what was expected instead") {
    val e = rejected(decodeJson("""{"id":1,"label":7}"""))
    assert(clue(e.message).contains("Expected String"))
  }

  test("a missing JSON field names the key that is missing") {
    val e = rejected(decodeJson("""{"id":1}"""))
    assert(clue(e.message).contains("missing map key"))
    assert(clue(e.message).contains("label"))
  }

  test("the input position is reported once, not twice") {
    // borer's messages already end in `(input position N)`, which is why the
    // bridge passes `getMessage` through verbatim. A well-meaning
    // `s"${e.getMessage} at position …"` in the bridge would show up here as a
    // second occurrence.
    val messages = List(
      rejected(decodeCbor(Array[Byte](0xff.toByte, 0x01))).message,
      rejected(decodeJson("""{"id":1,"label":""")).message
    )
    messages.foreach(m => assertEquals(clue(positionCount(m)), 1))
  }

  test("borer's JSON parser accepts trailing NUL and 0xFF, CBOR does not") {
    // Pinned as it is, not as it should be. borer's JSON parser reads every
    // byte up to 0x20 as whitespace, so a trailing NUL is skipped, and 0xFF is
    // the marker it uses for its own end of input, so that one is skipped too.
    // Only a visible byte such as `}` or `x` is rejected — which is why the
    // JSON law sets pass `}` as their trailing input and the CBOR ones do not
    // have to.
    val ok = Right(Row(1, "ok"))
    val asJson = """{"id":1,"label":"ok"}""".getBytes(UTF_8)
    assertEquals((asJson :+ 0x00.toByte).decodeAs[Row, Format.Json], ok)
    assertEquals((asJson :+ 0xff.toByte).decodeAs[Row, Format.Json], ok)

    val asCbor = Row(1, "ok").encode[Format.Cbor]
    assert((asCbor :+ 0x00.toByte).decodeAs[Row, Format.Cbor].isLeft)
    assert((asCbor :+ 0xff.toByte).decodeAs[Row, Format.Cbor].isLeft)
  }

  test("an exception from a decoder arrives as a DecodeError, not as itself") {
    // Pinned because it is the opposite of what a reader would expect from
    // "only Borer.Error is wrapped", and because b8 cannot change it. borer's
    // decoding DSL catches every non-fatal exception itself and re-throws it
    // as a `Borer.Error.General`, so by the time the bridge's catch runs the
    // exception is already a borer error. What b8 can promise is that the
    // original survives: it sits one link further down the cause chain.
    val e = summon[b8.Decoder[Boom, Format.Cbor]]
      .decode(b8.ByteSource(Array[Byte](1))) match
      case Left(err) => err
      case Right(a)  => fail(s"expected a rejection, got $a")

    assertEquals(e.format, "Cbor")
    assert(e.getCause.isInstanceOf[Borer.Error[?]], e.getCause)
    assert(
      e.getCause.getCause.isInstanceOf[IllegalStateException],
      e.getCause.getCause
    )
    assert(clue(e.message).contains("boom from a decoder"))
  }

  private def positionCount(message: String): Int =
    val marker = "input position"
    message.sliding(marker.length).count(_ == marker)

/** Two fields with names worth reading back out of an error message. */
final case class Row(id: Long, label: String)

object Row:
  given io.bullet.borer.Codec[Row] = deriveCodec

/** A type whose decoder is a bug: it raises on any input at all. */
final case class Boom(n: Int)

object Boom:
  given io.bullet.borer.Decoder[Boom] =
    io.bullet.borer.Decoder { (_: io.bullet.borer.Reader) =>
      throw new IllegalStateException("boom from a decoder")
    }
