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

package b8.container

import b8.DecodeError
import b8.Format.Json
import b8.jsoniter.given
import b8.laws.Fixtures
import b8.laws.Flat
import b8.vector.Codecs.given

import scodec.bits.ByteVector

/** One container per file, checked by the compiler rather than described.
  *
  * The package is `b8.container` and not `b8.vector` on purpose. A suite under
  * `b8.vector` would have that package's extension methods as members of its
  * own package, in scope without an import — and an explicit `import
  * b8.array.*` outranks a package member, so the two would shadow rather than
  * clash and the suite would prove the opposite of what it claims. Here nothing
  * is in scope until it is imported, which is the position an ordinary user's
  * file is in.
  *
  * The negative cases go through `compileErrors` over a snippet that carries
  * its own imports, so that the two wildcard imports exist only inside the code
  * being tested and cannot reach the rest of the file.
  */
class ImportSuite extends munit.FunSuite:

  /** One container imported: everything resolves, and the ascriptions say which
    * container it resolved to.
    */
  private object vectorOnly:
    import b8.vector.*

    val encoded: ByteVector = Fixtures.flat1.encode[Json]
    def decoded: Either[DecodeError, Flat] = encoded.decodeAs[Flat, Json]

  test("one container import gives the container that was imported") {
    // `encode[Json]` typed as a `ByteVector` compiles only because `b8.vector`
    // is the container in scope over there.
    assert(vectorOnly.encoded.nonEmpty)
    assertEquals(vectorOnly.decoded, Right(Fixtures.flat1))
  }

  test("one container import is what makes the snippets below compile") {
    // The positive control. Without it a typo inside the snippets would make
    // `compileErrors` non-empty too, and the negative cases would prove
    // nothing.
    assertEquals(
      compileErrors("""
        import b8.vector.*
        Fixtures.flat1.encode[Json]
      """),
      ""
    )
    assertEquals(
      compileErrors("""
        import b8.array.*
        Fixtures.flat1.encode[Json]
      """),
      ""
    )
  }

  test("importing both containers makes encode ambiguous") {
    assert(
      compileErrors("""
        import b8.array.*
        import b8.vector.*
        Fixtures.flat1.encode[Json]
      """).contains("ambiguous"),
      "b8.array.* and b8.vector.* in one scope both answered for encode"
    )
  }

  test("decodeAs is ambiguous too, even though the receivers differ") {
    // Worth pinning down, because it is not what a reader expects. The two
    // `decodeAs` extensions take different receivers — `Array[Byte]` and
    // `ByteVector` — so one might think the argument decides between them. It
    // does not: the name is resolved before applicability is ever considered,
    // so an ambiguous name is an error even where only one candidate could
    // have applied. All four names collide, not just the two on `[A](a: A)`.
    // The receiver is built without `encode`, on purpose: an `encode` in the
    // snippet would fail first and this case would silently become a copy of
    // the one above rather than a statement about `decodeAs`.
    assert(
      compileErrors("""
        import b8.array.*
        import b8.vector.*
        ByteVector.empty.toArray.decodeAs[Flat, Json]
      """).contains("ambiguous")
    )
    assert(
      compileErrors("""
        import b8.array.*
        import b8.vector.*
        ByteVector.empty.decodeAsUnsafe[Flat, Json]
      """).contains("ambiguous")
    )
  }

  /** The escape hatch for a file that genuinely needs both containers: give
    * each import a scope of its own. This is what the docs page recommends, so
    * it had better compile.
    */
  private object asArray:
    import b8.array.*
    def apply(f: Flat): Array[Byte] = f.encode[Json]

  private object asVector:
    import b8.vector.*
    def apply(f: Flat): ByteVector = f.encode[Json]

  test("a scope each keeps both containers available in one file") {
    assertEquals(
      asVector(Fixtures.flat1),
      ByteVector.view(asArray(Fixtures.flat1))
    )
  }

  test("naming the package instead of importing it needs no scope at all") {
    // An extension method is an ordinary method with its receiver in the first
    // argument list, so the package name can be spelled out at the call site.
    // Verbose, but it resolves what two wildcard imports could not, and it is
    // the shortest way to have both containers in a single expression.
    val fromVector: ByteVector = b8.vector.encode(Fixtures.flat1)[Json]
    val fromArray: Array[Byte] = b8.array.encode(Fixtures.flat1)[Json]
    assertEquals(fromVector, ByteVector.view(fromArray))
  }
