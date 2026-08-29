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

package b8.stream

import b8.ByteSink
import b8.ByteSource
import b8.Codec
import b8.Format

import java.nio.charset.StandardCharsets.UTF_8

/** Newline framing against a binary format is a compile error, and this is the
  * suite that says so.
  *
  * The rule itself is not in doubt: a `\n` used as a delimiter only works where
  * the encoding cannot produce a `0x0A` byte of its own, which rules out CBOR
  * and protobuf and leaves the text formats. What is worth pinning down is
  * *when* b8 says no. The obvious implementation is a `require` in the decode
  * pipe — a runtime check that fires on the first message, on the machine that
  * happened to run the stream, possibly in production. `Framing[-Fmt <:
  * Format]` replaces that check with a type: `Newline` is declared as a
  * `Framing[Format.Text]` and never mentions the others, so the mistake is
  * caught while the file is being typed and the decode pipe carries no check at
  * all.
  *
  * Contravariance is the second half of the mechanism and the less obvious one.
  * `encode[Format.Json]` asks for a `Framing[Format.Json]`, and `Newline` is a
  * `Framing[Format.Text]` — a *supertype* argument. That is only acceptable
  * because the parameter is contravariant, which makes `Framing[Format.Text] <:
  * Framing[Format.Json]` exactly when `Format.Json <: Format.Text`. So the same
  * declaration that admits `Newline` for JSON — and for any text format a user
  * writes years from now, without b8 knowing about it — is what refuses it for
  * protobuf. Turn the `-` into nothing and the positive cases below stop
  * compiling; the negative cases would still fail, which is why the positive
  * ones are here.
  *
  * Every check goes through `compileErrors` over a snippet that carries its own
  * imports, so nothing in the snippets leaks into the rest of the file. The
  * snippets are written the way `b8.stream.encode`'s scaladoc writes them — in
  * `.through(...)` position — because that is where `F` and `A` come from an
  * expected type rather than from nowhere. Binding the pipe to a bare `val`
  * happens to work here, but only because the codec below fixes `A` to
  * `String`; in a file with more than one codec in scope it does not, and a
  * negative case that failed for want of an `A` would prove nothing about the
  * framing.
  */
class FramingTypeSuite extends munit.FunSuite:

  /** A text format b8 has never heard of, which is the interesting case: the
    * bound has to admit formats declared downstream, not just the three in
    * `Format`.
    *
    * Not `private`, here and on the codec below, although nothing outside this
    * class names either: they are used only from inside the `compileErrors`
    * snippets, and `-Wunused:all` does not look in there. Marking them private
    * makes the build fail with "unused private member" while the suite passes.
    */
  trait Utf8 extends Format.Text

  /** One codec, for every format tag there is.
    *
    * Deliberately not a real encoding — the bytes are never looked at. What
    * this buys is that no snippet below can fail for want of a given, so the
    * only thing left for the compiler to object to is the framing. A per-format
    * codec would leave "did it reject the framing, or did it just not find an
    * `Encoder`?" open on every negative case.
    */
  given [Fmt <: Format]: Codec[String, Fmt] with
    def encodeTo(a: String, out: ByteSink): Unit = out.write(a.getBytes(UTF_8))
    def decodeUnsafe(in: ByteSource): String =
      new String(in.array, in.offset, in.length, UTF_8)

  test("newline framing does not compile for protobuf") {
    val errs = compileErrors("""
      import b8.Format.Proto
      fs2.Stream
        .emits(List("one"))
        .covary[fs2.Fallible]
        .through(b8.stream.encode[Proto](Framing.Newline))
    """)
    assert(
      errs.nonEmpty,
      "Framing.Newline was accepted for Format.Proto — the bound on Framing no longer holds"
    )
    // Not just "it failed": the failure has to be the framing argument. A typo
    // or a missing given would also make `errs` non-empty, and a suite that
    // stopped at `nonEmpty` would keep passing after the bound was deleted.
    assert(errs.contains("b8.stream.Framing[b8.Format.Proto]"), errs)
  }

  test("newline framing does not compile for cbor") {
    val errs = compileErrors("""
      import b8.Format.Cbor
      fs2.Stream
        .emits(List.empty[Byte])
        .covary[fs2.Fallible]
        .through(b8.stream.decode[Cbor](Framing.Newline))
    """)
    assert(
      errs.nonEmpty,
      "Framing.Newline was accepted for Format.Cbor — the bound on Framing no longer holds"
    )
    assert(errs.contains("b8.stream.Framing[b8.Format.Cbor]"), errs)
  }

  test("a length prefix compiles for the same binary formats") {
    // The positive control for both cases above, and the reason they mean
    // anything: same call, same givens, same inference position, only the
    // framing changed.
    assertEquals(
      compileErrors("""
        import b8.Format.Proto
        fs2.Stream
          .emits(List("one"))
          .covary[fs2.Fallible]
          .through(b8.stream.encode[Proto](Framing.Fixed32))
      """),
      ""
    )
    assertEquals(
      compileErrors("""
        import b8.Format.Proto
        fs2.Stream
          .emits(List("one"))
          .covary[fs2.Fallible]
          .through(b8.stream.encode[Proto](Framing.Varint))
      """),
      ""
    )
    assertEquals(
      compileErrors("""
        import b8.Format.Cbor
        fs2.Stream
          .emits(List.empty[Byte])
          .covary[fs2.Fallible]
          .through(b8.stream.decode[Cbor](Framing.Fixed32))
      """),
      ""
    )
    assertEquals(
      compileErrors("""
        import b8.Format.Cbor
        fs2.Stream
          .emits(List.empty[Byte])
          .covary[fs2.Fallible]
          .through(b8.stream.decode[Cbor](Framing.Varint))
      """),
      ""
    )
  }

  test("newline framing compiles for json in both directions") {
    // `Format.Json extends Format.Text`, so a `Framing[Format.Text]` is a
    // `Framing[Format.Json]` — the contravariance doing its half of the work.
    assertEquals(
      compileErrors("""
        import b8.Format.Json
        fs2.Stream
          .emits(List("one"))
          .covary[fs2.Fallible]
          .through(b8.stream.encode[Json](Framing.Newline))
      """),
      ""
    )
    assertEquals(
      compileErrors("""
        import b8.Format.Json
        fs2.Stream
          .emits(List.empty[Byte])
          .covary[fs2.Fallible]
          .through(b8.stream.decode[Json](Framing.Newline))
      """),
      ""
    )
  }

  test("newline framing compiles for a text format b8 does not know") {
    // `Utf8` is declared in this file and nowhere else. Nothing in `b8.stream`
    // enumerates the formats newline framing is legal for; it states one bound
    // and the subtyping does the rest, so a downstream tag picks the rule up by
    // extending `Format.Text`.
    assertEquals(
      compileErrors("""
        fs2.Stream
          .emits(List("one"))
          .covary[fs2.Fallible]
          .through(b8.stream.encode[Utf8](Framing.Newline))
      """),
      ""
    )
    assertEquals(
      compileErrors("""
        fs2.Stream
          .emits(List.empty[Byte])
          .covary[fs2.Fallible]
          .through(b8.stream.decode[Utf8](Framing.Newline))
      """),
      ""
    )
  }
