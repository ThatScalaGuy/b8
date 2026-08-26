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

package b8.circe

import b8.ArraySink
import b8.ByteSink
import b8.Encoder
import b8.Format.Json

import io.circe.Printer

/** Encodes `A` as JSON by printing circe's `Json` tree into the sink.
  *
  * There is one intermediate buffer per call and it is circe's own: `Printer`
  * only offers to build a `ByteBuffer`, never to write into a foreign array.
  * What the bridge controls is what happens next, and there it spends a single
  * copy and never builds a `String`.
  *
  * @param printer
  *   decides spacing, key order and number rendering
  */
final class CirceEncoder[A](printer: Printer)(using
    enc: io.circe.Encoder[A]
) extends Encoder[A, Json]:

  // No `sizeHint` override, so the inherited 256 stands. circe cannot tell how
  // long a value prints without printing it, and a made-up number here would
  // be a guess dressed up as knowledge.

  def encodeTo(a: A, out: ByteSink): Unit =
    CirceEncoder.encodeTo(a, out, printer, enc)

object CirceEncoder:

  /** The encode path, shared with `CirceCodec` so that neither class carries a
    * second copy of it.
    */
  private[circe] def encodeTo[A](
      a: A,
      out: ByteSink,
      printer: Printer,
      enc: io.circe.Encoder[A]
  ): Unit =
    val bb = printer.printToByteBuffer(enc(a))
    val n = bb.remaining()
    out match
      case s: ArraySink =>
        // Fast path: one copy straight into the sink's own array. `ensure` may
        // reallocate, so `buffer` is read after it and never cached across it.
        s.ensure(n)
        bb.get(s.buffer, s.position, n)
        s.advance(n)
      case other if bb.hasArray =>
        // Stream or fixed buffer: hand over the slice the printer filled. Its
        // capacity is usually larger than its limit, so passing `bb.array()`
        // on its own would append trailing garbage — invisible for payloads
        // that happen to fill the buffer exactly.
        other.write(bb.array(), bb.arrayOffset() + bb.position(), n)
      case other =>
        // Defence in depth: `printToByteBuffer` returns a heap buffer today,
        // but that is circe's implementation choice, not a promise made to us.
        // A direct buffer would make the branch above throw, so copy instead.
        val tmp = new Array[Byte](n)
        bb.get(tmp)
        other.write(tmp)
