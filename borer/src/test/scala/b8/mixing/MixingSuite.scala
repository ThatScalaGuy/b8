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

package b8.mixing

import b8.Codec
import b8.Format
import b8.array.*
import b8.borer.cbor.CborCodec
import b8.borer.cbor.given
import b8.circe.CirceCodec
import b8.circe.given
import b8.laws.Fixtures
import b8.laws.Flat

import java.nio.charset.StandardCharsets.UTF_8

import io.circe.Printer

/** Two backends in one scope: circe for JSON, borer for CBOR.
  *
  * This is what the `b8.borer.cbor` and `b8.borer.json` sub-packages exist for.
  * The aggregate `b8.borer` bridge serves both formats and therefore cannot
  * share a scope with a JSON bridge from somewhere else; taking only
  * `b8.borer.cbor` leaves JSON free for circe.
  *
  * The package is `b8.mixing` and not `b8.borer` on purpose. A suite written
  * under `b8.borer` would have that package's six givens as members of its own
  * package, in scope without any import, and those six cover both formats —
  * which is exactly the situation under test here. In `b8.mixing` nothing is in
  * scope until it is imported, the position an ordinary user's file is in.
  */
class MixingSuite extends munit.FunSuite:

  // Both are named, which anonymous givens elsewhere in the build are not. An
  // anonymous `given` gets its name from the type it produces, and both of
  // these produce a `Codec[Flat]` — one circe's, one borer's — so the two would
  // land on the same synthesised name and the second would shadow the first.
  given circeFlat: io.circe.Codec[Flat] = io.circe.generic.semiauto.deriveCodec
  given borerFlat: io.bullet.borer.Codec[Flat] =
    io.bullet.borer.derivation.MapBasedCodecs.deriveCodec

  test("each format is answered by the backend it was imported from") {
    // The format tag alone decides. `Format.Json` has only the circe given to
    // match, `Format.Cbor` only the borer one, so neither summon has a second
    // candidate to weigh and the two imports cannot interfere.
    assert(summon[Codec[Flat, Format.Json]].isInstanceOf[CirceCodec[?]])
    assert(summon[Codec[Flat, Format.Cbor]].isInstanceOf[CborCodec[?]])
  }

  test("both backends encode and read back their own bytes") {
    assertEquals(
      Fixtures.flat1.encode[Format.Json].decodeAs[Flat, Format.Json],
      Right(Fixtures.flat1)
    )
    assertEquals(
      Fixtures.flat1.encode[Format.Cbor].decodeAs[Flat, Format.Cbor],
      Right(Fixtures.flat1)
    )
  }

  test("the JSON bytes are the ones circe prints") {
    // Round-tripping only shows that the two directions agree with each other.
    // This one names the writer: the bytes are compared against circe's own
    // `Printer.print`, which builds a `String` — a path the bridge never
    // takes, it prints into a `ByteBuffer` — so the two sides meet in the
    // output and nowhere else.
    val expected =
      Printer.noSpaces.print(circeFlat(Fixtures.flat1)).getBytes(UTF_8)
    assert(Fixtures.flat1.encode[Format.Json].sameElements(expected))
  }

  // Swapping the CBOR import for the aggregate `import b8.borer.given` would
  // break this file: borer would offer a `Codec[Flat, Format.Json]` next to
  // circe's, neither of the two more specific than the other, and the summon
  // in the first test would stop compiling. The aggregate import and the
  // per-format ones are alternatives, not layers — a scope takes one shape or
  // the other, never both.
