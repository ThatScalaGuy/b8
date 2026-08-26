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
import b8.array.*
import b8.laws.Fixtures
import b8.laws.Flat
import b8.laws.Kind
import b8.laws.Nested
import b8.laws.Shape

import java.nio.ByteBuffer
import java.util.Arrays
import java.util.concurrent.TimeUnit

import io.circe.Printer
import io.circe.generic.semiauto.deriveCodec
import io.circe.jawn.JawnParser
import org.openjdk.jmh.annotations.*

/** What the b8 facade costs on top of a bare circe call.
  *
  * The pairs to read are `encodeDirect` against `encodeB8` and `decodeDirect`
  * against `decodeB8`. Both members of a pair run the same printer instance and
  * the same parser instance over the same ~1 KB fixture, so the difference is
  * the bridge and nothing else — no second codec, no second parser
  * configuration, no different payload.
  *
  * All four methods return their result rather than dropping it, which is what
  * keeps JMH from folding the work away. No `Blackhole` anywhere: mixing the
  * two styles would put a consume call on one side of a comparison and not on
  * the other.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class CirceBench:

  private val nested1: Nested = Fixtures.nested1

  private val printer: Printer = Printer.noSpaces

  /** `new JawnParser` and not `JawnParser()`: the companion only carries
    * `apply` overloads that take arguments. This stands for jawn's own defaults
    * — no limit on the size of a single value, duplicate keys allowed.
    */
  private val parser: JawnParser = new JawnParser

  // The fixtures carry no `derives` clause, so their circe codecs are derived
  // here from the outside. These are the same instances the bridge's own test
  // suites build, which keeps the numbers comparable to the law results.
  private given io.circe.Codec[Flat] = deriveCodec
  private given io.circe.Codec[Kind] = deriveCodec
  private given io.circe.Codec[Shape] = deriveCodec
  private given circeCodec: io.circe.Codec[Nested] = deriveCodec

  /** The bridge instance, built once and handed the very printer and parser the
    * direct benchmarks use.
    *
    * A `val` on purpose: `b8.circe`'s parameterised `given` is a `def`, so
    * summoning it at the call site would build a fresh `CirceCodec` on every
    * invocation and charge the facade for an allocation no real caller makes.
    */
  private given b8Codec: Codec[Nested, Json] = b8.circe.codec(printer, parser)

  private val bytes: Array[Byte] = readAll(
    printer.printToByteBuffer(circeCodec(nested1))
  )

  /** Copies out what a printed buffer actually holds.
    *
    * `printToByteBuffer` returns a heap buffer whose capacity is usually larger
    * than the value in it, so `bb.array()` on its own would hand back trailing
    * garbage — invisible for payloads under ten bytes, wrong for everything
    * else. Only used in `@Setup`, never on a measured path.
    */
  private def readAll(bb: ByteBuffer): Array[Byte] =
    val out = new Array[Byte](bb.remaining())
    bb.get(out)
    out

  /** Refuses to start a trial that would measure the wrong thing.
    *
    * A bridge that silently dropped a field, or a fixture that shrank to a
    * handful of bytes, would still produce perfectly stable numbers. So the
    * size band and the agreement of all four paths are checked once per trial,
    * where a failure is loud and costs no measurement time.
    */
  @Setup(Level.Trial)
  def check(): Unit =
    println(s"CirceBench: nested1 prints to ${bytes.length} bytes")
    assert(
      bytes.length > 512 && bytes.length < 2048,
      s"fixture is ${bytes.length} bytes, expected roughly 1 KB"
    )
    assert(
      Arrays.equals(readAll(encodeDirect), bytes),
      "encodeDirect does not reproduce the fixture bytes"
    )
    assert(
      Arrays.equals(encodeB8, bytes),
      "encodeB8 disagrees with encodeDirect"
    )
    assert(decodeDirect == Right(nested1), "decodeDirect lost data")
    assert(decodeB8 == Right(nested1), "decodeB8 lost data")

  /** circe alone: build the tree, print it into the buffer circe allocated.
    *
    * The result is a `ByteBuffer` with slack at the end, while `encodeB8` hands
    * back an exact-size `Array[Byte]`. That difference is deliberate and is the
    * point of the comparison: the facade promises one array holding the message
    * and nothing else, and the copy and the trim that promise costs are exactly
    * what the gap between these two numbers prices.
    */
  @Benchmark
  def encodeDirect: ByteBuffer =
    printer.printToByteBuffer(circeCodec(nested1))

  /** The same tree and the same printer, reached through the array facade and
    * the default `SinkPool` — that is, a fresh sink per call, no pooling.
    */
  @Benchmark
  def encodeB8: Array[Byte] =
    nested1.encode[Json]

  /** jawn alone. The wrap is part of the measurement because the facade cannot
    * avoid one either: `ByteSource.asByteBuffer` builds a fresh buffer per
    * decode, since jawn moves the position of whatever it is given.
    */
  @Benchmark
  def decodeDirect: Either[io.circe.Error, Nested] =
    parser.decodeByteBuffer[Nested](ByteBuffer.wrap(bytes))

  /** The same parser over the same bytes, through the array facade. The extra
    * work against `decodeDirect` is a `ByteSource`, a second `Either` and the
    * translation of circe's failure into a `DecodeError`.
    */
  @Benchmark
  def decodeB8: Either[DecodeError, Nested] =
    bytes.decodeAs[Nested, Json]
