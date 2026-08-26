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
import b8.Format
import b8.array.*
import b8.laws.Fixtures
import b8.laws.Flat
import b8.laws.Kind
import b8.laws.Nested
import b8.laws.Shape

import java.util.Arrays
import java.util.concurrent.TimeUnit

import io.bullet.borer.Cbor
import io.bullet.borer.Json
import io.bullet.borer.derivation.MapBasedCodecs.deriveAllCodecs
import io.bullet.borer.derivation.MapBasedCodecs.deriveCodec
import org.openjdk.jmh.annotations.*

/** What the b8 facade costs on top of a bare borer call, in both formats.
  *
  * The pairs to read are `cborEncodeDirect` against `cborEncodeB8` and the
  * three others like it. Within a pair the same borer codec writes or reads the
  * same bytes, and on the b8 side borer writes straight into the sink's array
  * through a custom `Output` — so there is no tree, no intermediate buffer and
  * no second parser on either side that could muddy the comparison. What is
  * left over is the facade.
  *
  * Both encode benchmarks hand back an exact-size `Array[Byte]`, `toByteArray`
  * on the direct side and the array facade on the b8 side. So unlike the circe
  * pair, where the direct call returns a buffer with slack, these two numbers
  * are comparable without any allowance.
  *
  * All eight methods return their result rather than dropping it, which is what
  * keeps JMH from folding the work away. No `Blackhole` anywhere: mixing the
  * two styles would put a consume call on one side of a comparison and not on
  * the other.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class BorerBench:

  private val nested1: Nested = Fixtures.nested1

  // The fixtures carry no `derives` clause, so their borer codecs are derived
  // here from the outside. One set serves both formats: borer's codecs describe
  // the shape of a value, and it is `Cbor` or `Json` at the call site that
  // decides which bytes come out. `Shape` needs the `All` variant because its
  // cases carry data and therefore need codecs of their own.
  private given io.bullet.borer.Codec[Flat] = deriveCodec
  private given io.bullet.borer.Codec[Kind] = deriveCodec
  private given io.bullet.borer.Codec[Shape] = deriveAllCodecs
  private given borerCodec: io.bullet.borer.Codec[Nested] = deriveCodec

  /** The two bridge instances, built once, over the very codec the direct
    * benchmarks run.
    *
    * `val`s on purpose: `b8.borer`'s givens take the borer configs, so they are
    * `def`s, and summoning one at the call site would build a fresh codec on
    * every invocation and charge the facade for an allocation no real caller
    * makes.
    */
  private given b8Cbor: Codec[Nested, Format.Cbor] = b8.borer.cbor.codec()
  private given b8Json: Codec[Nested, Format.Json] = b8.borer.json.codec()

  private val cborBytes: Array[Byte] = Cbor.encode(nested1).toByteArray
  private val jsonBytes: Array[Byte] = Json.encode(nested1).toByteArray

  /** Refuses to start a trial that would measure the wrong thing.
    *
    * A bridge that silently dropped a field, or a fixture that shrank to a
    * handful of bytes, would still produce perfectly stable numbers. So the
    * size bands and the agreement of all eight paths are checked once per
    * trial, where a failure is loud and costs no measurement time.
    */
  @Setup(Level.Trial)
  def check(): Unit =
    println(
      s"BorerBench: nested1 encodes to ${cborBytes.length} bytes of CBOR " +
        s"and ${jsonBytes.length} bytes of JSON"
    )
    assert(
      cborBytes.length > 512 && cborBytes.length < 2048,
      s"CBOR fixture is ${cborBytes.length} bytes, expected roughly 1 KB"
    )
    assert(
      jsonBytes.length > 512 && jsonBytes.length < 2048,
      s"JSON fixture is ${jsonBytes.length} bytes, expected roughly 1 KB"
    )
    assert(
      Arrays.equals(cborEncodeDirect, cborBytes),
      "cborEncodeDirect does not reproduce the fixture bytes"
    )
    assert(
      Arrays.equals(cborEncodeB8, cborBytes),
      "cborEncodeB8 disagrees with cborEncodeDirect"
    )
    assert(
      Arrays.equals(jsonEncodeDirect, jsonBytes),
      "jsonEncodeDirect does not reproduce the fixture bytes"
    )
    assert(
      Arrays.equals(jsonEncodeB8, jsonBytes),
      "jsonEncodeB8 disagrees with jsonEncodeDirect"
    )
    assert(cborDecodeDirect == nested1, "cborDecodeDirect lost data")
    assert(cborDecodeB8 == Right(nested1), "cborDecodeB8 lost data")
    assert(jsonDecodeDirect == nested1, "jsonDecodeDirect lost data")
    assert(jsonDecodeB8 == Right(nested1), "jsonDecodeB8 lost data")

  /** borer alone: its own DSL, its own growing output, trimmed to size at the
    * end by `toByteArray`.
    */
  @Benchmark
  def cborEncodeDirect: Array[Byte] =
    Cbor.encode(nested1).toByteArray

  /** The same codec, reached through the array facade and the default
    * `SinkPool` — that is, a fresh sink per call, no pooling.
    */
  @Benchmark
  def cborEncodeB8: Array[Byte] =
    nested1.encode[Format.Cbor]

  /** borer alone. `.value` is part of the measurement because the b8 side also
    * has to look inside a result before it can hand the value back.
    */
  @Benchmark
  def cborDecodeDirect: Nested =
    Cbor.decode(cborBytes).to[Nested].value

  /** The same codec over the same bytes, through the array facade. The extra
    * work against `cborDecodeDirect` is a `ByteSource`, an `Either` and the
    * translation of borer's failure into a `DecodeError`.
    */
  @Benchmark
  def cborDecodeB8: Either[DecodeError, Nested] =
    cborBytes.decodeAs[Nested, Format.Cbor]

  @Benchmark
  def jsonEncodeDirect: Array[Byte] =
    Json.encode(nested1).toByteArray

  @Benchmark
  def jsonEncodeB8: Array[Byte] =
    nested1.encode[Format.Json]

  @Benchmark
  def jsonDecodeDirect: Nested =
    Json.decode(jsonBytes).to[Nested].value

  /** The JSON bridge decodes with `maxNumberAbsExponent = 999` where borer's
    * own default is 64. That raises no work per value — the limit is only
    * consulted when a number carries an exponent — so this number stays
    * comparable to `jsonDecodeDirect`.
    */
  @Benchmark
  def jsonDecodeB8: Either[DecodeError, Nested] =
    jsonBytes.decodeAs[Nested, Format.Json]
