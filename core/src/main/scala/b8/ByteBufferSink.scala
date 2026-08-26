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
import java.nio.ByteBuffer

/** Writes into a caller-provided buffer, starting at its current position.
  *
  * Never grows: a message that does not fit propagates the buffer's own
  * `java.nio.BufferOverflowException`, so the caller sizes the buffer, not b8.
  */
final class ByteBufferSink(bb: ByteBuffer) extends ByteSink:

  private var stream: OutputStream = null

  def write(b: Byte): Unit =
    bb.put(b)
    ()

  def write(src: Array[Byte], off: Int, len: Int): Unit =
    bb.put(src, off, len)
    ()

  def asOutputStream: OutputStream =
    if stream eq null then stream = new ByteSinkOutputStream(this)
    stream
