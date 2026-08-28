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
import b8.Format.Json
import b8.Format.Proto
import b8.array.*
import b8.jsoniter.JsoniterCodec
import b8.jsoniter.given
import b8.laws.Fixtures
import b8.laws.Flat
import b8.scalapb.ProtoFixtures
import b8.scalapb.ScalapbCodec
import b8.scalapb.given
import b8.scalapb.protos.PNested

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

/** Two backends in one scope: ScalaPB for Protobuf, jsoniter for JSON.
  *
  * This is the quiet member of the mixing family, and it is worth being exact
  * about why. Two JSON bridges in one scope do not clash, they shadow — the
  * second import wins and the only trace is an "unused import" on the line that
  * lost. `b8.mixing.JsoniterMixingSuite` pins that down and it is not repeated
  * here. Nothing of the sort can happen between the two imports below. A Proto
  * bridge and a JSON bridge answer for different format tags, so
  * `summon[Codec[PNested, Proto]]` and `summon[Codec[Flat, Json]]` each have
  * exactly one candidate to weigh, and the order the imports are written in
  * does not enter into it. They do not even collide by name: an anonymous given
  * takes its name from the type it produces, and these two produce codecs for
  * different formats.
  *
  * `b8.scalapb` is the only thing in b8 that claims `Format.Proto`. It has no
  * sibling that could shadow it and it is not part of an aggregate import that
  * drags a JSON codec along, the way `b8.borer` does — so unlike the CBOR case
  * there is no per-format sub-package to reach for and no import order to get
  * right. Importing it is the whole story.
  *
  * The package is `b8.mixing` and not `b8.scalapb` on purpose. A suite written
  * under `b8.scalapb` would have that package's given as a member of its own
  * package, in scope without any import at all — which is exactly the thing
  * under test. In `b8.mixing` nothing is in scope until it is imported, the
  * position an ordinary user's file is in.
  */
class ScalapbMixingSuite extends munit.FunSuite:

  // Named, which the bridges' own givens are not. Nothing collides here —
  // jsoniter is the only JSON backend on this module's test classpath, and
  // ScalaPB needs no per-type given at all, since a message carries its
  // companion. The name buys nothing today; it is written this way because
  // the habit is what keeps `JsoniterMixingSuite` compiling, where two
  // anonymous `Codec[Flat]` givens land on the same synthesised name and the
  // second shadows the first.
  given jsoniterFlat: JsonValueCodec[Flat] = JsonCodecMaker.make

  test("each format is answered by the backend it was imported from") {
    // The format tag alone decides. `Format.Proto` has only the ScalaPB given
    // to match, `Format.Json` only the jsoniter one, so the two imports cannot
    // interfere.
    assert(summon[Codec[PNested, Proto]].isInstanceOf[ScalapbCodec[?]])
    assert(summon[Codec[Flat, Json]].isInstanceOf[JsoniterCodec[?]])
  }

  test("both backends encode and read back their own bytes") {
    assertEquals(
      ProtoFixtures.pNested1.encode[Proto].decodeAs[PNested, Proto],
      Right(ProtoFixtures.pNested1)
    )
    assertEquals(
      Fixtures.flat1.encode[Json].decodeAs[Flat, Json],
      Right(Fixtures.flat1)
    )
  }

  test("the Proto bytes are the ones ScalaPB writes") {
    // Round-tripping only shows that the two directions agree with each other;
    // a bridge that invented a dialect and read it back would pass that too.
    // This one names the writer: the bytes are compared against ScalaPB's own
    // `toByteArray`, which allocates an array of exactly `serializedSize` and
    // hands it back — a path the bridge never takes, it builds a
    // `CodedOutputStream` over the sink's own array and writes in place — so
    // the two sides meet in the output and nowhere else.
    val expected = ProtoFixtures.pNested1.toByteArray
    assert(ProtoFixtures.pNested1.encode[Proto].sameElements(expected))
  }
