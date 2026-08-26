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

import b8.laws.Flat
import b8.laws.Kind
import b8.laws.Nested
import b8.laws.Shape

import io.bullet.borer.derivation.MapBasedCodecs.deriveAllCodecs
import io.bullet.borer.derivation.MapBasedCodecs.deriveCodec

/** The borer codecs for the shared fixtures, derived once for the whole test
  * module.
  *
  * The fixtures live in `b8-laws` and carry no `derives` clause on purpose, so
  * their borer codecs are derived here from the outside. One set covers both
  * formats: borer's `Encoder` and `Decoder` describe the shape of a value, not
  * its spelling, and it is `Cbor` or `Json` at the call site that decides which
  * bytes come out.
  *
  * `deriveAllCodecs` for `Shape` and `deriveCodec` for the rest is not a style
  * choice. `Shape` is an enum whose cases carry data, so it needs codecs for
  * the cases as well as for the sum, which is what the `All` variant adds;
  * `deriveCodec[Shape]` alone fails with a missing `Encoder[Shape.Circle]`.
  * `Kind` is the mirror image: its cases are bare singletons, there are no case
  * codecs to derive, and `deriveAllCodecs[Kind]` fails with "this enum does not
  * define any sub-classes".
  *
  * `MapBasedCodecs` rather than `CompactMapBasedCodecs`, because the compact
  * one routes single-field case classes through an array and would leave
  * `Shape.Circle` with a wire shape no other b8 bridge produces.
  */
object Codecs:

  given io.bullet.borer.Codec[Flat] = deriveCodec
  given io.bullet.borer.Codec[Kind] = deriveCodec
  given io.bullet.borer.Codec[Shape] = deriveAllCodecs
  given io.bullet.borer.Codec[Nested] = deriveCodec
