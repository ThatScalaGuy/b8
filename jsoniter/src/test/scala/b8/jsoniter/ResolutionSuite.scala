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
import b8.Decoder
import b8.Encoder
import b8.Format.Json

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

/** jsoniter has a codec for this one. */
final case class Supported(n: Int)

object Supported:
  given JsonValueCodec[Supported] = JsonCodecMaker.make

/** jsoniter has no codec for this one, and no way to conjure one. */
final case class Unsupported(n: Int)

/** What the bridge resolves for a type, and what it deliberately does not.
  *
  * Much shorter than the same suite in the circe and borer modules, and the
  * reason is in jsoniter's model rather than in what is tested here.
  * `JsonValueCodec[A]` carries `encodeValue` and `decodeValue` in one trait, so
  * jsoniter has no write-only and no read-only types: either a type has the
  * instance and both directions come with it, or it has nothing. That is why
  * `b8.jsoniter` hands out exactly one given and needs no `NotGiven` guards to
  * keep it from competing with a one-way sibling it does not have.
  *
  * The negative half is checked with `compileErrors`: "there is no codec for
  * this type" has to be a compile error rather than a runtime surprise.
  *
  * The suite sits inside `b8.jsoniter`, so the bridge's given is already a
  * package member here and needs no import; a user elsewhere writes the one
  * `import b8.jsoniter.given` that `ExtensionSuite` shows.
  */
class ResolutionSuite extends munit.FunSuite:

  /** How the compiler starts every implicit-not-found message. Asserting on it
    * keeps the negative cases honest: a typo inside a snippet would make
    * `compileErrors` non-empty too, and would prove nothing.
    */
  private val noGiven = "No given instance of type"

  test("all three resolve, and all three are the one instance") {
    // Stronger than "all three resolve": the encoder and the decoder are the
    // same `JsoniterCodec` the codec given built, so the two directions of a
    // type cannot end up configured apart. With one given and one instance
    // class that is almost a tautology — which is the point, and what would
    // stop being true the moment the bridge grew a second given.
    assert(summon[Encoder[Supported, Json]].isInstanceOf[JsoniterCodec[?]])
    assert(summon[Decoder[Supported, Json]].isInstanceOf[JsoniterCodec[?]])
    assert(summon[Codec[Supported, Json]].isInstanceOf[JsoniterCodec[?]])
  }

  test("a type jsoniter knows nothing about resolves as nothing") {
    assert(
      compileErrors("summon[Encoder[Unsupported, Json]]").contains(noGiven)
    )
    assert(
      compileErrors("summon[Decoder[Unsupported, Json]]").contains(noGiven)
    )
    assert(compileErrors("summon[Codec[Unsupported, Json]]").contains(noGiven))
  }
