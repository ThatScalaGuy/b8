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

package b8.stream

import b8.laws.Flat
import b8.laws.Kind
import b8.laws.Nested
import b8.laws.Shape

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

/** jsoniter codecs for the shared fixtures, so that this module can be
  * exercised with a real bridge behind it.
  *
  * The choice of backend is arbitrary and the code under test never learns
  * about it: neither `b8.chunk` nor `b8.stream` names a format or holds a
  * given. jsoniter is here because it is b8's recommended JSON path.
  */
object Codecs:

  given JsonValueCodec[Flat] = JsonCodecMaker.make
  given JsonValueCodec[Kind] = JsonCodecMaker.make
  given JsonValueCodec[Shape] = JsonCodecMaker.make
  given JsonValueCodec[Nested] = JsonCodecMaker.make
