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

/** Supplies the intermediate buffer `Encoder.encode` writes into.
  *
  * b8 never pools behind the user's back: the default `given` allocates a fresh
  * sink per call. Reuse is opt-in through `SinkPool.threadLocal`, and only safe
  * where every borrowed sink is released on the thread that borrowed it.
  */
trait SinkPool:

  /** Returns a sink with position 0 and at least `hint` bytes of capacity. */
  def borrow(hint: Int): ArraySink

  /** Hands a sink borrowed from this pool back. The caller must not touch it
    * afterwards.
    */
  def release(sink: ArraySink): Unit

object SinkPool:

  /** Allocates a fresh sink per borrow; `release` does nothing. */
  val none: SinkPool = new SinkPool:
    def borrow(hint: Int): ArraySink = new ArraySink(hint)
    def release(sink: ArraySink): Unit = ()

  /** The pool in scope unless the caller brings their own: no pooling. */
  given default: SinkPool = none

  /** One reusable sink per thread.
    *
    * A sink that grew past `maxRetain` while in use is dropped on release
    * rather than kept alive, so a single outsized message does not pin a large
    * array to a thread. A nested borrow — one taken while the thread's sink is
    * still out — gets a fresh, unpooled sink, which keeps encoders that encode
    * inside an encode correct.
    *
    * @param maxRetain
    *   largest capacity, in bytes, still worth keeping around
    */
  def threadLocal(maxRetain: Int = 64 * 1024): SinkPool =
    new ThreadLocalSinkPool(maxRetain)

/** Holds the idle sink of each thread. A `null` slot means the thread's sink is
  * currently out on loan.
  */
private final class ThreadLocalSinkPool(maxRetain: Int) extends SinkPool:

  private val idle = new ThreadLocal[ArraySink]

  def borrow(hint: Int): ArraySink =
    val sink = idle.get()
    if sink eq null then new ArraySink(math.max(hint, 256))
    else
      idle.set(null)
      sink.reset()
      sink.ensure(hint)
      sink

  def release(sink: ArraySink): Unit =
    if sink.capacity <= maxRetain then
      sink.reset()
      idle.set(sink)
