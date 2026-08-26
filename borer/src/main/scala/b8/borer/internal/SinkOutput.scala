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

package b8.borer.internal

import b8.ArraySink
import b8.ByteSink

import io.bullet.borer.ByteAccess
import io.bullet.borer.Output

/** borer's `Output`, writing straight into an `ArraySink`.
  *
  * This is what makes the bridge zero-copy on the way out: borer renders into
  * the sink's own array, so there is no intermediate buffer between the encoder
  * and the bytes the caller gets back.
  *
  * Every multi-byte write reserves its bytes once and then stores them, rather
  * than reserving per byte. `ensure` may reallocate, so `buffer` and `position`
  * are read *after* it and never held in a field — an adapter that cached
  * either would write into a stale array as soon as the sink grew, which is the
  * single most likely bug in this file and the one `CodecLaws.sinkIndependence`
  * exists to catch.
  *
  * `Result` is `Unit` and `result()` is never called: the encode path runs
  * through `Cbor.writer` / `Json.writer`, which write and stop. The bytes are
  * in the sink, and the sink already belongs to the caller.
  */
private[borer] final class SinkOutput(sink: ArraySink) extends Output:

  type Self = SinkOutput
  type Result = Unit

  // `ArraySink.write` is already the one-byte fast path — reserve, store,
  // advance — so there is nothing to improve on here.
  def writeByte(byte: Byte): SinkOutput =
    sink.write(byte)
    this

  def writeBytes(a: Byte, b: Byte): SinkOutput =
    sink.ensure(2)
    val buf = sink.buffer
    val p = sink.position
    buf(p) = a
    buf(p + 1) = b
    sink.advance(2)
    this

  def writeBytes(a: Byte, b: Byte, c: Byte): SinkOutput =
    sink.ensure(3)
    val buf = sink.buffer
    val p = sink.position
    buf(p) = a
    buf(p + 1) = b
    buf(p + 2) = c
    sink.advance(3)
    this

  def writeBytes(a: Byte, b: Byte, c: Byte, d: Byte): SinkOutput =
    sink.ensure(4)
    val buf = sink.buffer
    val p = sink.position
    buf(p) = a
    buf(p + 1) = b
    buf(p + 2) = c
    buf(p + 3) = d
    sink.advance(4)
    this

  /** `writeShort` and `writeInt` are left to `Output`, whose implementations
    * split the value and call the two- and four-byte `writeBytes` above — one
    * reservation each, which is already all there is to win. `writeLong` is
    * different: it is written as two `writeInt` calls, so it would reserve
    * twice. CBOR renders every `Double` and every 64-bit integer through it.
    */
  override def writeLong(value: Long): SinkOutput =
    sink.ensure(8)
    val buf = sink.buffer
    val p = sink.position
    buf(p) = (value >> 56).toByte
    buf(p + 1) = (value >> 48).toByte
    buf(p + 2) = (value >> 40).toByte
    buf(p + 3) = (value >> 32).toByte
    buf(p + 4) = (value >> 24).toByte
    buf(p + 5) = (value >> 16).toByte
    buf(p + 6) = (value >> 8).toByte
    buf(p + 7) = value.toByte
    sink.advance(8)
    this

  /** Bulk writes — strings, byte strings — arrive here.
    *
    * `toByteArray` is the identity for `Array[Byte]`, which is what both
    * renderers hand over, so this is one reservation and one `arraycopy` with
    * nothing allocated in between.
    */
  def writeBytes[Bytes](bytes: Bytes)(using ba: ByteAccess[Bytes]): SinkOutput =
    val array = ba.toByteArray(bytes)
    sink.write(array, 0, array.length)
    this

  def result(): Unit = ()

object SinkOutput:

  /** The adapter for `out`: the fast path where the sink has an array to write
    * into, the forwarding one everywhere else.
    */
  def apply(out: ByteSink): Output =
    out match
      case s: ArraySink => new SinkOutput(s)
      case other        => new GenericSinkOutput(other)
