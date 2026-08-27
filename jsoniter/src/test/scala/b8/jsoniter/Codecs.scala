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

import b8.laws.Flat
import b8.laws.Kind
import b8.laws.Nested
import b8.laws.Shape

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

/** The jsoniter codecs for the shared fixtures, generated once for the whole
  * test module.
  *
  * The fixtures live in `b8-laws` and carry no `derives` clause on purpose, so
  * their jsoniter codecs are made here from the outside. `JsonCodecMaker.make`
  * runs at compile time and inlines the whole shape into one class, which is
  * why `Nested` needs no codec for `Flat`, `Kind` or `Shape` to already exist —
  * a single `make` covers everything reachable from the type.
  *
  * The givens are anonymous, as everywhere else in this build. An anonymous
  * given takes its name from the type it produces, and these four produce four
  * different types, so nothing collides.
  *
  * The wire shape the default `CodecMakerConfig` produces is worth writing
  * down, because it is not the obvious one and every other suite in this
  * package reads bytes that follow it:
  *
  *   - A sum is an object with a discriminator field named `type`, even when
  *     the case carries nothing. `Kind.Beta` is `{"type":"Beta"}`, not the bare
  *     string `"Beta"`, and `Shape.Rect(16, 9)` is
  *     `{"type":"Rect","width":16.0,"height":9.0}`.
  *   - `transientNone` and `transientEmpty` are on, so a `None` field and an
  *     empty `List`, `Vector` or `Map` are left out of the object entirely
  *     rather than written as `null`, `[]` or `{}`. Decoding treats those
  *     fields as optional, so the round trip survives — the encoding is
  *     shorter, not lossier.
  *   - Everything else is what a reader would guess: a `Map[String, String]` is
  *     a plain object, and a `Double` prints as the shortest decimal that reads
  *     back to the same bits.
  */
object Codecs:

  given JsonValueCodec[Flat] = JsonCodecMaker.make
  given JsonValueCodec[Kind] = JsonCodecMaker.make
  given JsonValueCodec[Shape] = JsonCodecMaker.make
  given JsonValueCodec[Nested] = JsonCodecMaker.make
