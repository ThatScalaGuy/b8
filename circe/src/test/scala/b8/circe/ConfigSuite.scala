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

import b8.ByteSource
import b8.Codec
import b8.Format.Json
import b8.array.*
import b8.laws.Fixtures
import b8.laws.Flat

import java.nio.charset.StandardCharsets.UTF_8

import io.circe.ParsingFailure
import io.circe.Printer
import io.circe.generic.semiauto.deriveCodec
import io.circe.jawn.JawnParser

/** One field, so that `{"a":1,"a":2}` is a document about duplicate keys and
  * nothing else.
  */
final case class Counter(a: Int) derives io.circe.Codec.AsObject

/** The printer and the parser are the two knobs the bridge exposes, and both
  * are reachable without giving up the givens: a codec built by hand wins over
  * the imported default wherever it is in scope.
  */
class ConfigSuite extends munit.FunSuite:

  given io.circe.Codec[Flat] = deriveCodec

  /** A scope of its own for the indented codec, so the rest of the suite still
    * sees the bridge's default. Inside it the hand-built codec outranks the
    * imported given: it is a value of exactly the summoned type, while the
    * bridge's given is parameterised and takes arguments of its own, which
    * makes it the less specific of the two.
    */
  private object pretty:
    given Codec[Flat, Json] = codec(Printer.spaces2)
    def encoded: String = new String(Fixtures.flat1.encode[Json], UTF_8)

  test("a hand-built codec replaces the default printer") {
    val compact = new String(Fixtures.flat1.encode[Json], UTF_8)
    assert(!compact.contains("\n"), compact)
    assert(pretty.encoded.contains("\n"), pretty.encoded)
    // Only the spacing changed: the indented bytes still read back as the
    // same value, through the default decoder.
    assertEquals(
      pretty.encoded.getBytes(UTF_8).decodeAs[Flat, Json],
      Right(Fixtures.flat1)
    )
  }

  test("a stricter parser turns duplicate keys into a decode failure") {
    val json = """{"a":1,"a":2}""".getBytes(UTF_8)
    // The default parser is jawn's own: duplicates pass, last one wins.
    assertEquals(json.decodeAs[Counter, Json], Right(Counter(2)))

    val strict = decoder[Counter](JawnParser(allowDuplicateKeys = false))
    strict.decode(ByteSource(json)) match
      case Right(a) => fail(s"expected a rejection, got $a")
      case Left(e)  =>
        assertEquals(e.format, "Json")
        assert(clue(e.message).contains("duplicate key name found: a"))
        // jawn raises this one itself, so the cause is a ParsingFailure with
        // the IllegalArgumentException it wrapped.
        e.getCause match
          case pf: ParsingFailure =>
            assert(pf.underlying.isInstanceOf[IllegalArgumentException])
          case other => fail(s"expected a ParsingFailure, got $other")
  }
