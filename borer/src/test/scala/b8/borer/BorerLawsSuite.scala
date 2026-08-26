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

package b8.borer

import b8.Codec
import b8.Format
import b8.laws.*

import java.nio.charset.StandardCharsets.UTF_8

import org.scalacheck.Arbitrary

/** The shared law set, run against both halves of the bridge for every fixture.
  *
  * Eight sets in total: four fixtures times two formats, from a single set of
  * borer codecs. That is the property worth testing here and not in any other
  * bridge — the type says CBOR or JSON, and nothing else changes.
  */
class BorerLawsSuite extends LawsSuite:

  import Codecs.given
  import Fixtures.given

  /** Trailing input for CBOR.
    *
    * The law set's own default is a NUL byte, which CBOR reads as the integer
    * zero — a complete second value where end of input was required. `0xFF` is
    * the other half of the story: the break stop code, which is only legal
    * inside an indefinite-length item and closes nothing at the top level.
    */
  private val breakCode: Array[Byte] = Array(0xff.toByte)

  /** Trailing input for JSON, and the reason the JSON sets do not take the
    * default.
    *
    * borer's JSON parser treats every byte up to `0x20` as whitespace, so the
    * default NUL is accepted rather than rejected — as is `0xFF`, which is the
    * parser's own end-of-input marker. `}` is a byte that is neither: it closes
    * nothing still open, so a decoder that stopped at the end of the first
    * value and never looked further would swallow it, and borer does not.
    */
  private val brace: Array[Byte] = "}".getBytes(UTF_8)

  /** Registers both CBOR runs for one fixture. The second set needs a name of
    * its own because munit reports tests by name, and two sets sharing one
    * would be indistinguishable in the output.
    */
  private def cborLaws[A](
      name: String
  )(using Codec[A, Format.Cbor], Arbitrary[A]): Unit =
    checkAll(CodecLaws[A, Format.Cbor](name))
    checkAll(
      CodecLaws[A, Format.Cbor](s"$name.break", trailing = Some(breakCode))
    )

  private def jsonLaws[A](
      name: String
  )(using Codec[A, Format.Json], Arbitrary[A]): Unit =
    checkAll(CodecLaws[A, Format.Json](name, trailing = Some(brace)))

  cborLaws[Flat]("borer.cbor.Flat")
  cborLaws[Kind]("borer.cbor.Kind")
  cborLaws[Shape]("borer.cbor.Shape")
  cborLaws[Nested]("borer.cbor.Nested")

  jsonLaws[Flat]("borer.json.Flat")
  jsonLaws[Kind]("borer.json.Kind")
  jsonLaws[Shape]("borer.json.Shape")
  jsonLaws[Nested]("borer.json.Nested")
