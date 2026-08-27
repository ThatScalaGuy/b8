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

import b8.ByteSource
import b8.Codec
import b8.DecodeError
import b8.Format.Json
import b8.laws.Fixtures
import b8.laws.Flat
import b8.laws.Kind
import b8.laws.Nested
import b8.laws.Shape

import java.util.concurrent.TimeUnit

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import io.bullet.borer.derivation.MapBasedCodecs
import io.circe.generic.semiauto
import org.openjdk.jmh.annotations.*

/** The three JSON backends against each other, all of them behind b8.
  *
  * This one measures nothing about the facade. Every one of the six methods
  * goes through a b8 `Codec`, over the same fixture, into the same `ArraySink`
  * from the same default `SinkPool`, so the facade appears identically in all
  * six numbers and what is left is the backends. It is here to answer "which
  * one should I put in this service", not "what does b8 cost".
  *
  * Read the numbers with the printed sizes next to them, because the three
  * backends do not produce the same JSON. They disagree about how to spell a
  * Scala 3 enum — `Kind.Beta` is `{"type":"Beta"}` to jsoniter, the bare string
  * `"Beta"` to borer and `{"Beta":{}}` to circe — about whether a present
  * `Option` is the value itself or a one-element array, and about whether an
  * empty collection is written at all. So the payloads differ in length and in
  * the number of tokens, and the throughputs are not strictly like-for-like.
  * They are close enough to tell a fast backend from a slow one and no closer.
  *
  * All six methods return their result rather than dropping it, which is what
  * keeps JMH from folding the work away.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class JsonBackendsBench:

  private val nested1: Nested = Fixtures.nested1

  // Each backend's instances live in their own object. Three sets of codecs
  // for the same four fixture types cannot share a scope — and neither can
  // three `Codec[Nested, Json]`, which is why the bridges below are plain
  // named `val`s and every call site names the one it means.

  /** jsoniter's codecs, from the inline macro with its default config. */
  private object JsoniterCodecs:
    given JsonValueCodec[Flat] = JsonCodecMaker.make
    given JsonValueCodec[Kind] = JsonCodecMaker.make
    given JsonValueCodec[Shape] = JsonCodecMaker.make
    given JsonValueCodec[Nested] = JsonCodecMaker.make

  /** borer's codecs. `Shape` needs `deriveAllCodecs` because its cases carry
    * data and therefore need codecs of their own; `Kind`, whose cases are bare
    * singletons, needs `deriveCodec` and fails under the `All` variant. The
    * same split as `b8.borer`'s own test codecs, and for the same reason.
    */
  private object BorerCodecs:
    given io.bullet.borer.Codec[Flat] = MapBasedCodecs.deriveCodec
    given io.bullet.borer.Codec[Kind] = MapBasedCodecs.deriveCodec
    given io.bullet.borer.Codec[Shape] = MapBasedCodecs.deriveAllCodecs
    given io.bullet.borer.Codec[Nested] = MapBasedCodecs.deriveCodec

  /** circe's codecs, semi-automatic so that the derivation happens once here
    * and not again at every use site.
    */
  private object CirceCodecs:
    given io.circe.Codec[Flat] = semiauto.deriveCodec
    given io.circe.Codec[Kind] = semiauto.deriveCodec
    given io.circe.Codec[Shape] = semiauto.deriveCodec
    given io.circe.Codec[Nested] = semiauto.deriveCodec

  // The bridges. The import that supplies a backend's instances is confined to
  // the body that builds its codec, so no two backends are ever in scope at
  // once and there is nothing for the compiler to pick between.

  private val jsoniterCodec: Codec[Nested, Json] =
    import JsoniterCodecs.given
    b8.jsoniter.codec()

  private val borerCodec: Codec[Nested, Json] =
    import BorerCodecs.given
    b8.borer.json.codec()

  private val circeCodec: Codec[Nested, Json] =
    import CirceCodecs.given
    b8.circe.codec()

  // Three payloads, not one: a backend has to read back what it wrote, and a
  // shared array would hand two of them somebody else's spelling of `Kind`.
  private val jsoniterBytes: Array[Byte] = jsoniterCodec.encode(nested1)
  private val borerBytes: Array[Byte] = borerCodec.encode(nested1)
  private val circeBytes: Array[Byte] = circeCodec.encode(nested1)

  /** Refuses to start a trial that would measure the wrong thing.
    *
    * A backend that silently dropped a field would still produce perfectly
    * stable — and flattering — numbers, so every decode path is run once per
    * trial, where a failure is loud and costs no measurement time. The three
    * sizes are printed because the comparison cannot be read without them.
    */
  @Setup(Level.Trial)
  def check(): Unit =
    println(
      s"JsonBackendsBench: nested1 encodes to ${jsoniterBytes.length} bytes " +
        s"with jsoniter, ${borerBytes.length} with borer and " +
        s"${circeBytes.length} with circe"
    )
    assert(jsoniterDecode == Right(nested1), "jsoniterDecode lost data")
    assert(borerDecode == Right(nested1), "borerDecode lost data")
    assert(circeDecode == Right(nested1), "circeDecode lost data")

  @Benchmark
  def jsoniterEncode: Array[Byte] =
    jsoniterCodec.encode(nested1)

  @Benchmark
  def jsoniterDecode: Either[DecodeError, Nested] =
    jsoniterCodec.decode(ByteSource(jsoniterBytes))

  @Benchmark
  def borerEncode: Array[Byte] =
    borerCodec.encode(nested1)

  @Benchmark
  def borerDecode: Either[DecodeError, Nested] =
    borerCodec.decode(ByteSource(borerBytes))

  @Benchmark
  def circeEncode: Array[Byte] =
    circeCodec.encode(nested1)

  @Benchmark
  def circeDecode: Either[DecodeError, Nested] =
    circeCodec.decode(ByteSource(circeBytes))
