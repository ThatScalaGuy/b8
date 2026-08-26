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

package b8.borer.cbor

import b8.ByteSink
import b8.Encoder
import b8.Format
import b8.borer.internal.SinkOutput

import io.bullet.borer.Cbor

/** Encodes `A` as CBOR by letting borer render straight into the sink.
  *
  * Nothing is buffered in between: borer writes through an `Output`, and the
  * bridge hands it one that is the sink. No `toByteArray`, no `ByteBuffer`, no
  * temporary array — which is the whole reason borer is b8's default binary
  * backend.
  *
  * @param config
  *   borer's encoding config; `bufferSize` doubles as b8's `sizeHint`
  */
final class CborEncoder[A](config: Cbor.EncodingConfig)(using
    enc: io.bullet.borer.Encoder[A]
) extends Encoder[A, Format.Cbor]:

  /** borer's own `bufferSize` — "the buffer size used for configuring the
    * respective `Output`" — which is exactly what a size hint is. The bridge
    * never lets borer allocate that buffer, so honouring the number here is
    * what keeps the setting meaningful instead of inert.
    */
  override def sizeHint(a: A): Int = config.bufferSize

  def encodeTo(a: A, out: ByteSink): Unit =
    CborEncoder.encodeTo(a, out, config, enc)

object CborEncoder:

  /** The encode path, shared with `CborCodec` so that neither class carries a
    * second copy of it.
    *
    * `Cbor.writer` rather than the `Cbor.encode(a).to(…)` DSL, deliberately.
    * The DSL's terminal operations catch every `NonFatal` and re-throw it as a
    * `Borer.Error.General`, which would turn a `ByteBufferSink` running out of
    * room into a borer error instead of the `BufferOverflowException` b8
    * promises. `Cbor.writer` builds the same renderer and the same validation
    * wrapper the DSL does, and lets that exception through untouched.
    *
    * `writeEndOfInput` writes no bytes; it triggers the checks that catch an
    * encoder which opened an array and never closed it.
    */
  private[cbor] def encodeTo[A](
      a: A,
      out: ByteSink,
      config: Cbor.EncodingConfig,
      enc: io.bullet.borer.Encoder[A]
  ): Unit =
    Cbor.writer(SinkOutput(out), config).write(a)(using enc).writeEndOfInput()
    ()
