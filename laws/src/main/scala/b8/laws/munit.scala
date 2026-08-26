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

import org.scalacheck.Properties

/** munit entry point for the law sets.
  *
  * {{{
  * class MyBridgeSuite extends LawsSuite:
  *   checkAll(CodecLaws[Flat, Format.Json]("mybridge.Flat"))
  * }}}
  */
trait LawsSuite extends munit.ScalaCheckSuite:

  /** Registers every law in `props` as a munit test of its own, so a failure
    * names the law that broke instead of the whole set.
    *
    * The names come out as `Properties` recorded them, already prefixed with
    * the set's own name: a set built as `CodecLaws[Flat, Json]("circe.Flat")`
    * reports `circe.Flat.roundTrip`. Prefixing again here would double it up.
    */
  def checkAll(props: Properties): Unit =
    props.properties.foreach { (name, prop) =>
      property(name)(prop)
    }
