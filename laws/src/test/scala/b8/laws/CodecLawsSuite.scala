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

package b8.laws

import b8.*

import java.nio.charset.StandardCharsets.UTF_8

/** Test-only format: the encoding is the string's UTF-8 bytes, nothing around
  * them.
  */
trait Utf8 extends Format.Text

given Codec[String, Utf8] with
  def encodeTo(a: String, out: ByteSink): Unit = out.write(a.getBytes(UTF_8))
  override def sizeHint(a: String): Int = a.length * 3
  def decodeUnsafe(in: ByteSource): String =
    new String(in.array, in.offset, in.length, UTF_8)

/** Runs the laws against a codec that is known to be correct, so anything red
  * here is a bug in the laws and not in a bridge.
  */
class CodecLawsSuite extends LawsSuite:

  // Raw UTF-8 cannot detect trailing input: appending a byte does not make the
  // input malformed, it makes it a longer string.
  checkAll(CodecLaws[String, Utf8]("utf8.String", trailing = None))
