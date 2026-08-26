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

import b8.Codec
import b8.Format.Json
import b8.laws.*

import java.nio.charset.StandardCharsets.UTF_8

import io.circe.generic.semiauto.deriveCodec
import org.scalacheck.Arbitrary

/** The shared law set, run against the bridge for every fixture.
  *
  * The fixtures live in `b8-laws` and carry no `derives` clause on purpose, so
  * their circe codecs are derived here from the outside with `deriveCodec`.
  * Those are the same instances a `derives io.circe.Codec.AsObject` on the
  * types themselves would have produced.
  */
class CirceLawsSuite extends LawsSuite:

  import Fixtures.given

  given io.circe.Codec[Flat] = deriveCodec
  given io.circe.Codec[Kind] = deriveCodec
  given io.circe.Codec[Shape] = deriveCodec
  given io.circe.Codec[Nested] = deriveCodec

  /** A second kind of trailing input, run alongside the default one.
    *
    * The default is a single NUL byte; jawn rejects it because only space, tab,
    * CR and LF may follow a value. `}` is the other half of that story: a
    * printable byte that closes nothing still open, so a decoder that stops at
    * the end of the first value and never looks further would swallow it.
    */
  private val brace: Array[Byte] = "}".getBytes(UTF_8)

  /** Registers both runs for one fixture. The second set needs a name of its
    * own because munit reports tests by name, and two sets sharing one would be
    * indistinguishable in the output.
    */
  private def laws[A](name: String)(using Codec[A, Json], Arbitrary[A]): Unit =
    checkAll(CodecLaws[A, Json](name))
    checkAll(CodecLaws[A, Json](s"$name.brace", trailing = Some(brace)))

  laws[Flat]("circe.Flat")
  laws[Kind]("circe.Kind")
  laws[Shape]("circe.Shape")
  laws[Nested]("circe.Nested")
