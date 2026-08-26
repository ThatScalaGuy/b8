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

import b8.DecodeError
import b8.Format.Json
import b8.array.*

import java.nio.charset.StandardCharsets.UTF_8

import io.circe.DecodingFailure
import io.circe.ParsingFailure

/** Two fields with names worth reading back out of an error message. */
final case class Row(id: Long, label: String) derives io.circe.Codec.AsObject

/** What a caller gets to see when the input is not what it should be.
  *
  * b8 raises exactly one error type, so everything circe knows about a failure
  * has to survive the trip into it: which format rejected the input, what the
  * backend said, and where in the document it happened.
  */
class ErrorSuite extends munit.FunSuite:

  private def decode(json: String): Either[DecodeError, Row] =
    json.getBytes(UTF_8).decodeAs[Row, Json]

  private def rejected(json: String): DecodeError =
    decode(json) match
      case Left(e)  => e
      case Right(a) => fail(s"expected $json to be rejected, got $a")

  test("malformed input keeps the parser's own failure as cause") {
    val e = rejected("""{"id":1,"label":""")
    assertEquals(e.format, "Json")
    assert(e.getCause.isInstanceOf[ParsingFailure], e.getCause)
    // A decode failure is data, not a crash: no stack trace was filled in.
    assert(e.getStackTrace.isEmpty)
  }

  test("a value of the wrong type names the field it was found in") {
    val e = rejected("""{"id":1,"label":7}""")
    assert(e.getCause.isInstanceOf[DecodingFailure], e.getCause)
    // The field name lives in circe's cursor history, which only
    // `DecodingFailure.getMessage` renders — `message` alone would say no more
    // than "Got value '7' with wrong type", leaving the caller to guess where.
    assert(clue(e.message).contains(".label"))
  }

  test("bytes after the value are malformed input") {
    assert(decode("""{"id":1,"label":"ok"}}""").isLeft)
    assert(decode("""{"id":1,"label":"ok"} x""").isLeft)
  }

  test("whitespace after the value is not") {
    assertEquals(decode("""{"id":1,"label":"ok"}   """), Right(Row(1, "ok")))
  }
