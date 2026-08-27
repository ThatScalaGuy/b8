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
import b8.Format.Cbor
import b8.Format.Json
import b8.array.*
import b8.borer.cbor.CborCodec
import b8.borer.cbor.given
import b8.circe.CirceCodec
import b8.jsoniter.JsoniterCodec
import b8.jsoniter.given
import b8.laws.Fixtures
import b8.laws.Flat

import scala.annotation.nowarn

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.core.writeToArray
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

/** Two backends in one scope: jsoniter for JSON, borer for CBOR.
  *
  * This is the supported way to combine them, and it works for one reason: the
  * two givens answer for different format tags, so neither summon has a second
  * candidate to weigh. `b8.borer.cbor` is the per-format sub-package that makes
  * this possible — the aggregate `b8.borer` bridge answers for JSON as well,
  * and putting that next to a JSON bridge from somewhere else is the situation
  * the second half of this suite pins down.
  *
  * The package is `b8.mixing` and not `b8.jsoniter` on purpose. A suite written
  * under `b8.jsoniter` would have that package's given as a member of its own
  * package, in scope without any import — which is exactly the thing under
  * test. In `b8.mixing` nothing is in scope until it is imported, the position
  * an ordinary user's file is in.
  */
class JsoniterMixingSuite extends munit.FunSuite:

  // All three are named, which the bridges' own givens are not. An anonymous
  // `given` takes its name from the type it produces, and `borerFlat` and
  // `circeFlat` both produce a `Codec[Flat]` — one borer's, one circe's — so
  // the two would land on the same synthesised name and the second would
  // shadow the first. Naming them is what keeps all three available at once.
  given jsoniterFlat: JsonValueCodec[Flat] = JsonCodecMaker.make
  given borerFlat: io.bullet.borer.Codec[Flat] =
    io.bullet.borer.derivation.MapBasedCodecs.deriveCodec
  given circeFlat: io.circe.Codec[Flat] = io.circe.generic.semiauto.deriveCodec

  test("each format is answered by the backend it was imported from") {
    // The format tag alone decides. `Format.Json` has only the jsoniter given
    // to match, `Format.Cbor` only the borer one, so the two imports cannot
    // interfere.
    assert(summon[Codec[Flat, Json]].isInstanceOf[JsoniterCodec[?]])
    assert(summon[Codec[Flat, Cbor]].isInstanceOf[CborCodec[?]])
  }

  test("both backends encode and read back their own bytes") {
    assertEquals(
      Fixtures.flat1.encode[Json].decodeAs[Flat, Json],
      Right(Fixtures.flat1)
    )
    assertEquals(
      Fixtures.flat1.encode[Cbor].decodeAs[Flat, Cbor],
      Right(Fixtures.flat1)
    )
  }

  test("the JSON bytes are the ones jsoniter writes") {
    // Round-tripping only shows that the two directions agree with each other.
    // This one names the writer: the bytes are compared against jsoniter's own
    // `writeToArray`, which allocates an array of its own and hands it back —
    // a path the bridge never takes, it writes into the sink's array in place
    // — so the two sides meet in the output and nowhere else.
    val expected = writeToArray(Fixtures.flat1)
    assert(Fixtures.flat1.encode[Json].sameElements(expected))
  }

  /** What two JSON bridges in one scope actually do, pinned here because it is
    * not what a reader expects and it is invisible otherwise.
    *
    * `b8.circe` offers a `Codec[Flat, Format.Json]` and so does `b8.jsoniter`.
    * That does not clash — every one of these givens is anonymous and they all
    * end up with the same synthesised name, so the second import shadows the
    * first instead of competing with it. Both objects below compile, and which
    * backend answers for JSON is decided by which import is written last.
    *
    * The `@nowarn` is the interesting part. Without it both objects raise
    * `unused import`, and the import the compiler names is the shadowed one. So
    * under `-Wunused:all`, which this build turns on everywhere, the shadowing
    * is not silent after all — the warning on the losing line is the symptom to
    * recognise. It is silent for everyone who does not.
    */
  @nowarn("msg=unused import")
  private object jsoniterThenCirce:
    import b8.jsoniter.given
    import b8.circe.given

    def json: Codec[Flat, Json] = summon

  @nowarn("msg=unused import")
  private object circeThenJsoniter:
    import b8.circe.given
    import b8.jsoniter.given

    def json: Codec[Flat, Json] = summon

  test("two JSON bridges in one scope are decided by import order") {
    assert(jsoniterThenCirce.json.isInstanceOf[CirceCodec[?]])
    assert(circeThenJsoniter.json.isInstanceOf[JsoniterCodec[?]])
    // Two import lines, swapped, two different writers — and the only trace is
    // an "unused import" on the line that lost. Importing one JSON bridge, and
    // a per-format sub-package for anything else, is what avoids the question.
  }
