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
import java.util.Arrays

/** Growable heap buffer with a direct-write fast path. Not thread-safe.
  *
  * Beyond the plain `write` methods, a backend that can serialize straight into
  * a caller-supplied array should use the fast path and skip the intermediate
  * copy:
  *
  * {{{
  * sink.ensure(n)                     // reserve n writable bytes
  * val written = backend.writeTo(sink.buffer, sink.position)
  * sink.advance(written)              // hand the bytes over to the sink
  * }}}
  *
  * `ensure` may reallocate, so `buffer` must be re-read after every call.
  *
  * @param initialCapacity
  *   capacity of the first backing array; negative values are treated as zero
  */
final class ArraySink(initialCapacity: Int = 256) extends ByteSink:

  private var buf: Array[Byte] = new Array[Byte](math.max(initialCapacity, 0))
  private var pos: Int = 0
  private var stream: OutputStream = null

  /** Size of the current backing array. */
  def capacity: Int = buf.length

  /** Number of bytes written so far, and the index the next write lands at. */
  def position: Int = pos

  /** Guarantees at least `n` writable bytes from `position` on. May reallocate:
    * re-read `buffer` afterwards.
    */
  def ensure(n: Int): Unit =
    if buf.length - pos < n then grow(n)

  /** The current backing array. Valid until the next `ensure` or `write`. */
  def buffer: Array[Byte] = buf

  /** Marks `n` bytes starting at `position` as written, after the caller has
    * filled them into `buffer` directly.
    */
  def advance(n: Int): Unit =
    if n < 0 || buf.length - pos < n then
      throw new IllegalArgumentException(
        s"cannot advance $n bytes at position $pos of a $capacity byte buffer"
      )
    pos += n

  /** Exact-size copy of the bytes written so far. The sink stays usable and
    * keeps its contents.
    */
  def result(): Array[Byte] = Arrays.copyOf(buf, pos)

  /** Drops everything written so far. Capacity is kept, so a reused sink stops
    * reallocating.
    */
  def reset(): Unit = pos = 0

  def write(b: Byte): Unit =
    if buf.length - pos < 1 then grow(1)
    buf(pos) = b
    pos += 1

  def write(src: Array[Byte], off: Int, len: Int): Unit =
    ensure(len)
    System.arraycopy(src, off, buf, pos, len)
    pos += len

  def asOutputStream: OutputStream =
    if stream eq null then stream = new ByteSinkOutputStream(this)
    stream

  /** Doubles the capacity until `n` more bytes fit, capped at the largest array
    * the JVM will allocate.
    */
  private def grow(n: Int): Unit =
    val required = pos.toLong + n
    if required > ArraySink.MaxCapacity then
      throw new OutOfMemoryError(
        s"b8 ArraySink would have to grow to $required bytes"
      )
    var newCapacity = math.max(buf.length.toLong, ArraySink.MinCapacity.toLong)
    while newCapacity < required do newCapacity *= 2
    if newCapacity > ArraySink.MaxCapacity then
      newCapacity = ArraySink.MaxCapacity.toLong
    buf = Arrays.copyOf(buf, newCapacity.toInt)

private object ArraySink:
  /** Smallest array the growth loop starts doubling from. */
  final val MinCapacity: Int = 16

  /** Largest array size JVMs allocate reliably; bigger requests fail with
    * `OutOfMemoryError` on most of them anyway.
    */
  final val MaxCapacity: Int = Int.MaxValue - 8
