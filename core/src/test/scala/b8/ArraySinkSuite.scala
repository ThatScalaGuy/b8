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

class ArraySinkSuite extends munit.FunSuite:

  test("capacity doubles until the write fits") {
    val sink = ArraySink(16)
    assertEquals(sink.capacity, 16)

    sink.write(new Array[Byte](17))
    assertEquals(sink.capacity, 32)

    sink.write(new Array[Byte](20))
    assertEquals(sink.capacity, 64)

    sink.write(new Array[Byte](100))
    assertEquals(sink.capacity, 256)
    assertEquals(sink.position, 137)
  }

  test("result is exact-size and independent of the sink's buffer") {
    val sink = ArraySink(64)
    sink.write(Array[Byte](1, 2, 3))

    val out = sink.result()
    assertEquals(out.length, 3)
    assertEquals(out.toList, List[Byte](1, 2, 3))

    out(0) = 9
    assertEquals(sink.buffer(0), 1.toByte)

    sink.buffer(1) = 8
    assertEquals(out(1), 2.toByte)
  }

  test("reset drops the contents but keeps the capacity") {
    val sink = ArraySink(16)
    sink.write(new Array[Byte](100))
    val grown = sink.capacity

    sink.reset()
    assertEquals(sink.position, 0)
    assertEquals(sink.capacity, grown)
    assertEquals(sink.result().length, 0)
  }

  test("ensure never shrinks the buffer") {
    val sink = ArraySink(16)
    sink.ensure(1000)
    val grown = sink.capacity
    assert(grown >= 1000)

    sink.ensure(1)
    assertEquals(sink.capacity, grown)

    sink.ensure(0)
    assertEquals(sink.capacity, grown)
  }

  test("asOutputStream writes into the sink and is allocated once") {
    val sink = ArraySink(2)
    val os = sink.asOutputStream
    assert(os eq sink.asOutputStream)

    os.write(1)
    os.write(Array[Byte](2, 3), 0, 2)
    sink.write(4.toByte)

    assertEquals(sink.result().toList, List[Byte](1, 2, 3, 4))
  }

  test("the fast path writes the same bytes as write") {
    val payload = Array[Byte](10, 20, 30, 40, 50)

    val viaWrite = ArraySink(2)
    viaWrite.write(payload)

    val viaFastPath = ArraySink(2)
    viaFastPath.ensure(payload.length)
    System.arraycopy(
      payload,
      0,
      viaFastPath.buffer,
      viaFastPath.position,
      payload.length
    )
    viaFastPath.advance(payload.length)

    assertEquals(viaFastPath.result().toList, viaWrite.result().toList)
  }

  test("advance past the reserved space is rejected") {
    val sink = ArraySink(16)
    intercept[IllegalArgumentException](sink.advance(17))
    intercept[IllegalArgumentException](sink.advance(-1))
  }
