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

package b8.vector

import b8.laws.Flat
import b8.laws.Kind
import b8.laws.Nested
import b8.laws.Shape

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

/** jsoniter codecs for the shared fixtures, so that the container can be
  * exercised with a real bridge behind it.
  *
  * The choice of backend is arbitrary and the module under test never learns
  * about it: `b8.vector` names no format and holds no given. jsoniter is here
  * because it is b8's recommended JSON path and because it is the one bridge
  * whose own numbers leave a copy nowhere to hide — if the container added one,
  * this is where it would show.
  */
object Codecs:

  given JsonValueCodec[Flat] = JsonCodecMaker.make
  given JsonValueCodec[Kind] = JsonCodecMaker.make
  given JsonValueCodec[Shape] = JsonCodecMaker.make
  given JsonValueCodec[Nested] = JsonCodecMaker.make
