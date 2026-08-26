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

/** Correct on a sink it has never seen, wrong on one handed to it twice.
  *
  * This is the failure `pooledEquivalent` exists for, and the one shape a law
  * that builds its pool per sample can never observe: with a fresh pool every
  * time, every borrow is a first borrow.
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

/** The laws checked against a codec that is known to be broken.
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

  test("pooledEquivalent catches an encoder blind to sink reuse") {
    val results = outcomes(new ReuseBlindCodec)

    assert(
      !results("broken.pooledEquivalent"),
      "pooledEquivalent passed a codec that corrupts its output on a " +
        "recycled sink — the pool is being rebuilt per sample again"
    )

    // Every other law is about something else and has to stay green, or the
    // one above proves nothing about where the bug is.
    val others = results.removed("broken.pooledEquivalent")
    assertEquals(others.filter((_, passed) => !passed), Map.empty)
  }
