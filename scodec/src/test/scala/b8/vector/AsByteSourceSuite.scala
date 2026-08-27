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

package b8.vector

import java.nio.ByteBuffer

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import scodec.bits.ByteVector

/** Which vector shapes reach a decoder without being copied.
  *
  * "Zero-copy" is not a property you can see in a round trip — a copy round
  * trips just as well — so every case that claims it asserts reference equality
  * on `ByteSource.array` against the array the vector was built over. A copy
  * anywhere in `asByteSource` turns those assertions red and nothing else in
  * this module would notice.
  *
  * The cases that do copy are here too, and they assert only what is actually
  * promised: the right bytes, at the right offset, once. The promise the module
  * makes is not "never copies" — it is "copies exactly when the vector is not a
  * single array-backed chunk, and says so".
  *
  * The suite sits inside `b8.vector`, so `asByteSource` is a member of its own
  * package here and needs no import.
  */
class AsByteSourceSuite extends ScalaCheckSuite:

  private def arr(n: Int): Array[Byte] =
    Array.tabulate(n)(i => (i + 1).toByte)

  test("a view over a whole array shares that array") {
    val a = arr(16)
    val source = ByteVector.view(a).asByteSource
    assert(source.array eq a, "the view was copied")
    assertEquals(source.offset, 0)
    assertEquals(source.length, 16)
  }

  test("a view over part of an array shares it, at the view's offset") {
    val a = arr(16)
    val source = ByteVector.view(a, 3, 5).asByteSource
    assert(source.array eq a, "the slice was copied")
    assertEquals(source.offset, 3)
    assertEquals(source.length, 5)
    assert(source.toArray.sameElements(a.slice(3, 8)))
  }

  test("drop and take of a view stay views") {
    val a = arr(16)
    val source = ByteVector.view(a).drop(2).take(4).asByteSource
    assert(source.array eq a, "drop/take copied")
    assertEquals(source.offset, 2)
    assertEquals(source.length, 4)
    assert(source.toArray.sameElements(a.slice(2, 6)))
  }

  test("a copied vector keeps its own array, and keeps it across calls") {
    val a = arr(16)
    val bytes = ByteVector(a)
    val first = bytes.asByteSource
    // `apply` copies on construction, by scodec's own contract — so the source
    // cannot point at `a`.
    assert(first.array ne a, "ByteVector.apply did not copy")
    assert(first.toArray.sameElements(a))
    // But the copy happened once, when the vector was built. Asking the same
    // vector for a second source hands back the same array rather than copying
    // again, which is what makes repeated decoding of one vector free.
    val second = bytes.asByteSource
    assert(second.array eq first.array, "a second asByteSource copied again")
  }

  test("a view over a positioned heap buffer shares the buffer's array") {
    val a = arr(16)
    val bb = ByteBuffer.wrap(a)
    bb.position(3)
    val source = ByteVector.view(bb).asByteSource
    assert(source.array eq a, "the heap buffer was copied")
    assertEquals(source.offset, 3)
    assertEquals(source.length, 13)
    assert(source.toArray.sameElements(a.drop(3)))
    // The buffer the caller still holds was not disturbed.
    assertEquals(bb.position(), 3)
  }

  test("a view over a direct buffer is copied rather than refused") {
    val a = arr(16)
    val bb = ByteBuffer.allocateDirect(16)
    bb.put(a)
    bb.flip()
    val bytes = ByteVector.view(bb)
    val source = bytes.asByteSource
    // A direct buffer exposes no array, so `ByteSource` copies. It must do that
    // rather than throw.
    assertEquals(source.length, 16)
    assert(source.toArray.sameElements(a))

    // And this is the one shape `compact` cannot help with: the vector is
    // already a single chunk, so `compact` hands it straight back and every
    // call keeps copying. `copy` is what puts an array underneath it — which is
    // what `asByteSource`'s scaladoc sends callers to.
    assert(bytes.compact eq bytes, "compact rebuilt an already-compact vector")
    assert(
      bytes.asByteSource.array ne source.array,
      "a direct-buffer view stopped copying, which no longer matches the docs"
    )
    val copied = bytes.copy
    val once = copied.asByteSource
    val twice = copied.asByteSource
    assert(once.array eq twice.array, "copy did not produce a stable array")
    assert(once.toArray.sameElements(a))
  }

  test("a concatenation is copied once, and compact decides when") {
    val left = ByteVector.view(arr(8))
    val right = ByteVector.view(arr(4))
    val joined = left ++ right
    val source = joined.asByteSource
    assertEquals(source.length, 12)
    assert(source.toArray.sameElements(arr(8) ++ arr(4)))

    // Compacting resolves the concatenation into one array, after which the
    // vector behaves like any other single chunk: no copy per call. This is
    // the `.compact` the scaladoc tells callers to reach for.
    val compacted = joined.compact
    val first = compacted.asByteSource
    val second = compacted.asByteSource
    assert(second.array eq first.array, "a compacted vector still copies")
    assert(first.toArray.sameElements(arr(8) ++ arr(4)))
  }

  test("the empty vector is the empty source") {
    val source = ByteVector.empty.asByteSource
    assertEquals(source.length, 0)
    assertEquals(source.offset, 0)
    assert(source.isEmpty)
  }

  /** Vectors of every shape the module has to survive, built the way a caller
    * actually builds them: `view` and `apply` at the leaves, `++`, `drop` and
    * `take` on top.
    */
  private val genVector: Gen[ByteVector] =
    def leaf: Gen[ByteVector] =
      for
        n <- Gen.choose(0, 24)
        bytes <- Gen.listOfN(n, Gen.choose(Byte.MinValue, Byte.MaxValue))
        viewed <- Gen.oneOf(true, false)
        a = bytes.toArray
      yield if viewed then ByteVector.view(a) else ByteVector(a)

    def node(depth: Int): Gen[ByteVector] =
      if depth <= 0 then leaf
      else
        Gen.oneOf(
          leaf,
          for
            l <- node(depth - 1)
            r <- node(depth - 1)
          yield l ++ r,
          node(depth - 1).flatMap(v =>
            Gen.choose(0L, v.size).map(n => v.drop(n))
          ),
          node(depth - 1).flatMap(v =>
            Gen.choose(0L, v.size).map(n => v.take(n))
          )
        )

    Gen.choose(0, 3).flatMap(node)

  property("every vector shape reaches a source with its own bytes") {
    forAll(genVector) { (v: ByteVector) =>
      val source = v.asByteSource
      source.length == v.size.toInt &&
      source.toArray.sameElements(v.toArray)
    }
  }
