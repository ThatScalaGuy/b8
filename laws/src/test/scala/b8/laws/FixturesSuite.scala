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

import java.nio.charset.StandardCharsets.UTF_8

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll

/** Guards the two generator invariants every bridge silently relies on. Both
  * failure modes look like a broken bridge when they hit, so they are checked
  * where they belong: on the generators themselves.
  */
class FixturesSuite extends ScalaCheckSuite:

  import Fixtures.given

  private def doublesOf(n: Nested): List[Double] =
    val flats = n.flat :: n.opt.toList ::: n.children.toList
    val shape = n.shape match
      case Shape.Circle(r)  => List(r)
      case Shape.Rect(w, h) => List(w, h)
    flats.map(_.score) ::: shape

  private def stringsOf(n: Nested): List[String] =
    val flats = n.flat :: n.opt.toList ::: n.children.toList
    flats.flatMap(f => f.name :: f.tags) :::
      n.meta.keys.toList ::: n.meta.values.toList

  property("generated doubles are finite") {
    forAll { (n: Nested) => doublesOf(n).forall(_.isFinite) }
  }

  // A lone surrogate encodes to '?', so a string carrying one could never
  // round-trip through any UTF-8 based format.
  property("generated strings survive a UTF-8 round trip") {
    forAll { (n: Nested) =>
      stringsOf(n).forall(s => new String(s.getBytes(UTF_8), UTF_8) == s)
    }
  }
