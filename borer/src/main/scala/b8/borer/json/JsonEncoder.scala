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

package b8.borer.json

import b8.ByteSink
import b8.Encoder
import b8.Format
import b8.borer.internal.SinkOutput

import io.bullet.borer.Json

/** Encodes `A` as JSON by letting borer render straight into the sink.
  *
  * The same borer `Encoder[A]` that drives the CBOR bridge drives this one:
  * borer's instances say what the shape of a value is, not how it is spelled,
  * so one codec covers both formats and only the `Format` tag decides which
  * bytes come out.
  *
  * No AST is built and no `String` is produced on the way — borer writes UTF-8
  * bytes into the sink as it goes, which is what separates this JSON path from
  * the circe one.
  *
  * @param config
  *   borer's encoding config; `bufferSize` doubles as b8's `sizeHint`, and
  *   `indent` turns on pretty printing
  */
final class JsonEncoder[A](config: Json.EncodingConfig)(using
    enc: io.bullet.borer.Encoder[A]
) extends Encoder[A, Format.Json]:

  override def sizeHint(a: A): Int = config.bufferSize

  def encodeTo(a: A, out: ByteSink): Unit =
    JsonEncoder.encodeTo(a, out, config, enc)

object JsonEncoder:

  /** The encode path, shared with `JsonCodec` so that neither class carries a
    * second copy of it.
    *
    * `Json.writer` rather than the `Json.encode(a).to(…)` DSL, deliberately.
    * The DSL's terminal operations catch every `NonFatal` and re-throw it as a
    * `Borer.Error.General`, which would turn a `ByteBufferSink` running out of
    * room into a borer error instead of the `BufferOverflowException` b8
    * promises. `Json.writer` builds the same renderer the DSL does, and lets
    * that exception through untouched.
    */
  private[json] def encodeTo[A](
      a: A,
      out: ByteSink,
      config: Json.EncodingConfig,
      enc: io.bullet.borer.Encoder[A]
  ): Unit =
    Json.writer(SinkOutput(out), config).write(a)(using enc).writeEndOfInput()
    ()
