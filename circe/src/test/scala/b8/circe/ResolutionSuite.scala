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
import b8.Decoder
import b8.Encoder
import b8.Format.Json

/** Circe can print this one and read it back. */
final case class PrintAndRead(n: Int) derives io.circe.Codec.AsObject

/** Print-only. The encoder is written out by hand because a `derives` clause
  * would hand out both directions, and a type with exactly one is the case
  * under test.
  */
final case class PrintOnly(n: Int)
object PrintOnly:
  given io.circe.Encoder[PrintOnly] = io.circe.Encoder[Int].contramap(_.n)

/** Read-only, the mirror image. */
final case class ReadOnly(n: Int)
object ReadOnly:
  given io.circe.Decoder[ReadOnly] = io.circe.Decoder[Int].map(ReadOnly(_))

/** Circe has no instance for this one in either direction. */
final case class Unsupported(n: Int)

/** What the bridge resolves for a type, and what it deliberately does not.
  *
  * The negative half is checked with `compileErrors`: "there is no decoder for
  * this type" has to be a compile error rather than a runtime surprise, which
  * is what the `NotGiven` guards on the one-way givens are for.
  *
  * The suite sits inside `b8.circe`, so the bridge's givens are already package
  * members here and need no import; a user elsewhere writes the one
  * `import b8.circe.given` that `ExtensionSuite` shows.
  */
class ResolutionSuite extends munit.FunSuite:

  /** How the compiler starts every implicit-not-found message. Asserting on it
    * keeps the negative cases honest: a typo inside a snippet would make
    * `compileErrors` non-empty too, and would prove nothing.
    */
  private val noGiven = "No given instance of type"

  test("both directions resolve, and both come from the codec given") {
    // Stronger than "all three resolve": the encoder and the decoder are the
    // instance the codec given built, so for a type circe knows fully the two
    // directions cannot be configured apart by accident.
    assert(summon[Encoder[PrintAndRead, Json]].isInstanceOf[CirceCodec[?]])
    assert(summon[Decoder[PrintAndRead, Json]].isInstanceOf[CirceCodec[?]])
    assert(summon[Codec[PrintAndRead, Json]].isInstanceOf[CirceCodec[?]])
  }

  test("a print-only type resolves as an encoder and nothing else") {
    assert(summon[Encoder[PrintOnly, Json]].isInstanceOf[CirceEncoder[?]])
    assert(compileErrors("summon[Decoder[PrintOnly, Json]]").contains(noGiven))
    assert(compileErrors("summon[Codec[PrintOnly, Json]]").contains(noGiven))
  }

  test("a read-only type resolves as a decoder and nothing else") {
    assert(summon[Decoder[ReadOnly, Json]].isInstanceOf[CirceDecoder[?]])
    assert(compileErrors("summon[Encoder[ReadOnly, Json]]").contains(noGiven))
    assert(compileErrors("summon[Codec[ReadOnly, Json]]").contains(noGiven))
  }

  test("a type circe knows nothing about resolves as nothing") {
    assert(
      compileErrors("summon[Encoder[Unsupported, Json]]").contains(noGiven)
    )
    assert(
      compileErrors("summon[Decoder[Unsupported, Json]]").contains(noGiven)
    )
  }
