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

package b8.scalapb

import b8.ArraySink
import b8.ByteSink
import b8.Encoder
import b8.Format.Proto

import com.google.protobuf.CodedOutputStream
import scalapb.GeneratedMessage

/** The write half of the ScalaPB bridge for one type.
  *
  * This is the one b8 bridge that never guesses. Every ScalaPB message knows
  * its exact encoded length before a byte is written — `serializedSize` walks
  * the value once and memoizes the answer on the instance — so `sizeHint` is
  * the truth rather than an estimate, the sink is sized once, and there is no
  * retry path anywhere in this file. What is left is precisely the sequence
  * ScalaPB's own `toByteArray` runs, minus its array allocation.
  *
  * Carries no state and takes no settings, so one instance behaves exactly like
  * the next; see `b8.scalapb.codec` for the two protobuf knobs that are
  * deliberately not offered.
  */
final class ScalapbEncoder[A <: GeneratedMessage] extends Encoder[A, Proto]:

  /** Exact, not a guess, and free after the first call on an instance. */
  override def sizeHint(a: A): Int = a.serializedSize

  def encodeTo(a: A, out: ByteSink): Unit =
    ScalapbEncoder.encodeTo(a, out)

object ScalapbEncoder:

  /** The body `ScalapbEncoder` and `ScalapbCodec` both run.
    *
    * The `ArraySink` case is the whole point of the bridge. `serializedSize` is
    * exact, so one `ensure` reserves precisely what the message needs and a
    * `CodedOutputStream` laid over the sink's own array — at the sink's
    * `position`, not at zero, which is what keeps a partly filled sink intact —
    * writes the message where it will finally live. `checkNoSpaceLeft` turns a
    * disagreement between `serializedSize` and `writeTo` into an
    * `IllegalStateException` on the spot instead of a silently short message;
    * it can only fire if a hand-written `GeneratedMessage` computes its own
    * size wrongly. Nothing is copied, nothing is allocated but the coded stream
    * itself, and `advance` is reached exactly once.
    *
    * Any other sink gets protobuf's stream encoder over `asOutputStream`. The
    * buffer is sized the way ScalaPB sizes its own — the message, capped at
    * protobuf's 4 KiB default — so a small message does not pull a 4 KiB array
    * along with it. `flush` is what hands the last partial buffer to the sink;
    * `checkNoSpaceLeft` has no meaning here and would throw if asked.
    */
  private[scalapb] def encodeTo[A <: GeneratedMessage](
      a: A,
      out: ByteSink
  ): Unit =
    out match
      case s: ArraySink =>
        val n = a.serializedSize
        s.ensure(n)
        val cos = CodedOutputStream.newInstance(s.buffer, s.position, n)
        a.writeTo(cos)
        cos.checkNoSpaceLeft()
        s.advance(n)
      case other =>
        val cos = CodedOutputStream.newInstance(
          other.asOutputStream,
          math.min(a.serializedSize, CodedOutputStream.DEFAULT_BUFFER_SIZE)
        )
        a.writeTo(cos)
        cos.flush()
