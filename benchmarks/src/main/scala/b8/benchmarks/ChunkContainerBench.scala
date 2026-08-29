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
import fs2.Chunk
import org.openjdk.jmh.annotations.*

/** What the target container costs, for fs2's `Chunk`.
  *
  * The chunk half of `ContainerBench`, and it is meant to be read the same way.
  * The same codec, the same fixture and the same default `SinkPool` on both
  * sides of each pair; the only difference is which package the extension
  * method came from. `b8.array` hands back the array `Encoder.encode` produced
  * and wraps it in a `ByteSource` on the way in. `b8.chunk` puts a
  * `Chunk.array` around that same array on the way out, and asks it for its
  * backing slice again on the way in.
  *
  * So the expected answer is "nothing measurable": the chunk pair should land
  * within noise of the array pair. A gap that is not noise means a copy crept
  * in, and there are exactly two places for one to come from:
  *
  *   - `Chunk.array` stopped being a view. For two bytes and up it is a
  *     `Chunk.ArraySlice` holding the array by reference, so a copy here would
  *     be fs2 changing under us rather than b8 doing anything different.
  *   - `asByteSource` lost the type argument on `toArraySlice`. That method is
  *     `toArraySlice[O2 >: O]` and takes its `ClassTag` from `O2`; an `O2` the
  *     compiler widened to `Any` misses the array-backed identity path and
  *     boxes every byte into a fresh `Array[Object]`. There is no warning and
  *     no compile error for that — only a decode number that moved, which is
  *     what this pair is here to show.
  *
  * Both are a kilobyte per call, which is why the fixture is `nested1` and not
  * something small. There is a second reason for that choice: `Chunk.array`
  * keeps the array it is handed only from two bytes on, answering a one-element
  * array with a `Chunk.Singleton` and an empty one with `Chunk.empty`, neither
  * of which holds a reference. A benchmark over a tiny value would therefore be
  * measuring fs2's small-input representations and not the container at all. At
  * 1 KB that floor is nowhere near, and the zero-copy path is the only one
  * either side can take.
  *
  * Neither side pools its sink, so both pay one `ArraySink` per encode. That is
  * common overhead rather than bias, but it does dilute the encode pair: the
  * container's own cost is a single `Chunk.ArraySlice` measured against an
  * allocation that dwarfs it.
  *
  * `-prof gc` prices that exactly, and is the number to reach for when the
  * throughput figures are noisy. The encode pair should differ by one small
  * constant object per operation and the decode pair by nothing at all, since
  * `toArraySlice[Byte]` on an array-backed chunk returns the chunk itself and
  * allocates nothing. A copy on either side would move those figures by the
  * size of the message, which at this fixture is unmistakable.
  *
  * `decodeChunk` reads a `Chunk.array` over the very array `decodeArray` reads,
  * so both decoders see the same bytes and the chunk one takes the zero-copy
  * path.
  *
  * The two imports the extension methods come from are local to the methods
  * that use them, and not at the top of the file: `b8.array.*` and `b8.chunk.*`
  * in one scope make `encode` ambiguous, which is the one-container-per-file
  * rule this benchmark would otherwise have to violate to state its case.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class ChunkContainerBench:

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

  /** The input `decodeChunk` reads: a view over `bytes`, not a copy of it. */
  private val chunk: Chunk[Byte] = Chunk.array(bytes)

  /** Refuses to start a trial where the two containers are not carrying the
    * same message, which is the only thing that makes these numbers comparable.
    */
  @Setup(Level.Trial)
  def check(): Unit =
    println(s"ChunkContainerBench: nested1 encodes to ${bytes.length} bytes")
    // A fixture that had quietly shrunk to a handful of bytes would still
    // produce perfectly stable numbers, and they would mean nothing. Same
    // guard, and same reason, as `JsoniterBench`. It also keeps the fixture
    // clear of the one- and zero-byte inputs `Chunk.array` refuses to hold by
    // reference.
    assert(
      bytes.length > 512 && bytes.length < 2048,
      s"fixture is ${bytes.length} bytes, expected roughly 1 KB"
    )
    assert(
      Arrays.equals(encodeArray, bytes),
      "encodeArray does not reproduce the fixture bytes"
    )
    assert(encodeChunk == chunk, "encodeChunk disagrees with encodeArray")
    assert(
      encodeChunk.size == bytes.length,
      "encodeChunk is not exact-size"
    )
    // The claim the decode pair rests on: `chunk` is a window onto `bytes`
    // rather than a copy of them, so `decodeChunk` hands its decoder the same
    // array `decodeArray` does. If this ever fails, the pair is measuring one
    // decode against one decode plus a memcpy.
    assert(
      chunk.toArraySlice[Byte].values eq bytes,
      "chunk does not share the array decodeArray reads"
    )
    assert(decodeArray == Right(nested1), "decodeArray lost data")
    assert(decodeChunk == Right(nested1), "decodeChunk lost data")

  /** `b8.array`: the array `Encoder.encode` produced, handed straight back. */
  @Benchmark
  def encodeArray: Array[Byte] =
    import b8.array.*
    nested1.encode[Json]

  /** `b8.chunk`: the same array, with a `Chunk.array` around it. */
  @Benchmark
  def encodeChunk: Chunk[Byte] =
    import b8.chunk.*
    nested1.encode[Json]

  /** `b8.array`: one `ByteSource` over the array as it lies. */
  @Benchmark
  def decodeArray: Either[DecodeError, Nested] =
    import b8.array.*
    bytes.decodeAs[Nested, Json]

  /** `b8.chunk`: one `toArraySlice[Byte]`, which gives the chunk back
    * unchanged, and one `ByteSource` over the array it already points at.
    */
  @Benchmark
  def decodeChunk: Either[DecodeError, Nested] =
    import b8.chunk.*
    chunk.decodeAs[Nested, Json]
