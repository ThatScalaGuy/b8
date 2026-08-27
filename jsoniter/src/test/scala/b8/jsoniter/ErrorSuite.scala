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

package b8.jsoniter

import b8.ByteSource
import b8.Codec
import b8.DecodeError
import b8.Format.Json
import b8.array.*
import b8.laws.Fixtures
import b8.laws.Flat

import java.nio.charset.StandardCharsets.UTF_8

import com.github.plokhotnyuk.jsoniter_scala.core.JsonReader
import com.github.plokhotnyuk.jsoniter_scala.core.JsonReaderException
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.core.JsonWriter

/** A type whose codec is a bug: reading anything at all raises. */
final case class Boom(n: Int)

object Boom:
  given JsonValueCodec[Boom] = new JsonValueCodec[Boom]:
    def nullValue: Boom = null
    def encodeValue(x: Boom, out: JsonWriter): Unit = out.writeVal(x.n)
    def decodeValue(in: JsonReader, default: Boom): Boom =
      throw new IllegalStateException("boom from a codec")

/** What a caller gets to see when the input is not what it should be.
  *
  * b8 raises exactly one error type, so everything jsoniter knows about a
  * failure has to survive the trip into it: which format rejected the input,
  * what the backend said, and where in the input it happened.
  *
  * Two things about the message are worth knowing before reading the
  * assertions. jsoniter always ends its parse errors with `, offset: 0x` and
  * eight hex digits, so the position is already in the text and the bridge does
  * not append it a second time. And `appendHexDumpToParseException` is on by
  * jsoniter's default, so the message does not stop there: it continues with
  * `, buf:` and a hex dump table of the bytes around the offset, complete with
  * an ASCII column. A `DecodeError` from this bridge is therefore multi-line,
  * which is generous in a log and surprising in an `assertEquals`.
  */
class ErrorSuite extends munit.FunSuite:

  import Codecs.given

  /** A complete, well-formed `Flat` to vary one piece of at a time. */
  private val valid =
    """{"id":1,"name":"ok","active":true,"score":1.5,"tags":["a"]}"""

  private def decode(json: String): Either[DecodeError, Flat] =
    json.getBytes(UTF_8).decodeAs[Flat, Json]

  private def rejected(result: Either[DecodeError, Flat]): DecodeError =
    result match
      case Left(e)  => e
      case Right(a) => fail(s"expected a rejection, got $a")

  test("malformed JSON keeps jsoniter's own failure as cause") {
    val e = rejected(decode("{"))
    assertEquals(e.format, "Json")
    assert(e.getCause.isInstanceOf[JsonReaderException], e.getCause)
    // A decode failure is data, not a crash: no stack trace was filled in, on
    // the `DecodeError` or on jsoniter's exception underneath it.
    assert(e.getStackTrace.isEmpty)
    assert(e.getCause.getStackTrace.isEmpty)
    assert(clue(e.message).contains("offset: 0x"))
  }

  test("the message carries jsoniter's hex dump") {
    // Pinned as a property of the default `ReaderConfig`, not as a wish. The
    // dump is what makes a one-line message into a paragraph, and a caller who
    // logs `e.message` should know that before a production incident tells
    // them.
    val e = rejected(decode("{"))
    assert(clue(e.message).contains("buf:"))
    assert(clue(e.message).linesIterator.size > 1)
  }

  test("empty input is not an empty value") {
    assert(decode("").isLeft)
  }

  test("bytes after the JSON value are malformed input") {
    val encoded = Fixtures.flat1.encode[Json]
    assert((encoded ++ "}".getBytes(UTF_8)).decodeAs[Flat, Json].isLeft)
    assert((encoded ++ " x".getBytes(UTF_8)).decodeAs[Flat, Json].isLeft)
  }

  test("whitespace after the JSON value is not") {
    val encoded = Fixtures.flat1.encode[Json]
    assertEquals(
      (encoded ++ "   ".getBytes(UTF_8)).decodeAs[Flat, Json],
      Right(Fixtures.flat1)
    )
  }

  test("a missing field names the field that is missing") {
    val e = rejected(decode("""{"name":"ok","active":true,"score":1.5}"""))
    assert(clue(e.message).contains("missing required field"))
    assert(clue(e.message).contains("id"))
    // `tags` is absent from the same input and is not complained about:
    // `transientEmpty` leaves an empty collection out on the way in, so the
    // decoder cannot require it on the way back.
    assert(!clue(e.message).contains("tags"))
  }

  test("a field of the wrong type is rejected where it stands") {
    val e = rejected(decode(valid.replace("\"id\":1", "\"id\":\"nope\"")))
    assertEquals(e.format, "Json")
    assert(clue(e.message).contains("offset: 0x"))
    // jsoniter reports a type mismatch by position rather than by name — the
    // parser knows it wanted a digit and got a quote, and the field it was
    // filling is not part of that sentence. The name is still readable: the
    // ASCII column of the hex dump shows the input around the offset, `"id"`
    // included.
    assert(clue(e.message).contains("id"))
  }

  test("an exception from a codec arrives as itself, not as a DecodeError") {
    // The one place this bridge diverges from the circe and borer ones, and it
    // is deliberate. jsoniter does not catch what a `JsonValueCodec` throws and
    // re-raise it as a parse error, so a `MatchError` or an
    // `IllegalStateException` from a hand-written codec is a bug in the codec
    // and comes out of `decode` saying so. Turning it into a `Left` would
    // report a broken program as a broken message, and the caller would go
    // looking at the wrong end of the wire.
    val e = intercept[IllegalStateException] {
      summon[Codec[Boom, Json]].decode(ByteSource("1".getBytes(UTF_8)))
    }
    assertEquals(e.getMessage, "boom from a codec")
  }
