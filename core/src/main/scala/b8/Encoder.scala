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

/** Turns values of `A` into bytes in format `F`.
  *
  * Encoders are total: for any value of `A` that the type admits, `encodeTo`
  * writes a complete encoding and raises no b8-specific exception. Errors that
  * a backend cannot avoid (a buffer that cannot grow, a failing stream) surface
  * as their own exceptions.
  */
trait Encoder[A, F <: Format]:

  /** Writes the complete encoding of `a` into `out`. */
  def encodeTo(a: A, out: ByteSink): Unit

  /** Best-effort size of the encoding of `a`, used to pre-size the sink. Over-
    * and under-estimates only cost time.
    */
  def sizeHint(a: A): Int = 256

  /** Encodes `a` into a fresh, exact-size array, taking the intermediate buffer
    * from `pool`.
    */
  final def encode(a: A)(using pool: SinkPool): Array[Byte] =
    val sink = pool.borrow(sizeHint(a))
    try
      encodeTo(a, sink)
      sink.result()
    finally pool.release(sink)

object Encoder:
  /** Summons the encoder of `A` for format `F`. */
  def apply[A, F <: Format](using e: Encoder[A, F]): Encoder[A, F] = e
