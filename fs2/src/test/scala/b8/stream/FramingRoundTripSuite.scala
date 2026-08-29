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

import b8.Format.Json
import b8.Format.Proto
import b8.jsoniter.given
import b8.laws.Fixtures
import b8.laws.Fixtures.given
import b8.laws.Nested
import b8.scalapb.ProtoFixtures
import b8.scalapb.given
import b8.scalapb.protos.PNested
import b8.stream.Codecs.given

import fs2.Fallible
import fs2.Pipe
import fs2.Stream
import munit.ScalaCheckSuite
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalacheck.Prop.*

/** Encode then decode gives the list back, whatever the wire did to it in
  * between.
  *
  * This is the same promise `b8-laws` makes for a codec — encode, decode, get
  * the value back — one level up: for a *sequence* of values and the bytes that
  * delimit them. It is not in `b8-laws` because nothing here is about a codec.
  * A bridge could satisfy every law in that module and this suite would still
  * catch a framing that loses the last message, or one that cannot tell an
  * empty frame from the end of the stream. The law module also has no fs2
  * dependency and should not grow one for a module only `b8-fs2` uses.
  *
  * Three shapes of the same property, because a stream is not a byte array:
  *
  *   - as fs2 hands the bytes over, one chunk per message;
  *   - after `rechunkRandomly`, which merges and splits chunks at boundaries
  *     nobody chose;
  *   - after `chunkLimit(1).unchunks`, one byte per chunk, which is the worst
  *     case for every decoder here — the header arrives a byte at a time and no
  *     frame is ever complete when a chunk ends.
  *
  * A reader may worry that `rechunkRandomly` could spin without emitting. It
  * cannot here: it draws a factor from `(0.1, 2.0)` afresh on every pass and
  * multiplies it by the size of the chunk it just saw, and every chunk on this
  * wire is at least one byte — the smallest thing any of the three framings
  * writes for a message is a one-byte varint length. A target size that rounds
  * to zero therefore still consumes the chunk it was computed from, and the
  * stream is finite regardless.
  *
  * The effect is `Fallible` throughout, not `IO`. Everything under test is pure
  * — `Pull.output` and `Pull.raiseError`, no allocation of anything effectful —
  * so `Fallible` runs it synchronously, `compile.toList` yields an
  * `Either[Throwable, List[A]]` that a property can compare directly, and this
  * module needs no cats-effect dependency to be tested.
  */
class FramingRoundTripSuite extends ScalaCheckSuite:

  /** `0x0A`. */
  private final val Lf: Byte = 10

  /** `0x0D`. */
  private final val Cr: Byte = 13

  /** Short lists on purpose. Nine properties run over this generator and
    * `nested1`-sized values are around a kilobyte of JSON each, so the length
    * is what decides whether the suite takes a second or a minute. Nothing here
    * gets better at six elements than it is at three.
    */
  private val genValues: Gen[List[Nested]] =
    Gen.choose(0, 6).flatMap(Gen.listOfN(_, Arbitrary.arbitrary[Nested]))

  /** All three framings, since `Format.Json` is a text format. */
  private val jsonFramings: List[Framing[Json]] =
    List(Framing.Fixed32, Framing.Varint, Framing.Newline)

  /** The two a binary format may use. `Newline` cannot be added to this list:
    * it is a `Framing[Format.Text]`, and `FramingTypeSuite` is where that is
    * pinned down.
    */
  private val protoFramings: List[Framing[Proto]] =
    List(Framing.Fixed32, Framing.Varint)

  /** `Framing[?]` rather than a concrete format, so the one method names both
    * lists.
    */
  private def name(f: Framing[?]): String = f.toString.toLowerCase

  private def encoded(
      as: List[Nested],
      f: Framing[Json]
  ): Stream[Fallible, Byte] =
    Stream.emits(as).covary[Fallible].through(b8.stream.encode[Json](f))

  /** The ascribed `Pipe` is not decoration, on either decode helper.
    *
    * `decode[Fmt]` leaves the element type open — a `Pipe[F, Byte, A]` says
    * nothing about `A` — and the `Decoder` lookup needs it settled, so `A` has
    * to arrive as an expected type. The method's own result type is not enough:
    * it does not reach back through `.compile.toList` to the `through` call,
    * and what the compiler reports instead is an ambiguity between the four
    * `JsonValueCodec`s in `Codecs`, or a missing `GeneratedMessageCompanion[A]`
    * for the proto half. Naming the pipe is what fixes it, and doing it once
    * here is what the call sites are spared. The encode side needs none of
    * this: its `A` is the element type of the stream it is applied to.
    */
  private def decoded(
      bytes: Stream[Fallible, Byte],
      f: Framing[Json]
  ): Either[Throwable, List[Nested]] =
    val pipe: Pipe[Fallible, Byte, Nested] = b8.stream.decode[Json](f)
    bytes.through(pipe).compile.toList

  private def decodedProto(
      bytes: Stream[Fallible, Byte],
      f: Framing[Proto]
  ): Either[Throwable, List[PNested]] =
    val pipe: Pipe[Fallible, Byte, PNested] = b8.stream.decode[Proto](f)
    bytes.through(pipe).compile.toList

  /** Annotated rather than inlined at the call sites: `rechunkRandomly` is
    * `[F2[x] >: F[x]]`, and pinning `F2` to `Fallible` once here keeps the
    * three properties below from each having to say so.
    */
  private def rechunked(s: Stream[Fallible, Byte]): Stream[Fallible, Byte] =
    s.rechunkRandomly()

  jsonFramings.foreach { f =>
    property(s"${name(f)} framing round-trips a list of values") {
      forAll(genValues) { (as: List[Nested]) =>
        decoded(encoded(as, f), f) == Right(as)
      }
    }

    property(s"${name(f)} framing does not care where the chunks fall") {
      forAll(genValues) { (as: List[Nested]) =>
        decoded(rechunked(encoded(as, f)), f) == Right(as)
      }
    }

    property(s"${name(f)} framing survives one byte per chunk") {
      forAll(genValues) { (as: List[Nested]) =>
        decoded(encoded(as, f).chunkLimit(1).unchunks, f) == Right(as)
      }
    }

    test(s"${name(f)} framing writes nothing for the empty stream") {
      // Both halves matter. No bytes out, so a framing never emits a header
      // for a stream it never saw a message on; and no values back, so the
      // decoder treats an empty input as an empty stream rather than as a
      // truncated frame.
      assertEquals(encoded(Nil, f).compile.toList, Right(List.empty[Byte]))
      assertEquals(decoded(encoded(Nil, f), f), Right(List.empty[Nested]))
    }
  }

  /** A fixed list, so the mangled wire below is a stated thing rather than
    * whatever a generator drew.
    */
  private val known: List[Nested] = List(
    Fixtures.nested1,
    Fixtures.nested1
      .copy(children = Vector.empty, meta = Map.empty, opt = None),
    Fixtures.nested1
  )

  test("newline framing reads crlf terminators and blank lines back") {
    val wire = encoded(known, Framing.Newline).compile.toList
      .getOrElse(fail("encoding the fixed list failed"))

    // The one assumption the mangling below rests on: the only `0x0A` bytes on
    // this wire are the three the pipe wrote. `Fixtures` puts a literal "\n"
    // into the generated strings and `nested1` carries one in a tag, and
    // jsoniter writes it as the two characters `\` and `n` — which is the whole
    // reason newline framing is legal for JSON in the first place.
    assertEquals(wire.count(_ == Lf), known.size)

    // What a file written on Windows and then reflowed by something that likes
    // spacing looks like: every terminator becomes CRLF, and a blank line
    // follows each record.
    val mangled = wire.flatMap {
      case Lf => List(Cr, Lf, Lf)
      case b  => List(b)
    }
    assertEquals(mangled.count(_ == Lf), 2 * known.size)

    assertEquals(
      decoded(Stream.emits(mangled).covary[Fallible], Framing.Newline),
      Right(known)
    )
  }

  /** `PNested()` is protobuf's default instance: every field is at its default,
    * so there is nothing to write and it encodes to zero bytes.
    */
  private val withEmptyMessages: List[PNested] =
    List(PNested(), ProtoFixtures.pNested1, PNested())

  protoFramings.foreach { f =>
    test(s"${name(f)} framing round-trips a zero-length frame") {
      // The case that separates a length prefix from a sentinel. A frame of
      // zero bytes is a message — the default instance — and the decoder has to
      // emit it and carry on rather than read the length as "no more input".
      // Both ends of the list are empty as well, so a framing that dropped a
      // leading or a trailing empty frame is caught too.
      assertEquals(
        decodedProto(
          Stream
            .emits(withEmptyMessages)
            .covary[Fallible]
            .through(b8.stream.encode[Proto](f)),
          f
        ),
        Right(withEmptyMessages)
      )
    }
  }
