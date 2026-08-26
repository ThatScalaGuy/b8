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

package b8.laws

import b8.*

import java.nio.charset.StandardCharsets.UTF_8
import java.util.IdentityHashMap

import org.scalacheck.Arbitrary
import org.scalacheck.Test

/** Decodes correctly, and encodes correctly only into a sink it has never seen.
  *
  * The failure `pooledEquivalent` exists for, and the one shape a law that
  * builds its pool per sample can never observe: with a fresh pool every time,
  * every borrow is a first borrow.
  */
private final class ReuseBlindCodec extends Codec[String, Utf8]:

  // Identity, not equality: two sinks holding the same bytes are still two
  // sinks, and it is the recycled one this has to recognise.
  private val seen: java.util.Set[ArraySink] =
    java.util.Collections.newSetFromMap(
      new IdentityHashMap[ArraySink, java.lang.Boolean]
    )

  def encodeTo(a: String, out: ByteSink): Unit =
    out match
      case s: ArraySink if !seen.add(s) =>
        out.write("CORRUPT".getBytes(UTF_8))
      case _ =>
        out.write(a.getBytes(UTF_8))

  def decodeUnsafe(in: ByteSource): String =
    new String(in.array, in.offset, in.length, UTF_8)

/** Takes the `ArraySink` fast path and writes at index 0 rather than at the
  * sink's position.
  *
  * The natural slip once a backend has the fast path in hand, and invisible to
  * every check that only ever hands it an empty sink — including
  * `pooledEquivalent`, because a pooled sink is reset to position 0 before it
  * is handed back out.
  */
private final class PositionBlindCodec extends Codec[String, Utf8]:

  def encodeTo(a: String, out: ByteSink): Unit =
    val bytes = a.getBytes(UTF_8)
    out match
      case s: ArraySink =>
        s.ensure(bytes.length)
        System.arraycopy(bytes, 0, s.buffer, 0, bytes.length)
        s.advance(bytes.length)
      case other =>
        other.write(bytes)

  def decodeUnsafe(in: ByteSource): String =
    new String(in.array, in.offset, in.length, UTF_8)

/** The laws checked against codecs that are known to be broken.
  *
  * `CodecLawsSuite` shows the laws pass for a correct codec, which on its own
  * would also be true of laws that assert nothing. This is the other half: a
  * specific bug has to turn a specific law red, and only that one.
  */
class LawsCatchSuite extends munit.FunSuite:

  private def outcomes(codec: Codec[String, Utf8]): Map[String, Boolean] =
    CodecLaws[String, Utf8]("broken", trailing = None)(using
      codec,
      summon[Arbitrary[String]]
    ).properties.map { (name, prop) =>
      name -> Test.check(Test.Parameters.default, prop).passed
    }.toMap

  /** Asserts `law` is the only one the codec fails. Catching the bug is half of
    * it; not smearing the failure across unrelated laws is the other half,
    * because that is what makes a red run point somewhere.
    */
  private def onlyFailure(codec: Codec[String, Utf8], law: String)(using
      munit.Location
  ): Unit =
    val results = outcomes(codec)
    val key = s"broken.$law"
    assert(
      results.contains(key),
      s"$law is not in the law set: ${results.keys}"
    )
    assert(!results(key), s"$law passed a codec built to break it")
    assertEquals(results.removed(key).filter((_, passed) => !passed), Map.empty)

  test("pooledEquivalent catches an encoder blind to sink reuse") {
    onlyFailure(new ReuseBlindCodec, "pooledEquivalent")
  }

  test("sinkIndependence catches an encoder blind to sink position") {
    onlyFailure(new PositionBlindCodec, "sinkIndependence")
  }
