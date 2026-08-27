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

package b8.jsoniter

import b8.ArraySink
import b8.ByteSource
import b8.Codec
import b8.Format.Json
import b8.laws.Fixtures
import b8.laws.Flat

import java.nio.charset.StandardCharsets.UTF_8

import scala.util.control.NonFatal

import com.github.plokhotnyuk.jsoniter_scala.core.JsonReader
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.core.JsonWriter

/** A record whose second field is written by another b8 codec rather than by
  * the codec that owns it. Contrived on purpose: an envelope carrying an
  * already-serialized payload is the ordinary shape of this, and it is the
  * shape that breaks.
  */
final case class Wrapper(label: String, inner: Flat)

/** What the `reentrant` flag is for.
  *
  * jsoniter keeps one `JsonWriter` and one `JsonReader` per thread and hands
  * the same instance to every `writeToSubArray` and `readFromSubArray` call on
  * that thread. That is why it is fast, and it is fine as long as the calls do
  * not overlap. A codec that calls b8 — or jsoniter — while it is in the middle
  * of encoding overlaps them: the nested call takes the writer the outer one is
  * using, resets its buffer and its write position, and hands it back with the
  * position where the *inner* value ended.
  *
  * Nothing raises. The outer encode carries on writing at the wrong offset into
  * the right array, and what comes back is a byte array of the right kind and
  * the wrong contents. That is the worst failure mode a serializer has, because
  * the message only stops being readable at the far end, and the `*Reentrant`
  * entry points — a fresh reader and writer per call — are the only way out of
  * it.
  */
class ReentrantSuite extends munit.FunSuite:

  import Codecs.given

  /** A hand-written codec for `Wrapper` whose `inner` field is a b8 encoding of
    * a `Flat`, carried as a JSON string.
    *
    * Written out rather than generated because `JsonCodecMaker` would encode
    * `Flat` inline and never call back into b8, which is the case that works
    * anyway. The inner codec is a parameter so the same shape can be built
    * twice, once reentrant and once not.
    */
  private def wrapperCodec(inner: Codec[Flat, Json]): JsonValueCodec[Wrapper] =
    new JsonValueCodec[Wrapper]:
      def nullValue: Wrapper = null

      def encodeValue(x: Wrapper, out: JsonWriter): Unit =
        out.writeObjectStart()
        out.writeKey("label")
        out.writeVal(x.label)
        out.writeKey("inner")
        // The nested call. `inner.encode` borrows a sink of its own and runs a
        // whole second jsoniter encode inside this one.
        out.writeVal(new String(inner.encode(x.inner), UTF_8))
        out.writeObjectEnd()

      def decodeValue(in: JsonReader, default: Wrapper): Wrapper =
        if !in.isNextToken('{') then in.decodeError("expected '{'")
        if in.readKeyAsString() != "label" then
          in.decodeError("expected the label field")
        val label = in.readString(null)
        if !in.isNextToken(',') then in.decodeError("expected ','")
        if in.readKeyAsString() != "inner" then
          in.decodeError("expected the inner field")
        val bytes = in.readString(null).getBytes(UTF_8)
        if !in.isNextToken('}') then in.decodeError("expected '}'")
        // And the nested read, in the middle of the outer one.
        inner.decode(ByteSource(bytes)) match
          case Right(flat) => Wrapper(label, flat)
          case Left(e)     => in.decodeError(e.message)

  private val wrapped = Wrapper("envelope", Fixtures.flat1)

  /** Both levels on their own reader and writer. */
  private val reentrant: Codec[Wrapper, Json] =
    codec[Wrapper](reentrant = true)(using
      wrapperCodec(codec[Flat](reentrant = true))
    )

  /** Both levels on the thread's pooled reader and writer — the default, and
    * the wrong choice for this codec.
    */
  private val pooled: Codec[Wrapper, Json] =
    codec[Wrapper]()(using wrapperCodec(codec[Flat]()))

  test("a reentrant codec survives encoding inside an encode") {
    val bytes = reentrant.encode(wrapped)
    assertEquals(reentrant.decode(ByteSource(bytes)), Right(wrapped))
  }

  test("a reentrant codec survives the retry path too") {
    // Starting the sink small is not what forces the retry — `encodeTo` calls
    // `ensure(sizeHint)` first, so a one-byte sink is grown to the hint before
    // jsoniter ever sees it. What forces it is a hint that is too small, and
    // the hint comes from the last value this codec encoded. So: a fresh codec,
    // a tiny value to pull the hint down, then a large one.
    //
    // Worth the trouble because the retry is the only place the nested encode
    // happens twice on the same writer. A `*Reentrant` entry point that
    // allocated its writer once and cached it would come apart exactly here,
    // and nowhere else in this suite.
    val fresh: Codec[Wrapper, Json] =
      codec[Wrapper](reentrant = true)(using
        wrapperCodec(codec[Flat](reentrant = true))
      )
    val tiny = Wrapper("a", Flat(1L, "", false, 0.0, Nil))
    fresh.encode(tiny)

    val hint = fresh.sizeHint(wrapped)
    val sink = ArraySink(hint)
    fresh.encodeTo(wrapped, sink)

    // The sink grew past what `ensure(hint)` reserved, which only the retry
    // branch does. If this ever fails, the fixtures have drifted close enough
    // in size that the first attempt now fits — the test would be green and
    // vacuous, so it fails loudly instead.
    assert(
      sink.capacity > hint,
      s"no retry: hint $hint already held the ${sink.position} bytes written"
    )
    assertEquals(fresh.decode(ByteSource(sink.result())), Right(wrapped))
  }

  test("the pooled codec loses the message, and says nothing about it") {
    // What is asserted is the damage, not its exact shape. Measured on this
    // fixture the encode returns 382 bytes that begin `{"label":"envelope",
    // "inner":` and continue with a run of spaces — the nested encode reset the
    // shared writer's position, so the outer one resumed past the hole it had
    // left — and the bytes no longer parse. Which of those two symptoms shows
    // up depends on the value and on jsoniter's internals, so the test pins the
    // part that is the caller's problem either way: the round trip is gone.
    //
    // Note what does *not* happen: nothing is thrown on the way out. Whatever
    // this produces would go on the wire.
    val outcome =
      try
        val bytes = pooled.encode(wrapped)
        pooled.decode(ByteSource(bytes)) match
          case Right(w) if w == wrapped => "round-tripped"
          case Right(w)                 => s"decoded as something else: $w"
          case Left(e) => s"rejected its own output: ${e.message.take(60)}"
      catch case NonFatal(e) => s"threw ${e.getClass.getName}"

    assertNotEquals(
      outcome,
      "round-tripped",
      "the pooled writer survived a nested encode — if jsoniter has started " +
        "guarding against re-entry, `reentrant` may no longer be needed"
    )
  }
