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
import b8.laws.Fixtures
import b8.laws.Flat
import b8.laws.Kind
import b8.laws.Nested
import b8.laws.Shape

import java.util.Arrays
import java.util.concurrent.TimeUnit

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import org.openjdk.jmh.annotations.*
import scodec.bits.ByteVector

/** What the target container costs.
  *
  * The same codec, the same fixture and the same default `SinkPool` on both
  * sides of each pair; the only difference is which package the extension
  * method came from. `b8.array` hands back the array `Encoder.encode` produced
  * and wraps it in a `ByteSource` on the way in. `b8.vector` puts a
  * `ByteVector.view` around that same array on the way out, and unwraps a
  * `ByteBuffer` back into the same array on the way in.
  *
  * So the expected answer is "nothing measurable": the vector pair should land
  * within noise of the array pair. A gap that is not noise means a copy crept
  * in — `ByteVector(...)` instead of `ByteVector.view(...)`, which copies the
  * message outright, or `toByteBuffer` instead of `toByteBufferUnsafe`, which
  * hands back a read-only buffer that `ByteSource` then has to copy because it
  * cannot reach its array. Either way a kilobyte per call.
  *
  * Neither side pools its sink, so both pay one `ArraySink` per encode. That is
  * common overhead rather than bias, but it does dilute the encode pair: the
  * container's own cost is three small objects (`Chunk`, `View`, `AtArray`)
  * measured against an allocation that dwarfs them.
  *
  * `-prof gc` prices that exactly, and is the number to reach for when the
  * throughput figures are noisy: the encode pair differs by 72 B/op, which is
  * those three objects and nothing else, and the decode pair by zero, because
  * escape analysis scalar-replaces the `ByteBuffer` that `asByteSource` builds.
  * A copy would move those figures by the size of the message.
  *
  * `decodeVector` reads a `ByteVector.view` over the very array `decodeArray`
  * reads, so both decoders see the same bytes and the vector one takes the
  * zero-copy path through `toByteBufferUnsafe`.
  *
  * The two imports the extension methods come from are local to the methods
  * that use them, and not at the top of the file: `b8.array.*` and
  * `b8.vector.*` in one scope make `encode` ambiguous, which is the
  * one-container-per-file rule this benchmark would otherwise have to violate
  * to state its case.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class ContainerBench:

  private val nested1: Nested = Fixtures.nested1

  // The fixtures carry no `derives` clause, so their jsoniter codecs are made
  // here from the outside, exactly as in `JsoniterBench`.
  private given JsonValueCodec[Flat] = JsonCodecMaker.make
  private given JsonValueCodec[Kind] = JsonCodecMaker.make
  private given JsonValueCodec[Shape] = JsonCodecMaker.make
  private given JsonValueCodec[Nested] = JsonCodecMaker.make

  /** Bound once, for the reason `JsoniterBench` binds it: the bridge's given is
    * a method, so summoning it per call would charge both containers for a
    * fresh codec and a size hint that never settles.
    */
  private given b8Codec: Codec[Nested, Json] = b8.jsoniter.codec()

  private val bytes: Array[Byte] = encodeArray

  /** The input `decodeVector` reads: a view over `bytes`, not a copy of it. */
  private val vector: ByteVector = ByteVector.view(bytes)

  /** Refuses to start a trial where the two containers are not carrying the
    * same message, which is the only thing that makes these numbers comparable.
    */
  @Setup(Level.Trial)
  def check(): Unit =
    println(s"ContainerBench: nested1 encodes to ${bytes.length} bytes")
    // A fixture that had quietly shrunk to a handful of bytes would still
    // produce perfectly stable numbers, and they would mean nothing. Same
    // guard, and same reason, as `JsoniterBench`.
    assert(
      bytes.length > 512 && bytes.length < 2048,
      s"fixture is ${bytes.length} bytes, expected roughly 1 KB"
    )
    assert(
      Arrays.equals(encodeArray, bytes),
      "encodeArray does not reproduce the fixture bytes"
    )
    assert(encodeVector == vector, "encodeVector disagrees with encodeArray")
    assert(
      encodeVector.size == bytes.length.toLong,
      "encodeVector is not exact-size"
    )
    assert(decodeArray == Right(nested1), "decodeArray lost data")
    assert(decodeVector == Right(nested1), "decodeVector lost data")

  /** `b8.array`: the array `Encoder.encode` produced, handed straight back. */
  @Benchmark
  def encodeArray: Array[Byte] =
    import b8.array.*
    nested1.encode[Json]

  /** `b8.vector`: the same array, with a `ByteVector.view` around it. */
  @Benchmark
  def encodeVector: ByteVector =
    import b8.vector.*
    nested1.encode[Json]

  /** `b8.array`: one `ByteSource` over the array as it lies. */
  @Benchmark
  def decodeArray: Either[DecodeError, Nested] =
    import b8.array.*
    bytes.decodeAs[Nested, Json]

  /** `b8.vector`: one `toByteBufferUnsafe` and one `ByteSource` over the array
    * the view already points at.
    */
  @Benchmark
  def decodeVector: Either[DecodeError, Nested] =
    import b8.vector.*
    vector.decodeAs[Nested, Json]
