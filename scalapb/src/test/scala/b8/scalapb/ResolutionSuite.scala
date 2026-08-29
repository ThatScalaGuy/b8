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

import b8.Codec
import b8.Decoder
import b8.Encoder
import b8.Format.Proto
import b8.scalapb.protos.PNested

/** A case class written by hand, which no protoc ever saw. */
final case class NotAMessage(n: Int)

/** What the bridge resolves for a type, and what it deliberately does not.
  *
  * Much shorter than the same suite in the circe and borer modules, and the
  * reason is in ScalaPB's model rather than in what is tested here. A message
  * and its `GeneratedMessageCompanion` come out of one protoc run together —
  * the companion is an implicit member of the message's own companion object,
  * and nothing can pull the two apart. So ScalaPB has no write-only and no
  * read-only type, the way a borer `Encoder` with no `Decoder` is one, and
  * `b8.scalapb` hands out exactly one given: there is no one-way sibling for a
  * `NotGiven` guard to keep it clear of.
  *
  * The negative half is checked with `compileErrors`, because "this type is not
  * a protobuf message" has to be a compile error and not a runtime surprise.
  *
  * The suite sits inside `b8.scalapb`, so the bridge's given is a member of
  * this file's own package and needs no import. That makes the import the one
  * thing this file cannot check, and the case where it matters — a Proto bridge
  * and a JSON bridge imported into one scope, each answering for its own format
  * tag — is `b8.mixing.ScalapbMixingSuite`.
  */
class ResolutionSuite extends munit.FunSuite:

  /** How the compiler starts every implicit-not-found message. Asserting on it
    * is what keeps the negative cases honest: `compileErrors` is non-empty for
    * any error at all, so a snippet with a typo in it would satisfy a bare
    * `nonEmpty` and prove nothing. Asserting instead that the text mentions
    * `Codec` would be no better, because munit echoes the offending line into
    * its output and the word is therefore in there whatever went wrong.
    *
    * The wording belongs to the compiler and not to the bridge, so a Scala
    * upgrade may well have to change this line — the jsoniter, circe and borer
    * suites pin the same string and would all move together. Checked against
    * 3.3.8, where a given whose `A <: GeneratedMessage` bound is not met is
    * reported as an ordinary missing given, followed by the candidate that was
    * found and rejected.
    */
  private val noGiven = "No given instance of type"

  test("all three resolve, and all three are the one instance") {
    // Stronger than "all three resolve": all three arrive as the one class
    // the given builds, so the two directions of a type cannot be answered by
    // instances that disagree about anything — today there is nothing for them
    // to disagree about, and this is the test that would notice if the bridge
    // ever grew a setting. With one given and one instance class that is
    // almost a tautology, which is the point, and what would stop being true
    // the moment the bridge grew a second given.
    assert(summon[Encoder[PNested, Proto]].isInstanceOf[ScalapbCodec[?]])
    assert(summon[Decoder[PNested, Proto]].isInstanceOf[ScalapbCodec[?]])
    assert(summon[Codec[PNested, Proto]].isInstanceOf[ScalapbCodec[?]])
  }

  test("a type that is not a generated message resolves as nothing") {
    assert(
      compileErrors("summon[Encoder[NotAMessage, Proto]]").contains(noGiven)
    )
    assert(
      compileErrors("summon[Decoder[NotAMessage, Proto]]").contains(noGiven)
    )
    assert(compileErrors("summon[Codec[NotAMessage, Proto]]").contains(noGiven))
  }

  test("a generated enum is not a message, and resolves as nothing") {
    // The interesting negative case for this bridge, because `PKind` is not a
    // stranger: protoc generated it, out of the same file as `PNested` and
    // into the same package, so it is the type a user is most likely to try
    // this on. It is not a `GeneratedMessage` and the bound turns it down.
    //
    // That is protobuf's shape and not a gap in the bridge. An enum on the
    // wire is a varint inside some message's field — it carries no tag, no
    // length and no framing of its own — so there is nothing "encode a PKind"
    // could produce that "decode a PKind" would find again. ScalaPB gives it
    // `GeneratedEnum`, which is a different thing from a message, and the
    // bridge refuses rather than inventing a framing protobuf does not have.
    //
    // `protos.PKind` is written out rather than imported because the snippet
    // is type-checked and then thrown away: an import that only this string
    // used is reported as unused, and `-Wunused` is an error in this build.
    assert(
      compileErrors("summon[Codec[protos.PKind, Proto]]").contains(noGiven)
    )
  }
