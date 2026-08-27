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

import b8.ByteSource
import b8.Codec
import b8.Format.Json
import b8.laws.Fixtures
import b8.laws.Flat

import java.nio.charset.StandardCharsets.UTF_8

import com.github.plokhotnyuk.jsoniter_scala.core.ReaderConfig
import com.github.plokhotnyuk.jsoniter_scala.core.WriterConfig

/** Whether the config factory is real, knob by knob.
  *
  * A bridge that takes a config object and quietly ignores it still compiles,
  * so both settings are checked by the behaviour they change: an indention step
  * puts newlines in the output, and switching `checkForEndOfInput` off accepts
  * bytes the default rejects.
  *
  * The three codecs are plain `val`s rather than local givens, and that is not
  * a matter of taste. This suite is inside `b8.jsoniter`, so the package's own
  * `given Codec[A, Json]` is a member of the enclosing scope here; a second
  * given of exactly the same type in the class body would not shadow it, it
  * would compete with it, and every summon in the file would become ambiguous.
  * Holding the codecs by name and calling them directly says which one is meant
  * without asking the compiler.
  */
class ConfigSuite extends munit.FunSuite:

  import Codecs.given

  /** The same settings the package given hands out, built explicitly so the
    * comparisons below have something to compare against.
    */
  private val compact: Codec[Flat, Json] = codec[Flat]()

  private val indented: Codec[Flat, Json] =
    codec[Flat](writer = WriterConfig.withIndentionStep(2))

  private val lenient: Codec[Flat, Json] =
    codec[Flat](reader = ReaderConfig.withCheckForEndOfInput(false))

  test("the default writer produces one line") {
    // Not as obvious as it looks: `flat1` carries a tag with a newline in it,
    // so a raw `\n` byte in the output would mean the string was written
    // unescaped rather than that the writer indents.
    val text = new String(compact.encode(Fixtures.flat1), UTF_8)
    assert(!text.contains("\n"), text)
  }

  test("an indention step adds newlines and changes nothing else") {
    val text = new String(indented.encode(Fixtures.flat1), UTF_8)
    assert(text.contains("\n"), text)
    // Longer, not different. The default decoder reads the indented form back,
    // because the newlines are whitespace between tokens and nothing more.
    assertEquals(
      compact.decode(ByteSource(indented.encode(Fixtures.flat1))),
      Right(Fixtures.flat1)
    )
  }

  test("checkForEndOfInput off accepts what the default rejects") {
    val bytes = compact.encode(Fixtures.flat1) ++ "garbage".getBytes(UTF_8)
    // The default is what makes the bridge honour `Decoder`'s promise that a
    // decode consumes the whole source.
    assert(compact.decode(ByteSource(bytes)).isLeft)
    // And a caller who means to read one value out of a longer stream can say
    // so, and still gets the value rather than a truncated one.
    assertEquals(lenient.decode(ByteSource(bytes)), Right(Fixtures.flat1))
  }
