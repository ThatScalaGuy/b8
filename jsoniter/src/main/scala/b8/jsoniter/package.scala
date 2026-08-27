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

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.core.ReaderConfig
import com.github.plokhotnyuk.jsoniter_scala.core.WriterConfig

/** Builds the b8 JSON codec for `A` from its jsoniter-scala codec.
  *
  * One factory rather than the three the circe and borer bridges offer, because
  * jsoniter has one instance: a `JsonValueCodec[A]` carries `encodeValue` and
  * `decodeValue` together, so there is no such thing as a type jsoniter can
  * write but not read.
  *
  * @param writer
  *   jsoniter's own defaults — compact output, no unicode escaping. The
  *   companion object *is* the default instance, so `WriterConfig` here is a
  *   value and not a type. `WriterConfig.withIndentionStep(2)` pretty-prints.
  * @param reader
  *   jsoniter's own defaults, which include `checkForEndOfInput = true`. That
  *   is what makes the bridge reject bytes left over after a value, as
  *   `Decoder` requires; `ReaderConfig.withCheckForEndOfInput(false)` turns it
  *   off for a caller who means to.
  * @param reentrant
  *   `false` uses jsoniter's thread-pooled reader and writer, which is what
  *   every direct jsoniter user gets and what the `given` below hands out.
  *   `true` allocates a fresh one per call, and is required when the codec for
  *   `A` calls b8 — or jsoniter — again while it is encoding or decoding.
  *   Getting this wrong does not raise anything: the nested call takes the same
  *   pooled writer the outer one is in the middle of using, and the outer
  *   message comes out silently malformed.
  */
def codec[A](
    writer: WriterConfig = WriterConfig,
    reader: ReaderConfig = ReaderConfig,
    reentrant: Boolean = false
)(using JsonValueCodec[A]): Codec[A, Json] =
  JsoniterCodec(writer, reader, reentrant)

/** Every type jsoniter has a codec for gets the JSON bridge, with jsoniter's
  * own settings.
  *
  * One given, and it answers for `Encoder[A, Json]` and `Decoder[A, Json]` as
  * well, since `Codec` extends both.
  *
  * Import it, or another backend's JSON bridge, but not both in one file. The
  * two do not compete and that is the problem: every b8 bridge given is
  * anonymous, so they share a synthesised name, and a second import shadows the
  * first instead of raising an ambiguity. `import b8.jsoniter.given` above
  * `import b8.circe.given` leaves circe answering for `Format.Json`, and the
  * same two lines the other way round leaves jsoniter answering. The only hint
  * is that `-Wunused:all` calls the shadowed import unused — worth recognising
  * as the symptom rather than deleting the line it names.
  */
given [A](using JsonValueCodec[A]): Codec[A, Json] = codec()
