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

/** Destination for encoded bytes.
  *
  * Implementations are not thread-safe and are written to by exactly one
  * encoder at a time.
  */
trait ByteSink:

  /** Appends a single byte. */
  def write(b: Byte): Unit

  /** Appends `len` bytes from `src`, starting at `off`. */
  def write(src: Array[Byte], off: Int, len: Int): Unit

  /** Appends all of `src`. */
  final def write(src: Array[Byte]): Unit = write(src, 0, src.length)

  /** Adapter for backends that can only write to a stream.
    *
    * Allocated at most once per sink: repeated calls return the same instance.
    * Writes through the adapter and direct writes may be interleaved freely.
    */
  def asOutputStream: OutputStream

/** Bridges `OutputStream` writes onto a `ByteSink`. Neither buffers nor closes
  * anything.
  */
private[b8] final class ByteSinkOutputStream(sink: ByteSink)
    extends OutputStream:
  override def write(b: Int): Unit = sink.write(b.toByte)
  override def write(src: Array[Byte], off: Int, len: Int): Unit =
    sink.write(src, off, len)
