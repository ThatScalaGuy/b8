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

import java.nio.ByteBuffer

class ByteSourceSuite extends munit.FunSuite:

  private val bytes = Array[Byte](0, 1, 2, 3, 4, 5)

  test("bounds are validated on construction") {
    intercept[IllegalArgumentException](ByteSource(bytes, -1, 2))
    intercept[IllegalArgumentException](ByteSource(bytes, 0, -1))
    intercept[IllegalArgumentException](ByteSource(bytes, 0, 7))
    intercept[IllegalArgumentException](ByteSource(bytes, 5, 2))
    intercept[IllegalArgumentException](ByteSource(bytes, 7, 0))

    val whole = ByteSource(bytes)
    assertEquals(whole.offset, 0)
    assertEquals(whole.length, 6)
  }

  test("empty is empty, a window of zero bytes is too") {
    assert(ByteSource.empty.isEmpty)
    assert(ByteSource(bytes, 3, 0).isEmpty)
    assert(!ByteSource(bytes, 3, 1).isEmpty)
  }

  test("every view honours offset and length") {
    val source = ByteSource(bytes, 2, 3)

    val bb = source.asByteBuffer
    assertEquals(bb.remaining(), 3)
    val fromBuffer = new Array[Byte](3)
    bb.get(fromBuffer)
    assertEquals(fromBuffer.toList, List[Byte](2, 3, 4))

    val in = source.asInputStream
    assertEquals(in.available(), 3)
    val fromStream = new Array[Byte](3)
    assertEquals(in.read(fromStream), 3)
    assertEquals(fromStream.toList, List[Byte](2, 3, 4))
    assertEquals(in.read(), -1)

    assertEquals(source.toArray.toList, List[Byte](2, 3, 4))
  }

  test("toArray always copies") {
    val source = ByteSource(bytes)
    val copy = source.toArray
    assert(copy ne bytes)
    copy(0) = 99
    assertEquals(bytes(0), 0.toByte)
  }

  test(
    "a heap buffer is wrapped without copying, position and arrayOffset included"
  ) {
    val bb = ByteBuffer.wrap(bytes)
    bb.position(2)

    val source = ByteSource(bb)
    assert(source.array eq bytes)
    assertEquals(source.offset, 2)
    assertEquals(source.length, 4)
    assertEquals(bb.position(), 2)

    val sliced = ByteBuffer.wrap(bytes, 1, 4).slice()
    sliced.position(1)
    val fromSlice = ByteSource(sliced)
    assert(fromSlice.array eq bytes)
    assertEquals(fromSlice.offset, 2)
    assertEquals(fromSlice.length, 3)
    assertEquals(sliced.position(), 1)
    assertEquals(fromSlice.toArray.toList, List[Byte](2, 3, 4))
  }

  test("a direct buffer is copied and left where it was") {
    val bb = ByteBuffer.allocateDirect(6)
    bb.put(bytes)
    bb.position(2)

    val source = ByteSource(bb)
    assertEquals(source.offset, 0)
    assertEquals(source.length, 4)
    assertEquals(source.toArray.toList, List[Byte](2, 3, 4, 5))
    assertEquals(bb.position(), 2)
  }

  test("a read-only buffer is copied") {
    val bb = ByteBuffer.wrap(bytes).asReadOnlyBuffer()
    bb.position(4)

    val source = ByteSource(bb)
    assert(source.array ne bytes)
    assertEquals(source.toArray.toList, List[Byte](4, 5))
    assertEquals(bb.position(), 4)
  }
