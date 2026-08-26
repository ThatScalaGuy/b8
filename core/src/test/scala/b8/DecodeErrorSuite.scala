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

class DecodeErrorSuite extends munit.FunSuite:

  test("carries no stack trace") {
    val error = DecodeError("unexpected end of input", "json")
    assert(error.getStackTrace.isEmpty)
    assertEquals(error.message, "unexpected end of input")
    assertEquals(error.format, "json")
    assertEquals(error.getMessage, "unexpected end of input")
    assertEquals(error.getCause, null)
  }

  test("keeps the backend exception as cause") {
    val backend = new IllegalStateException("truncated object")
    val error = DecodeError("malformed json", "json", backend)
    assert(error.getCause eq backend)
  }

  test("suppression is off") {
    val error = DecodeError("nope", "cbor")
    error.addSuppressed(new IllegalStateException("ignored"))
    assert(error.getSuppressed.isEmpty)
  }

  test(
    "decode turns a DecodeError into a Left and lets everything else through"
  ) {
    val expected = DecodeError("nope", "json")

    val failing = new Decoder[Int, Format.Json]:
      def decodeUnsafe(in: ByteSource): Int = throw expected
    assertEquals(
      failing.decode(ByteSource.empty),
      Left(expected): Either[DecodeError, Int]
    )

    val exploding = new Decoder[Int, Format.Json]:
      def decodeUnsafe(in: ByteSource): Int =
        throw new IllegalStateException("boom")
    intercept[IllegalStateException](exploding.decode(ByteSource.empty))
  }
