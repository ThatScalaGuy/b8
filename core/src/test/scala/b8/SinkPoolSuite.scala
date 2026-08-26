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

import java.util.concurrent.atomic.AtomicReference

class SinkPoolSuite extends munit.FunSuite:

  test("none hands out a fresh sink every time") {
    val pool = SinkPool.none
    val first = pool.borrow(128)
    pool.release(first)
    val second = pool.borrow(128)

    assert(first ne second)
    assert(first.capacity >= 128)
  }

  test("the default given is no pooling") {
    assert(summon[SinkPool] eq SinkPool.none)
  }

  test("threadLocal reuses one sink across sequential borrows") {
    val pool = SinkPool.threadLocal()

    val first = pool.borrow(16)
    first.write(Array[Byte](1, 2, 3))
    pool.release(first)

    val second = pool.borrow(16)
    assert(second eq first)
    assertEquals(second.position, 0)
    assert(second.capacity >= 16)
  }

  test("a sink grown past maxRetain is dropped instead of kept") {
    val pool = SinkPool.threadLocal(maxRetain = 1024)

    val first = pool.borrow(16)
    pool.release(first)
    val retained = pool.borrow(16)
    assert(retained eq first)

    retained.write(new Array[Byte](4096))
    assert(retained.capacity > 1024)
    pool.release(retained)

    val afterGrowth = pool.borrow(16)
    assert(afterGrowth ne retained)
  }

  test("a nested borrow gets its own sink") {
    val pool = SinkPool.threadLocal()

    val outer = pool.borrow(16)
    pool.release(outer)

    val reused = pool.borrow(16)
    assert(reused eq outer)

    val nested = pool.borrow(16)
    assert(nested ne reused)
  }

  test("each thread gets its own sink") {
    val pool = SinkPool.threadLocal()

    val here = pool.borrow(16)
    pool.release(here)

    val there = new AtomicReference[ArraySink]()
    val thread = new Thread(() => there.set(pool.borrow(16)))
    thread.start()
    thread.join()

    assert(there.get ne null)
    assert(there.get ne here)
  }
