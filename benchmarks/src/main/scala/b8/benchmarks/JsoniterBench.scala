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

package b8.benchmarks

import b8.Codec
import b8.DecodeError
import b8.Format.Json
import b8.SinkPool
import b8.array.*
import b8.laws.Fixtures
import b8.laws.Flat
import b8.laws.Kind
import b8.laws.Nested
import b8.laws.Shape

import java.util.Arrays
import java.util.concurrent.TimeUnit

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray
import com.github.plokhotnyuk.jsoniter_scala.core.writeToArray
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import org.openjdk.jmh.annotations.*

/** What the b8 facade costs on top of a bare jsoniter-scala call.
  *
  * Three encode numbers, and the order they are meant to be read in:
  *
  *   - `encodeDirect` is jsoniter on its own. `writeToArray` renders into the
  *     thread's pooled 32 KB buffer and then trims the result with a single
  *     `Arrays.copyOf`, so it already hands back an exact-size array and
  *     already pays for one copy of the message. That is what makes it
  *     comparable to the two b8 numbers without any allowance — unlike the
  *     circe pair, where the direct call returns a buffer with slack in it.
  *   - `encodeB8` adds a fresh `ArraySink` per call, sized by the bridge's
  *     adaptive hint: the previous encoding's size plus a quarter plus 32
  *     bytes. After the first invocation that hint is right, so what this
  *     measures against `encodeDirect` is one array allocation, not a
  *     re-encode.
  *   - `encodeB8Pooled` runs the same codec over a `SinkPool.threadLocal`,
  *     which hands back the same already-grown sink on every call. That is the
  *     configuration a hot path belongs in, and the gap to `encodeB8` prices
  *     the per-call sink.
  *
  * No intermediate buffer appears on either side of the comparison: the
  * bridge's `ArraySink` fast path calls `writeToSubArray` straight into the
  * sink's own array, exactly as `writeToArray` writes into jsoniter's pooled
  * one. Neither path builds a tree, a `String` or a `ByteBuffer` on the way.
  *
  * All five methods return their result rather than dropping it, which is what
  * keeps JMH from folding the work away. No `Blackhole` anywhere: mixing the
  * two styles would put a consume call on one side of a comparison and not on
  * the other.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class JsoniterBench:

  private val nested1: Nested = Fixtures.nested1

  // The fixtures carry no `derives` clause, so their jsoniter codecs are made
  // here from the outside. `JsonCodecMaker.make` is an inline macro and runs
  // with jsoniter's default `CodecMakerConfig`, which is what a caller who
  // writes `make` and nothing else gets: a discriminator field named `type`,
  // and `None` and empty collections left out of the output entirely.
  private given JsonValueCodec[Flat] = JsonCodecMaker.make
  private given JsonValueCodec[Kind] = JsonCodecMaker.make
  private given JsonValueCodec[Shape] = JsonCodecMaker.make
  private given jsoniterCodec: JsonValueCodec[Nested] = JsonCodecMaker.make

  /** The bridge instance, built once, over the very codec the direct benchmarks
    * run.
    *
    * A `val` on purpose: `b8.jsoniter`'s given takes the two jsoniter configs,
    * so it is a `def`, and summoning it at the call site would build a fresh
    * `JsoniterCodec` on every invocation and charge the facade for an
    * allocation no real caller makes. Building it once also lets the adaptive
    * size hint settle, which is what a long-lived codec in an application does.
    */
  private given b8Codec: Codec[Nested, Json] = b8.jsoniter.codec()

  /** One reusable sink per thread, for `encodeB8Pooled` only. Passed explicitly
    * rather than put in scope, so that `encodeB8` keeps the default unpooled
    * `SinkPool` next to it.
    */
  private val pooled: SinkPool = SinkPool.threadLocal()

  private val bytes: Array[Byte] = writeToArray(nested1)

  /** Refuses to start a trial that would measure the wrong thing.
    *
    * A bridge that silently dropped a field, or a fixture that shrank to a
    * handful of bytes, would still produce perfectly stable numbers. So the
    * size band and the agreement of all five paths are checked once per trial,
    * where a failure is loud and costs no measurement time.
    */
  @Setup(Level.Trial)
  def check(): Unit =
    println(s"JsoniterBench: nested1 encodes to ${bytes.length} bytes")
    assert(
      bytes.length > 512 && bytes.length < 2048,
      s"fixture is ${bytes.length} bytes, expected roughly 1 KB"
    )
    assert(
      Arrays.equals(encodeDirect, bytes),
      "encodeDirect does not reproduce the fixture bytes"
    )
    assert(
      Arrays.equals(encodeB8, bytes),
      "encodeB8 disagrees with encodeDirect"
    )
    assert(
      Arrays.equals(encodeB8Pooled, bytes),
      "encodeB8Pooled disagrees with encodeB8"
    )
    assert(decodeDirect == nested1, "decodeDirect lost data")
    assert(decodeB8 == Right(nested1), "decodeB8 lost data")

  /** jsoniter alone: the thread's pooled writer and buffer, trimmed to size at
    * the end by one `Arrays.copyOf`.
    */
  @Benchmark
  def encodeDirect: Array[Byte] =
    writeToArray(nested1)

  /** The same codec, reached through the array facade and the default
    * `SinkPool` — that is, a fresh sink per call, no pooling.
    */
  @Benchmark
  def encodeB8: Array[Byte] =
    nested1.encode[Json]

  /** The same codec again, over a sink the thread keeps between calls.
    *
    * Called on the codec rather than through the `encode[Json]` extension: the
    * extension takes the encoder and the pool in one `using` clause, so naming
    * the pool there would mean naming the encoder too.
    */
  @Benchmark
  def encodeB8Pooled: Array[Byte] =
    b8Codec.encode(nested1)(using pooled)

  /** jsoniter alone, over the array as it lies. */
  @Benchmark
  def decodeDirect: Nested =
    readFromArray[Nested](bytes)

  /** The same codec over the same bytes, through the array facade. The extra
    * work against `decodeDirect` is a `ByteSource`, an `Either` and the
    * translation of a `JsonReaderException` into a `DecodeError` — and the last
    * of those only on the failing path, which this benchmark never takes.
    */
  @Benchmark
  def decodeB8: Either[DecodeError, Nested] =
    bytes.decodeAs[Nested, Json]
