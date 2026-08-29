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
import b8.Format.Proto
import b8.SinkPool
import b8.array.*
import b8.scalapb.ProtoFixtures
import b8.scalapb.protos.PNested

import java.util.Arrays
import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*

/** What the b8 facade costs on top of a bare ScalaPB call.
  *
  * This is the one benchmark in the set where the bridge never guesses.
  * `sizeHint` is `serializedSize`, which every ScalaPB message computes exactly
  * and then memoizes on the instance, so the sink is sized once, the message is
  * written once and there is no retry path to fall into. The two encode sides
  * therefore run the same instructions and the difference between them can be
  * named to the allocation:
  *
  *   - `encodeDirect` is `toByteArray`: one array of `serializedSize`, a
  *     `CodedOutputStream` laid over it, `writeTo`, `checkNoSpaceLeft`, hand
  *     the array back. Nothing is copied.
  *   - `encodeB8` runs that same sequence into an `ArraySink`'s own array and
  *     then copies the bytes out of it, because `encode` owes the caller an
  *     exact-size array it owns. Against `encodeDirect` that is one `ArraySink`
  *     plus its array — exactly sized, so it never grows — and one
  *     `Arrays.copyOf` of the message. No re-encode, no intermediate buffer, no
  *     size guess: the gap is those two things and nothing else.
  *   - `encodeB8Pooled` takes the sink from a `SinkPool.threadLocal`, which
  *     hands the same already-grown array back on every call. That removes the
  *     allocation and leaves the copy, so this number should land between the
  *     other two, and what still separates it from `encodeDirect` is one copy
  *     of the message.
  *
  * On the read side `decodeDirect` is ScalaPB's own `parseFrom(Array[Byte])`,
  * which is a `CodedInputStream` over the array and then the generated parser.
  * `decodeB8` reaches the same parser over the same bytes and adds a
  * `ByteSource`, a `Right` and a `catch` for `InvalidProtocolBufferException`
  * that valid input never reaches. All three are constant and none of them
  * touches a field, so the two decode numbers should be close to identical; a
  * visible gap would mean something other than the facade is being measured.
  *
  * All five methods return their result rather than dropping it, which is what
  * keeps JMH from folding the work away. No `Blackhole` anywhere: mixing the
  * two styles would put a consume call on one side of a comparison and not on
  * the other.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class ScalapbBench:

  // The message comes from `b8.scalapb`'s test fixtures rather than being
  // built again here. That is the whole reason this module depends on
  // `scalapb % "compile->test"`: `ProtoFixtures.pNested1` is `Fixtures.nested1`
  // converted field for field, so this benchmark encodes the same value the
  // other bridges are measured on and the same value the ScalaPB laws run.
  private val pNested1: PNested = ProtoFixtures.pNested1

  /** The bridge instance, built once, over the message type the direct
    * benchmarks run.
    *
    * A `val` on purpose: `b8.scalapb`'s given is parameterised — `[A]` plus a
    * `using GeneratedMessageCompanion[A]` — so it is a `def`, and summoning it
    * at the call site would build a fresh `ScalapbCodec` on every invocation
    * and charge the facade for an allocation no real caller makes.
    */
  private given b8Codec: Codec[PNested, Proto] = b8.scalapb.codec

  /** One reusable sink per thread, for `encodeB8Pooled` only. Passed explicitly
    * rather than put in scope, so that `encodeB8` keeps the default unpooled
    * `SinkPool` next to it.
    */
  private val pooled: SinkPool = SinkPool.threadLocal()

  private val bytes: Array[Byte] = pNested1.toByteArray

  /** Refuses to start a trial that would measure the wrong thing.
    *
    * A bridge that silently dropped a field, or a fixture that shrank to a
    * handful of bytes, would still produce perfectly stable numbers. So the
    * size band and the agreement of all five paths are checked once per trial,
    * where a failure is loud and costs no measurement time.
    *
    * The band sits far below the one the JSON benchmarks assert on the same
    * fixture, and that is not a smaller message: protobuf frames each field
    * with a one-byte tag and writes no field names, no quotes and no
    * separators, so the ~1 KB of JSON becomes a few hundred bytes here. Numbers
    * from this class and from `JsoniterBench` are therefore not per-byte
    * comparable; each class is only meant to be read against itself.
    */
  @Setup(Level.Trial)
  def check(): Unit =
    println(s"ScalapbBench: pNested1 encodes to ${bytes.length} bytes")
    assert(
      bytes.length > 400 && bytes.length < 800,
      s"fixture is ${bytes.length} bytes, expected roughly 570"
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
    assert(decodeDirect == pNested1, "decodeDirect lost data")
    assert(decodeB8 == Right(pNested1), "decodeB8 lost data")

  /** ScalaPB alone: one exactly sized array, written once, returned as it
    * stands.
    */
  @Benchmark
  def encodeDirect: Array[Byte] =
    pNested1.toByteArray

  /** The same write, reached through the array facade and the default
    * `SinkPool` — that is, a fresh sink per call, no pooling.
    */
  @Benchmark
  def encodeB8: Array[Byte] =
    pNested1.encode[Proto]

  /** The same codec again, over a sink the thread keeps between calls.
    *
    * Called on the codec rather than through the `encode[Proto]` extension: the
    * extension takes the encoder and the pool in one `using` clause, so naming
    * the pool there would mean naming the encoder too.
    */
  @Benchmark
  def encodeB8Pooled: Array[Byte] =
    b8Codec.encode(pNested1)(using pooled)

  /** ScalaPB alone, over the array as it lies. */
  @Benchmark
  def decodeDirect: PNested =
    PNested.parseFrom(bytes)

  /** The same parser over the same bytes, through the array facade. The extra
    * work against `decodeDirect` is a `ByteSource`, an `Either` and the
    * translation of an `InvalidProtocolBufferException` into a `DecodeError` —
    * and the last of those only on the failing path, which this benchmark never
    * takes.
    */
  @Benchmark
  def decodeB8: Either[DecodeError, PNested] =
    bytes.decodeAs[PNested, Proto]
