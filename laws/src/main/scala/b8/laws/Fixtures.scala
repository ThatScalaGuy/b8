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

package b8.laws

import org.scalacheck.Arbitrary
import org.scalacheck.Gen

/** Scalars plus one repeated field: the shape most messages start out as. */
final case class Flat(
    id: Long,
    name: String,
    active: Boolean,
    score: Double,
    tags: List[String]
)

/** A sum whose cases carry no data. */
enum Kind:
  case Alpha, Beta, Gamma

/** A sum whose cases carry different data. */
enum Shape:
  case Circle(radius: Double)
  case Rect(width: Double, height: Double)

/** Wraps `Flat` in every container a bridge has to handle. */
final case class Nested(
    flat: Flat,
    children: Vector[Flat],
    meta: Map[String, String],
    opt: Option[Flat],
    kind: Kind,
    shape: Shape
)

/** The model types, generators and fixed values every b8 bridge is tested
  * against.
  *
  * Deliberately backend-agnostic: the types carry no serialization annotations
  * and this module depends on no backend. Every bridge derives its own codecs
  * for them in its own test sources, which keeps law results — and the
  * benchmark numbers built on the same values — comparable across backends.
  */
object Fixtures:

  /** The pieces a generated string is glued together from.
    *
    * Drawing whole literals instead of single chars is what keeps the emoji's
    * surrogate pair intact. A lone surrogate would not survive a UTF-8 encode —
    * it turns into `'?'` — and would fail every round-trip law for reasons that
    * have nothing to do with the bridge under test.
    */
  private val stringChunks: Seq[String] = Seq(
    "",
    "plain",
    "with space",
    "\"",
    "\\",
    "/",
    "\n",
    "\t",
    "\r",
    "\u0000",
    "\u001f",
    "äöüß",
    "Привет",
    "日本語",
    "🎉",
    "null",
    "{\"k\":[1,2]}"
  )

  private val genString: Gen[String] =
    Gen
      .choose(0, 6)
      .flatMap(Gen.listOfN(_, Gen.oneOf(stringChunks)))
      .map(_.mkString)

  /** Finite doubles only.
    *
    * JSON has neither NaN nor infinity: encoders write them as `null`, which no
    * round trip survives. Every finite double on the other hand comes back
    * bit-exact, because `Double.toString` is round-trippable, so the extremes
    * are safe to generate. The bounds handed to `chooseNum` are finite and the
    * result is filtered on top, so nothing non-finite reaches a law.
    */
  private val genDouble: Gen[Double] =
    Gen
      .frequency(
        4 -> Gen.chooseNum(-1.0e12, 1.0e12),
        1 -> Gen.oneOf(
          0.0,
          -0.0,
          1.0,
          -1.0,
          0.5,
          -3.25e-7,
          6.02e23,
          Double.MinPositiveValue,
          Double.MaxValue,
          Double.MinValue
        )
      )
      .filter(_.isFinite)

  /** Collections stay short: the law suite runs six properties per fixture, so
    * a generator that occasionally produces thousands of elements would only
    * show up as build time.
    */
  private def genList[A](g: Gen[A]): Gen[List[A]] =
    Gen.choose(0, 8).flatMap(Gen.listOfN(_, g))

  private val genFlat: Gen[Flat] =
    for
      id <- Arbitrary.arbitrary[Long]
      name <- genString
      active <- Arbitrary.arbitrary[Boolean]
      score <- genDouble
      tags <- genList(genString)
    yield Flat(id, name, active, score, tags)

  private val genKind: Gen[Kind] = Gen.oneOf(Kind.values.toSeq)

  private val genShape: Gen[Shape] =
    Gen.oneOf[Shape](
      genDouble.map(Shape.Circle(_)),
      for
        width <- genDouble
        height <- genDouble
      yield Shape.Rect(width, height)
    )

  private val genNested: Gen[Nested] =
    for
      flat <- genFlat
      children <- genList(genFlat)
      meta <- genList(genString.flatMap(k => genString.map(k -> _)))
      opt <- Gen.option(genFlat)
      kind <- genKind
      shape <- genShape
    yield Nested(flat, children.toVector, meta.toMap, opt, kind, shape)

  given Arbitrary[Flat] = Arbitrary(genFlat)
  given Arbitrary[Kind] = Arbitrary(genKind)
  given Arbitrary[Shape] = Arbitrary(genShape)
  given Arbitrary[Nested] = Arbitrary(genNested)

  /** One fixed record, around 170 bytes of JSON. */
  val flat1: Flat = Flat(
    id = 4815162342L,
    name = "flat \"one\" \\ / ünïcödé Привет 日本語 🎉",
    active = true,
    score = 1234.5678,
    tags = List("alpha", "beta", "gamma", "tab\there", "line\nbreak")
  )

  /** One fixed message of around 1 KB of JSON — the size band most service
    * payloads fall into, and the reason the benchmarks encode this one.
    */
  val nested1: Nested = Nested(
    flat = flat1,
    children = Vector(
      flat1.copy(
        id = 2,
        name = "child zwei — ждём 🐙",
        score = -0.5,
        tags = List("x", "y")
      ),
      flat1.copy(
        id = 3,
        name = "child trois — 三番目",
        active = false,
        score = 0.0025
      ),
      flat1.copy(id = 4, name = "child four", score = 1.0e10, tags = Nil),
      flat1.copy(
        id = 5,
        name = "kind fünf — пятый — 五番目",
        active = false,
        score = -12345.678,
        tags = List("tag-a", "tag-b", "tag-c")
      )
    ),
    meta = Map(
      "region" -> "eu-central-1",
      "trace" -> "0af7651916cd43dd8448eb211c80319c",
      "note" -> "с пробелом / スペース",
      "lang" -> "de-DE"
    ),
    opt = Some(flat1.copy(id = 6, tags = Nil)),
    kind = Kind.Beta,
    shape = Shape.Rect(16.0, 9.0)
  )
