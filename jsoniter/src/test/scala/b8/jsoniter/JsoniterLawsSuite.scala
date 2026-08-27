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

package b8.jsoniter

import b8.Codec
import b8.Format.Json
import b8.laws.*

import java.nio.charset.StandardCharsets.UTF_8

import org.scalacheck.Arbitrary

/** The shared law set, run against the bridge for every fixture.
  *
  * Eight sets in total: four fixtures times two kinds of trailing input. The
  * second kind is there because the first one is nearly free, and a bridge that
  * only ever sees one trailing byte can pass `trailingRejected` for the wrong
  * reason.
  */
class JsoniterLawsSuite extends LawsSuite:

  import Codecs.given
  import Fixtures.given

  /** A second kind of trailing input, run alongside the law set's default.
    *
    * The default is a single NUL byte, and taking it is a deliberate choice
    * rather than an oversight. borer's JSON parser reads every byte below
    * `0x20` as whitespace and swallows a trailing NUL, which is why the borer
    * suites have to pass `}` instead; jsoniter's whitespace is exactly space,
    * tab, CR and LF, so the NUL is a byte after the value and
    * `checkForEndOfInput` rejects it. Running the default here is what pins
    * that difference in place.
    *
    * `}` is the other half of the story: a printable byte that closes nothing
    * still open, so a decoder that stopped at the end of the first value and
    * never looked further would swallow it.
    */
  private val brace: Array[Byte] = "}".getBytes(UTF_8)

  /** Registers both runs for one fixture. The second set needs a name of its
    * own because munit reports tests by name, and two sets sharing one would be
    * indistinguishable in the output.
    */
  private def jsonLaws[A](
      name: String
  )(using Codec[A, Json], Arbitrary[A]): Unit =
    checkAll(CodecLaws[A, Json](name))
    checkAll(CodecLaws[A, Json](s"$name.brace", trailing = Some(brace)))

  jsonLaws[Flat]("jsoniter.Flat")
  jsonLaws[Kind]("jsoniter.Kind")
  jsonLaws[Shape]("jsoniter.Shape")
  jsonLaws[Nested]("jsoniter.Nested")
