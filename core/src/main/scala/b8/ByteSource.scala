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

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Arrays

/** A read-only view of `length` bytes starting at `offset` in `array`.
  *
  * The view never copies and never takes ownership: decoders must not write to
  * `array`, and the caller must not mutate it while a decode is running. Bounds
  * are validated on construction, so decoders can index the window without
  * re-checking.
  *
  * @throws IllegalArgumentException
  *   if the window does not lie inside `array`
  */
final class ByteSource(
    val array: Array[Byte],
    val offset: Int,
    val length: Int
):

  if offset < 0 || length < 0 || offset > array.length - length then
    throw new IllegalArgumentException(
      s"ByteSource($offset, $length) is out of bounds for an array of ${array.length} bytes"
    )

  /** Read-only view of the window. Shares `array`; does not copy. */
  def asByteBuffer: ByteBuffer = ByteBuffer.wrap(array, offset, length)

  /** Stream over the window. Shares `array`; does not copy. */
  def asInputStream: InputStream =
    new ByteArrayInputStream(array, offset, length)

  /** Copy of the viewed bytes. Always a fresh array, never `array` itself. */
  def toArray: Array[Byte] = Arrays.copyOfRange(array, offset, offset + length)

  /** True when the window holds no bytes. */
  def isEmpty: Boolean = length == 0

  override def toString: String =
    s"ByteSource(offset = $offset, length = $length)"

object ByteSource:

  /** A source over zero bytes. */
  val empty: ByteSource = new ByteSource(new Array[Byte](0), 0, 0)

  /** Views all of `array`. */
  def apply(array: Array[Byte]): ByteSource =
    new ByteSource(array, 0, array.length)

  /** Views `length` bytes of `array`, starting at `offset`. */
  def apply(array: Array[Byte], offset: Int, length: Int): ByteSource =
    new ByteSource(array, offset, length)

  /** Views the buffer's remaining bytes.
    *
    * A heap buffer with an accessible array is wrapped without copying,
    * honouring both `arrayOffset` and `position`; direct and read-only buffers
    * are copied, since they expose no array. Either way the buffer's own
    * position is left untouched.
    */
  def apply(bb: ByteBuffer): ByteSource =
    if bb.hasArray then
      new ByteSource(
        bb.array(),
        bb.arrayOffset() + bb.position(),
        bb.remaining()
      )
    else
      val copy = new Array[Byte](bb.remaining())
      bb.duplicate().get(copy)
      new ByteSource(copy, 0, copy.length)
