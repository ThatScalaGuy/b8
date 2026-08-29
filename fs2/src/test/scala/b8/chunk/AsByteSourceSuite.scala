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

package b8.chunk

import java.nio.ByteBuffer

import fs2.Chunk
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

/** Which chunk shapes reach a decoder without being copied.
  *
  * "Zero-copy" is not a property you can see in a round trip — a copy round
  * trips just as well — so every case that claims it asserts reference equality
  * on `ByteSource.array` against the array the chunk was built over. A copy
  * anywhere in `asByteSource` turns those assertions red and nothing else in
  * this module would notice.
  *
  * The cases that do copy are here too, and they assert only what is actually
  * promised: the right bytes, at the right length, once per call. The promise
  * the module makes is not "never copies" — it is "copies exactly when the
  * chunk is not array-backed, and says so".
  *
  * The suite sits inside `b8.chunk`, so `asByteSource` is a member of its own
  * package here and needs no import.
  */
class AsByteSourceSuite extends ScalaCheckSuite:

  private def arr(n: Int): Array[Byte] =
    Array.tabulate(n)(i => (i + 1).toByte)

  test("a chunk over a whole array shares that array") {
    val a = arr(16)
    val source = Chunk.array(a).asByteSource
    assert(source.array eq a, "the chunk was copied")
    assertEquals(source.offset, 0)
    assertEquals(source.length, 16)
  }

  test("a chunk over part of an array shares it, at the chunk's offset") {
    val a = arr(16)
    val source = Chunk.array(a, 3, 5).asByteSource
    assert(source.array eq a, "the slice was copied")
    assertEquals(source.offset, 3)
    assertEquals(source.length, 5)
    assert(source.toArray.sameElements(a.slice(3, 8)))
  }

  test("drop and take of an array chunk stay on that array") {
    val a = arr(16)
    val source = Chunk.array(a).drop(2).take(4).asByteSource
    assert(source.array eq a, "drop/take copied")
    assertEquals(source.offset, 2)
    assertEquals(source.length, 4)
    assert(source.toArray.sameElements(a.slice(2, 6)))
  }

  test("a chunk over a positioned heap buffer shares the buffer's array") {
    val a = arr(16)
    val bb = ByteBuffer.wrap(a)
    bb.position(3)
    val source = Chunk.byteBuffer(bb).asByteSource
    assert(source.array eq a, "the heap buffer was copied")
    assertEquals(source.offset, 3)
    assertEquals(source.length, 13)
    assert(source.toArray.sameElements(a.drop(3)))
    // The buffer the caller still holds was not disturbed.
    assertEquals(bb.position(), 3)
  }

  test("a chunk over a direct buffer is copied rather than refused") {
    val a = arr(16)
    val bb = ByteBuffer.allocateDirect(16)
    bb.put(a)
    bb.flip()
    val chunk = Chunk.byteBuffer(bb)
    val source = chunk.asByteSource
    // A direct buffer exposes no array, so there is nothing to share and the
    // bytes have to be pulled out into one. That must happen rather than throw.
    assertEquals(source.length, 16)
    assert(source.toArray.sameElements(a))
    // And it happens again on every call, because the chunk keeps no array to
    // hand back the second time.
    assert(
      chunk.asByteSource.array ne source.array,
      "a direct-buffer chunk stopped copying per call"
    )

    // `compact` is the fix the scaladoc points at: it resolves the buffer into
    // one array-backed chunk, after which repeated decoding of the same chunk
    // costs nothing.
    val compacted = chunk.compact[Byte]
    val first = compacted.asByteSource
    val second = compacted.asByteSource
    assert(second.array eq first.array, "a compacted chunk still copies")
    assert(first.toArray.sameElements(a))
  }

  test("a concatenation is copied per call, and compact decides when") {
    val left = arr(8)
    val right = arr(4)
    // `++` builds a `Chunk.Queue`, which keeps its two pieces apart. A decoder
    // needs its input in one piece, so the queue has to be flattened first.
    val joined = Chunk.array(left) ++ Chunk.array(right)
    val source = joined.asByteSource
    assertEquals(source.length, 12)
    assert(source.toArray.sameElements(left ++ right))
    assert(
      joined.asByteSource.array ne source.array,
      "a concatenation stopped copying per call"
    )

    val compacted = joined.compact[Byte]
    val first = compacted.asByteSource
    val second = compacted.asByteSource
    assert(second.array eq first.array, "a compacted chunk still copies")
    assert(first.toArray.sameElements(left ++ right))
  }

  test("the empty chunk is the empty source") {
    val source = Chunk.empty[Byte].asByteSource
    assertEquals(source.length, 0)
    assertEquals(source.offset, 0)
    assert(source.isEmpty)
  }

  test("a one-byte chunk does not share its array") {
    val a = arr(1)
    val source = Chunk.array(a).asByteSource
    // fs2's rule, not b8's: `Chunk.array` answers a one-element array with a
    // `Chunk.Singleton`, which keeps the byte and drops the array — so by the
    // time `asByteSource` sees the chunk there is nothing left to share and
    // `toArraySlice` has to allocate. The copy costs exactly one byte, and this
    // floor is the whole reason `asByteSource`'s scaladoc promises sharing for
    // "two or more bytes" rather than for every array-backed chunk.
    assert(source.array ne a, "a one-byte chunk shared its array after all")
    assertEquals(source.length, 1)
    assert(source.toArray.sameElements(a))
  }

  /** Chunks of every shape the module has to survive, built the way a caller
    * actually builds them: whole arrays, array slices, singletons and the empty
    * chunk at the leaves, `++`, `drop` and `take` on top.
    */
  private val genChunk: Gen[Chunk[Byte]] =
    def bytes(n: Int): Gen[Array[Byte]] =
      Gen.listOfN(n, Gen.choose(Byte.MinValue, Byte.MaxValue)).map(_.toArray)

    val leaf: Gen[Chunk[Byte]] =
      Gen.oneOf(
        Gen.choose(0, 24).flatMap(bytes).map(Chunk.array(_)),
        for
          n <- Gen.choose(0, 24)
          a <- bytes(n)
          offset <- Gen.choose(0, n)
          length <- Gen.choose(0, n - offset)
        yield Chunk.array(a, offset, length),
        Gen.choose(Byte.MinValue, Byte.MaxValue).map(Chunk.singleton),
        Gen.const(Chunk.empty[Byte])
      )

    def node(depth: Int): Gen[Chunk[Byte]] =
      if depth <= 0 then leaf
      else
        Gen.oneOf(
          leaf,
          for
            l <- node(depth - 1)
            r <- node(depth - 1)
          yield l ++ r,
          node(depth - 1).flatMap(c => Gen.choose(0, c.size).map(c.drop)),
          node(depth - 1).flatMap(c => Gen.choose(0, c.size).map(c.take))
        )

    Gen.choose(0, 3).flatMap(node)

  property("every chunk shape reaches a source with its own bytes") {
    forAll(genChunk) { (c: Chunk[Byte]) =>
      val source = c.asByteSource
      source.length == c.size &&
      // The type argument keeps `toArray` on bytes; an inferred `Any` would
      // box every element and compare a different array than the one a decoder
      // would see.
      source.toArray.sameElements(c.toArray[Byte])
    }
  }
