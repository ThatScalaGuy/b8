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

package b8.scalapb

import b8.ByteSource
import b8.Codec
import b8.Format.Proto
import b8.scalapb.ProtoFixtures.pNested1
import b8.scalapb.protos.PNested
import b8.scalapb.protos.PRecursive

/** What the two configuration parameters actually do behind ScalaPB, which in
  * both cases is less than their names promise.
  *
  * The other bridges' config suites check that a knob changes behaviour. This
  * one mostly checks that it does not, because that is what is true: neither
  * `deterministic` nor `recursionLimit` reaches any code ScalaPB runs, and a
  * test that asserted the comfortable thing would simply fail. Both are pinned
  * here so the limitation is a measured fact with a name on it instead of
  * something every reader has to rediscover, and so that on the day ScalaPB
  * starts honouring either one, a test in this file breaks and points at the
  * paragraph that needs rewriting.
  *
  * The codecs are plain `val`s rather than local givens. A local given would in
  * fact work — this suite lives inside `b8.scalapb`, so the package's own
  * `given Codec[A, Proto]` is already in the enclosing scope, but it is
  * parameterised, and a monomorphic given in the class body is the more
  * specific of the two and wins without any ambiguity. The point is that a
  * reader would have to know that rule to know which codec a bare `summon`
  * meant, and half this file is about codecs that differ only in a flag. Naming
  * them and calling them directly says which one is meant at the call site.
  */
class ConfigSuite extends munit.FunSuite:

  /** The bridge's defaults, spelled out so the flagged codec below has
    * something to be compared against.
    */
  private val default: Codec[PNested, Proto] = codec[PNested]()

  /** protobuf's deterministic-serialization flag, on.
    *
    * Read this before trusting it. The flag is a bit on the
    * `CodedOutputStream`, and the only thing that reads it is protobuf-java's
    * own map-field writer, which sorts entries by key before writing them.
    * ScalaPB-generated code never goes through that writer: a
    * `map<string, string>` field is a Scala `Map`, and the generated `writeTo`
    * iterates it directly and writes each entry in the order it comes out. The
    * word "deterministic" does not occur anywhere in the generated sources. So
    * the flag is set, protobuf records it, and nothing ever asks.
    *
    * The consequence is the second test below: two `PNested` values whose maps
    * are `==` but were built in opposite insertion order encode to different
    * bytes with the flag on, exactly as they do with it off. What does hold,
    * and holds either way, is the weaker property most callers actually want —
    * one value encodes to the same bytes every time, because one `Map` iterates
    * the same way twice.
    *
    * The parameter stays because it is protobuf's own switch, because it costs
    * one call, and because a ScalaPB that began honouring it would need no
    * change on the b8 side. Read it as an intent, not as a guarantee. Do not
    * hash, sign or content-address the bytes of a message that has a map field
    * on the strength of it; if that is what you need, compare the parsed values
    * rather than the bytes, or carry the entries in a `repeated` field you sort
    * yourself.
    */
  private val deterministic: Codec[PNested, Proto] =
    codec[PNested](deterministic = true)

  test("the deterministic flag leaves a single value's bytes alone") {
    // The weakest thing worth knowing about a flag that does nothing: it also
    // breaks nothing. Same bytes as the default codec, and the same bytes
    // ScalaPB's own `toByteArray` produces.
    val flagged = deterministic.encode(pNested1)
    assertEquals(flagged.toSeq, default.encode(pNested1).toSeq)
    assertEquals(flagged.toSeq, pNested1.toByteArray.toSeq)
  }

  test("deterministic does not reorder a ScalaPB map field") {
    val m1 = Map("a" -> "1", "b" -> "2", "c" -> "3", "d" -> "4")
    val m2 = Map("d" -> "4", "c" -> "3", "b" -> "2", "a" -> "1")
    // Equal as maps, and Scala's four-entry `Map4` keeps the order it was
    // built in, so the two iterate opposite ways. Under a writer that
    // honoured the flag both would come out sorted, and therefore identical.
    assertEquals(m1, m2)
    assertNotEquals(m1.keys.toList, m2.keys.toList)

    val first = deterministic.encode(PNested(meta = m1))
    val second = deterministic.encode(PNested(meta = m2))
    assert(
      !first.sameElements(second),
      "ScalaPB now honours deterministic serialization: rewrite the docs"
    )
  }

  test("the same value encodes to the same bytes, flag or no flag") {
    // This is the property the flag is usually reached for, and it needs no
    // flag: `pNested1` carries a four-entry map, and its iteration order is
    // fixed once the map exists.
    val flagged = deterministic.encode(pNested1).toSeq
    val plain = default.encode(pNested1).toSeq
    assertEquals(flagged, deterministic.encode(pNested1).toSeq)
    assertEquals(plain, default.encode(pNested1).toSeq)
  }

  /** protobuf's nesting bound, at the bridge's default of 100.
    *
    * Read this before trusting it too, because the gap is wider here.
    * `recursionLimit` is set on the `CodedInputStream`, and protobuf-java's own
    * parsers honour it by reading nested messages through
    * `CodedInputStream.readMessage`, which raises a depth counter and checks
    * it. ScalaPB parses through `scalapb.LiteParser.readMessage`, which pushes
    * a length limit and then recurses; the counter is never touched, so nothing
    * in the parse compares a depth against this number. A chain 200 levels deep
    * decodes through a codec configured for 8, which is what the last test in
    * this file measures.
    *
    * That leaves the JVM stack as the only real bound, and it does not fail
    * politely. Past a few thousand levels the parse raises a
    * `StackOverflowError` — an `Error`, not an exception — so it never becomes
    * a `DecodeError`, `decode` returns no `Left` for it, and a `Try` around the
    * call does not catch it either. There is deliberately no test for that
    * here: a suite that provokes a stack overflow leaves the thread in a state
    * nobody should be asserting about, and the depth at which it happens is a
    * property of the running JVM rather than of this bridge.
    *
    * The mitigation against untrusted input is not this parameter, it is a cap
    * on the input LENGTH. Every level of nesting costs at least two bytes on
    * the wire, a tag and a length, so a byte cap is a depth cap: refuse a frame
    * longer than N bytes and nothing inside it can nest deeper than N/2.
    */
  private val recursive: Codec[PRecursive, Proto] = codec[PRecursive]()

  /** The same codec with protobuf's bound turned down to eight, far below every
    * depth this suite builds. Nothing reads it.
    */
  private val shallow: Codec[PRecursive, Proto] =
    codec[PRecursive](recursionLimit = 8)

  /** `depth` levels of nesting wrapped around a labelled leaf.
    *
    * Built as a fold, so that constructing the value is iterative and cannot
    * overflow the stack the way parsing it can. A test helper that blew up
    * before the code under test ran would prove nothing.
    */
  private def chain(depth: Int): PRecursive =
    (1 to depth).foldLeft(PRecursive(label = "leaf"))((c, _) =>
      PRecursive(child = Some(c))
    )

  test("a chain round-trips, so the helper builds real nesting") {
    val value = chain(5)
    // Equality alone would not catch a degenerate helper: a `chain` that
    // ignored `depth` would round-trip just as happily. Every level costs a
    // tag and a length byte, so five levels have to be measurably larger
    // than one.
    assert(
      value.serializedSize > chain(1).serializedSize,
      clue(value.serializedSize)
    )
    assertEquals(
      recursive.decode(ByteSource(recursive.encode(value))),
      Right(value)
    )
  }

  test("fifty levels decode with the default limit") {
    val value = chain(50)
    assertEquals(
      recursive.decode(ByteSource(recursive.encode(value))),
      Right(value)
    )
  }

  test("recursionLimit is set on the stream and ScalaPB never reads it") {
    // Twenty-five times the configured bound, and it comes back whole. If
    // this ever turns into a `Left`, ScalaPB started enforcing the limit and
    // the scaladoc above is out of date.
    val value = chain(200)
    assertEquals(
      shallow.decode(ByteSource(shallow.encode(value))),
      Right(value)
    )
  }

  test("the one-way factories thread their arguments through") {
    // `encoder` and `decoder` are public API too, and they are the only
    // place either parameter travels on its own: everything above goes
    // through `codec`, which builds a `ScalapbCodec`, while these build a
    // `ScalapbEncoder` and a `ScalapbDecoder` with their own copies of the
    // fields. What is checked here is the wiring, not the semantics — the
    // two assertions are the ones the codec already made.
    val flagged = encoder[PNested](deterministic = true)
    val m1 = Map("a" -> "1", "b" -> "2", "c" -> "3", "d" -> "4")
    val m2 = Map("d" -> "4", "c" -> "3", "b" -> "2", "a" -> "1")
    assert(
      !flagged
        .encode(PNested(meta = m1))
        .sameElements(flagged.encode(PNested(meta = m2)))
    )

    val deep = chain(200)
    val bounded = decoder[PRecursive](recursionLimit = 8)
    assertEquals(
      bounded.decode(ByteSource(recursive.encode(deep))),
      Right(deep)
    )
  }
