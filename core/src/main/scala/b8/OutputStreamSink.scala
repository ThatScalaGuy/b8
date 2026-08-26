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

package b8

import java.io.OutputStream

/** Pass-through sink over an `OutputStream`.
  *
  * Adds no buffering of its own — wrap the stream in a
  * `java.io.BufferedOutputStream` if the encoder writes many small pieces.
  * `asOutputStream` hands back the very stream that was passed in.
  */
final class OutputStreamSink(os: OutputStream) extends ByteSink:

  def write(b: Byte): Unit = os.write(b.toInt)

  def write(src: Array[Byte], off: Int, len: Int): Unit =
    os.write(src, off, len)

  def asOutputStream: OutputStream = os
