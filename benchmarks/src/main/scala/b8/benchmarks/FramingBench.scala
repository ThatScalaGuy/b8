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
import b8.Format.Json
import b8.Format.Proto
import b8.laws.Fixtures
import b8.laws.Flat
import b8.laws.Kind
import b8.laws.Nested
import b8.laws.Shape
import b8.scalapb.ProtoFixtures
import b8.scalapb.protos.PNested
import b8.stream.Framing

import java.util.concurrent.TimeUnit

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fs2.Fallible
import fs2.Stream
import org.openjdk.jmh.annotations.*

/** What each framing costs to write and read back.
  *
  * Informational. Nothing here gates anything: there is no "b8 versus the
  * backend" pair to keep honest, because no backend frames anything — the
  * delimiting is the one part b8 writes itself, so the only thing to compare a
  * framing against is another framing.
  *
  * One operation is `Messages` values pushed through `b8.stream.encode` and
  * straight back through `b8.stream.decode`, so a figure of `n` ops/s is
  * `n * 1000` messages round-tripped per second. Both halves are in every
  * number; a framing that is cheap to write and expensive to scan shows up
  * exactly the same as one that is expensive to write, and this benchmark does
  * not tell those apart.
  *
  * There are two comparisons in here and they do not cross:
  *
  *   - `fixed32` against `newline`, both jsoniter over `Fixtures.nested1`.
  *   - `fixed32Proto` against `varint`, both ScalaPB over
  *     `ProtoFixtures.pNested1`.
  *
  * A Json number read against a Proto number means nothing: the two payloads
  * are not the same size — the banner prints both, and the JSON message is
  * roughly half again the protobuf one — and the codec underneath dominates
  * both. `fixed32Proto` exists precisely so that Varint has a Fixed32 to be
  * measured against on its own payload, rather than against a Fixed32 carrying
  * more bytes through a different codec.
  *
  * What the four numbers should say. Fixed32 and Varint differ only in the
  * header: four bytes written with four stores and read with four loads,
  * against one or two bytes written and read by a loop that runs at most five
  * times. On a message of several hundred bytes that is a handful of
  * instructions either way, so the two belong close together and neither has a
  * structural reason to win. A clear and repeatable gap is therefore a header
  * path worth reading, not a payload effect.
  *
  * Newline is a scan rather than a jump: the decoder has to look at every byte
  * of a message to find the `\n` that ends it, where Fixed32 reads a length and
  * skips ahead. That one extra linear pass is the whole of the difference, and
  * it is cheap only because it runs on the chunk's backing array — one
  * `toArraySlice` per arriving chunk, then a plain `while` over `Array[Byte]`.
  * Written the obvious way instead, through `Chunk#indexWhere`, the same scan
  * walks a boxed iterator and costs several times more per byte: that one
  * change moved Newline from roughly 0.6x Fixed32 to within a few percent of
  * it. So the two belong close together, and a Newline figure that falls back
  * to a fraction of Fixed32 means the scan has gone through the iterator again.
  *
  * What Newline must not do is get relatively *worse* as the message grows:
  * that is the line scanner re-reading bytes it has already looked at — an
  * accumulation that walks the whole pending buffer for every chunk instead of
  * only the newly arrived one, which is O(n^2) in the length of a line and the
  * one real bug this framing invites. The way to see it is to run this class
  * against fixtures of two sizes and compare the Newline-to-Fixed32 ratio, not
  * the absolute figures.
  *
  * Because the codec is in every number, the framing's own share is small and
  * the differences are correspondingly dilute. When the figures are too noisy
  * to read, `-prof gc` separates them — but read the difference between the
  * framings, never one framing's B/op on its own. Every one of them allocates
  * an `ArraySink` and its message-sized array per message, so all four grow
  * with the payload; what distinguishes them is the header, which is bytes. A
  * framing whose B/op exceeds another's by about a whole message is copying
  * one, which is what none of these paths is supposed to do.
  *
  * The pipelines are compiled under `Fallible`, which turns a failed stream
  * into a `Left` instead of an exception, and `@Setup` asserts that all four
  * come back `Right` with exactly the messages they were given. Without that a
  * pipe that failed on its first byte would still report a number, and a very
  * good one. Each method returns the `Either` for the same reason JMH always
  * needs a return: work whose result is dropped is work the JIT is allowed to
  * delete.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class FramingBench:

  /** Messages per operation.
    *
    * Enough that the per-invocation stream setup — two `Pull` loops and a
    * `Compiler` — is amortized rather than measured, and small enough that the
    * decoded `List` an operation returns stays a list and not a heap problem.
    */
  private final val Messages = 1000

  // The fixtures carry no `derives` clause, so their jsoniter codecs are made
  // here from the outside, exactly as in `JsoniterBench`.
  private given JsonValueCodec[Flat] = JsonCodecMaker.make
  private given JsonValueCodec[Kind] = JsonCodecMaker.make
  private given JsonValueCodec[Shape] = JsonCodecMaker.make
  private given JsonValueCodec[Nested] = JsonCodecMaker.make

  /** Both bridges bound once, for the reason the other benchmarks bind theirs:
    * their givens are methods, so summoning them per call would build a fresh
    * codec inside the measured region.
    */
  private given jsonCodec: Codec[Nested, Json] = b8.jsoniter.codec()
  private given protoCodec: Codec[PNested, Proto] = b8.scalapb.codec

  /** The same value `Messages` times, as one chunk.
    *
    * The type annotation is not decoration: `Stream.emits` on its own infers
    * `F = Pure`, and the decode pipe then asks for a `RaiseThrowable[Pure]`
    * that does not exist. Naming `Fallible` here is what makes the whole
    * pipeline typecheck, and it is also what lets `.compile.toList` hand back
    * the `Either` that `@Setup` checks.
    */
  private val values: Stream[Fallible, Nested] =
    Stream.emits(Vector.fill(Messages)(Fixtures.nested1))

  /** The protobuf mirror of `values`, message for message. */
  private val pValues: Stream[Fallible, PNested] =
    Stream.emits(Vector.fill(Messages)(ProtoFixtures.pNested1))

  /** Refuses to start a trial where any of the four pipelines is not actually
    * carrying its messages.
    *
    * A stream that fails on the first frame finishes in no time and reports a
    * throughput to match, so the round trips are run once here — where a
    * failure is loud and costs no measurement time — and checked for both the
    * count and the content. The banner prints how many bytes each framing puts
    * on the wire for one operation, which is the figure the throughputs have to
    * be read against, and the plainest statement of why the Json and Proto
    * numbers are not comparable.
    */
  @Setup(Level.Trial)
  def check(): Unit =
    val jsonFixed =
      sizeOf("fixed32", values.through(b8.stream.encode[Json](Framing.Fixed32)))
    val jsonLines =
      sizeOf("newline", values.through(b8.stream.encode[Json](Framing.Newline)))
    val protoFixed = sizeOf(
      "fixed32Proto",
      pValues.through(b8.stream.encode[Proto](Framing.Fixed32))
    )
    val protoVarint =
      sizeOf("varint", pValues.through(b8.stream.encode[Proto](Framing.Varint)))
    println(
      s"FramingBench: $Messages json messages frame to $jsonFixed bytes " +
        s"fixed32, $jsonLines bytes newline"
    )
    println(
      s"FramingBench: $Messages proto messages frame to $protoFixed bytes " +
        s"fixed32, $protoVarint bytes varint"
    )
    expect("fixed32", Fixtures.nested1, fixed32)
    expect("newline", Fixtures.nested1, newline)
    expect("fixed32Proto", ProtoFixtures.pNested1, fixed32Proto)
    expect("varint", ProtoFixtures.pNested1, varint)

  /** jsoniter over `nested1`, four-byte big-endian length prefixes. */
  @Benchmark
  def fixed32: Either[Throwable, List[Nested]] =
    values
      .through(b8.stream.encode[Json](Framing.Fixed32))
      .through(b8.stream.decode[Json](Framing.Fixed32))
      .compile
      .toList

  /** The same messages as JSON Lines: no header, one `\n` per record, and a
    * scan for it on the way back.
    */
  @Benchmark
  def newline: Either[Throwable, List[Nested]] =
    values
      .through(b8.stream.encode[Json](Framing.Newline))
      .through(b8.stream.decode[Json](Framing.Newline))
      .compile
      .toList

  /** ScalaPB over `pNested1`, with the same fixed prefix as `fixed32` — the
    * baseline `varint` is meant to be read against.
    */
  @Benchmark
  def fixed32Proto: Either[Throwable, List[PNested]] =
    pValues
      .through(b8.stream.encode[Proto](Framing.Fixed32))
      .through(b8.stream.decode[Proto](Framing.Fixed32))
      .compile
      .toList

  /** The same messages behind LEB128 prefixes, which is byte for byte what
    * protobuf's own `writeDelimitedTo` produces.
    */
  @Benchmark
  def varint: Either[Throwable, List[PNested]] =
    pValues
      .through(b8.stream.encode[Proto](Framing.Varint))
      .through(b8.stream.decode[Proto](Framing.Varint))
      .compile
      .toList

  /** Bytes a framed stream occupies, for the banner.
    *
    * Takes the already-piped byte stream rather than a value stream and a
    * `Framing`: every `Framing` case but `Newline` is declared
    * `Framing[Format]`, so a helper that inferred its format from the framing
    * would infer `Format` and then find no `Encoder` for it.
    */
  private def sizeOf(label: String, bytes: Stream[Fallible, Byte]): Long =
    bytes.compile.count match
      case Right(n) => n
      case Left(e)  => throw new AssertionError(s"$label failed to encode", e)

  /** Fails the trial unless `run` came back with exactly the messages it was
    * given.
    */
  private def expect(
      label: String,
      message: Any,
      run: Either[Throwable, List[Any]]
  ): Unit =
    run match
      case Left(e) =>
        throw new AssertionError(s"$label failed the round trip", e)
      case Right(out) =>
        assert(
          out.size == Messages,
          s"$label decoded ${out.size} messages, expected $Messages"
        )
        assert(
          out.forall(_ == message),
          s"$label did not decode the message it framed"
        )
