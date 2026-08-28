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

import b8.laws.Fixtures
import b8.laws.Flat
import b8.laws.Kind
import b8.laws.Nested
import b8.laws.Shape
import b8.scalapb.protos.PCircle
import b8.scalapb.protos.PFlat
import b8.scalapb.protos.PKind
import b8.scalapb.protos.PNested
import b8.scalapb.protos.PRect
import b8.scalapb.protos.PShape

import org.scalacheck.Arbitrary

/** The shared law fixtures, as the messages `b8_fixtures.proto` generates.
  *
  * The proto types are mirrors, so the mapping is total in one direction and
  * mechanical: field for field, with the two shape changes protobuf imposes. A
  * proto3 message field has explicit presence, so ScalaPB gives `PNested` an
  * `Option[PFlat]` where `Nested` has a plain `Flat`, and a `repeated` field
  * arrives as `Seq` where `Flat` has a `List` and `Nested` a `Vector` — both
  * compare equal by content, so neither shows up in a law.
  *
  * The generators are the fixtures' own, run through those conversions rather
  * than written again. That is the point of doing it this way: `b8-scalapb`
  * sees the same distribution of ids, strings, doubles and collection sizes
  * that every other bridge is measured on, so a law that passes here and fails
  * for circe is a difference between the bridges and not between two sets of
  * generators.
  *
  * Two values the proto types admit therefore never come out of these
  * generators, because the Scala types they are mapped from cannot express
  * them: `PShape.Shape.Empty`, the oneof with no case set, and
  * `PKind.Unrecognized`, the enum value from a newer schema. Both are real and
  * both are reachable over the wire, so `ProtoSemanticsSuite` covers them by
  * hand rather than leaving them to a generator that will never draw them.
  */
object ProtoFixtures:

  def toProto(f: Flat): PFlat =
    PFlat(
      id = f.id,
      name = f.name,
      active = f.active,
      score = f.score,
      tags = f.tags
    )

  def toProto(k: Kind): PKind = k match
    case Kind.Alpha => PKind.ALPHA
    case Kind.Beta  => PKind.BETA
    case Kind.Gamma => PKind.GAMMA

  def toProto(s: Shape): PShape = s match
    case Shape.Circle(radius) =>
      PShape(PShape.Shape.Circle(PCircle(radius)))
    case Shape.Rect(width, height) =>
      PShape(PShape.Shape.Rect(PRect(width, height)))

  def toProto(n: Nested): PNested =
    PNested(
      flat = Some(toProto(n.flat)),
      children = n.children.map(c => toProto(c)),
      meta = n.meta,
      opt = n.opt.map(o => toProto(o)),
      kind = toProto(n.kind),
      shape = Some(toProto(n.shape))
    )

  import Fixtures.given

  given Arbitrary[PFlat] = Arbitrary(Arbitrary.arbitrary[Flat].map(toProto))
  given Arbitrary[PShape] = Arbitrary(Arbitrary.arbitrary[Shape].map(toProto))
  given Arbitrary[PNested] = Arbitrary(Arbitrary.arbitrary[Nested].map(toProto))

  /** `Fixtures.flat1`, as a message. */
  val pFlat1: PFlat = toProto(Fixtures.flat1)

  /** `Fixtures.nested1`, as a message — the value the benchmarks encode. */
  val pNested1: PNested = toProto(Fixtures.nested1)
