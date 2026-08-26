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

import java.nio.BufferOverflowException
import java.nio.ByteBuffer

class ByteBufferSinkSuite extends munit.FunSuite:

  test("writes land in the caller's buffer") {
    val bb = ByteBuffer.allocate(8)
    val sink = ByteBufferSink(bb)

    sink.write(1.toByte)
    sink.write(Array[Byte](2, 3, 4))
    sink.write(Array[Byte](9, 5, 9), 1, 1)
    sink.asOutputStream.write(6)

    assertEquals(bb.position(), 6)
    assertEquals(bb.array().take(6).toList, List[Byte](1, 2, 3, 4, 5, 6))
  }

  test("the buffer never grows: overflow surfaces as BufferOverflowException") {
    val sink = ByteBufferSink(ByteBuffer.allocate(2))
    sink.write(1.toByte)
    sink.write(2.toByte)

    intercept[BufferOverflowException](sink.write(3.toByte))
    intercept[BufferOverflowException](
      ByteBufferSink(ByteBuffer.allocate(2)).write(Array[Byte](1, 2, 3))
    )
  }

  test("asOutputStream is allocated once") {
    val sink = ByteBufferSink(ByteBuffer.allocate(8))
    assert(sink.asOutputStream eq sink.asOutputStream)
  }
