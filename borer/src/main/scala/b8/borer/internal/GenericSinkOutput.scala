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

import b8.ByteSink

import io.bullet.borer.ByteAccess
import io.bullet.borer.Output

/** borer's `Output` over any other `ByteSink`.
  *
  * A `ByteBufferSink` or an `OutputStreamSink` exposes no array to render into,
  * so the multi-byte writes forward one byte at a time rather than build a
  * temporary array to hand over — `ByteBuffer.put` and `OutputStream.write` are
  * what a per-byte write costs there anyway, and an allocation per data item
  * would cost more.
  *
  * Bulk writes still go over in one call.
  */
private[borer] final class GenericSinkOutput(sink: ByteSink) extends Output:

  type Self = GenericSinkOutput
  type Result = Unit

  def writeByte(byte: Byte): GenericSinkOutput =
    sink.write(byte)
    this

  def writeBytes(a: Byte, b: Byte): GenericSinkOutput =
    sink.write(a)
    sink.write(b)
    this

  def writeBytes(a: Byte, b: Byte, c: Byte): GenericSinkOutput =
    sink.write(a)
    sink.write(b)
    sink.write(c)
    this

  def writeBytes(a: Byte, b: Byte, c: Byte, d: Byte): GenericSinkOutput =
    sink.write(a)
    sink.write(b)
    sink.write(c)
    sink.write(d)
    this

  def writeBytes[Bytes](bytes: Bytes)(using
      ba: ByteAccess[Bytes]
  ): GenericSinkOutput =
    val array = ba.toByteArray(bytes)
    sink.write(array, 0, array.length)
    this

  def result(): Unit = ()

  /** borer appends the `Output` to the message of an encode-side error. This
    * sink has no position to report, so the class of the sink is what there is
    * to say.
    */
  override def toString: String = s"b8 ${sink.getClass.getSimpleName}"
