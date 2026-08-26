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

import java.io.ByteArrayOutputStream

class OutputStreamSinkSuite extends munit.FunSuite:

  test("writes pass straight through to the stream") {
    val os = new ByteArrayOutputStream()
    val sink = OutputStreamSink(os)

    sink.write(1.toByte)
    sink.write(Array[Byte](2, 3, 4))
    sink.write(Array[Byte](9, 5, 9), 1, 1)

    assertEquals(os.toByteArray.toList, List[Byte](1, 2, 3, 4, 5))
  }

  test("asOutputStream hands back the very stream it wraps") {
    val os = new ByteArrayOutputStream()
    assert(OutputStreamSink(os).asOutputStream eq os)
  }
