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

package b8.vector

import b8.DecodeError
import b8.Encoder
import b8.Format.Json
import b8.SinkPool
import b8.jsoniter.given
import b8.laws.Fixtures
import b8.laws.Nested

import java.nio.charset.StandardCharsets.UTF_8

import scodec.bits.ByteVector

/** The container with an ordinary bridge behind it, over the shared fixtures.
  *
  * `b8.vector` and `b8.jsoniter` know nothing about each other — one picks the
  * container, the other picks the backend, and the format tag is the third
  * choice again — so this suite is the one that shows the three axes actually
  * being independent rather than merely described as such.
  *
  * `import b8.jsoniter.given` is the line a user writes; it is spelled out here
  * for the same reason each bridge's own extension suite spells out its import.
  */
class FixturesSuite extends munit.FunSuite:

  import Codecs.given

  private val encoded: ByteVector = Fixtures.nested1.encode[Json]

  test("encode and decode are inverses") {
    assertEquals(
      Fixtures.nested1.encode[Json].decodeAs[Nested, Json],
      Right(Fixtures.nested1)
    )
  }

  test("the vector is the encoder's own array, exact-size and unrewritten") {
    val expected = Encoder[Nested, Json].encode(Fixtures.nested1)
    assertEquals(encoded, ByteVector.view(expected))
    assertEquals(encoded.size, expected.length.toLong)
  }

  test("decodeAsUnsafe agrees with decodeAs") {
    assertEquals(encoded.decodeAsUnsafe[Nested, Json], Fixtures.nested1)
  }

  test("a slice of a larger vector decodes") {
    val framed =
      ByteVector.view(Array[Byte](0x7f, 0x7f, 0x7f)) ++ encoded ++
        ByteVector.view(Array[Byte](0x7f))
    assertEquals(
      framed.drop(3).dropRight(1).decodeAs[Nested, Json],
      Right(Fixtures.nested1)
    )
    // And the frame really is a different input: the padding is not quietly
    // skipped on the way past.
    assert(framed.decodeAs[Nested, Json].isLeft)
  }

  test("a malformed vector fails with the bridge's own error") {
    val broken = ByteVector.view("{".getBytes(UTF_8))
    broken.decodeAs[Nested, Json] match
      case Right(v)             => fail(s"malformed input decoded to $v")
      case Left(e: DecodeError) =>
        // The container names no format and builds no error of its own, so
        // the one that comes back has to be the bridge's, untouched.
        assertEquals(e.format, "Json")
        assert(e.message.nonEmpty)
  }

  test("a thread-local pool changes neither the bytes nor the round trip") {
    given SinkPool = SinkPool.threadLocal()
    val pooled = Fixtures.nested1.encode[Json]
    assertEquals(pooled, encoded)
    assertEquals(pooled.decodeAs[Nested, Json], Right(Fixtures.nested1))
  }
