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

import b8.DecodeError
import b8.Encoder
import b8.Format.Json
import b8.jsoniter.given
import b8.laws.Fixtures
import b8.laws.Nested
import b8.stream.Codecs.given

import java.nio.charset.StandardCharsets.UTF_8

import com.github.plokhotnyuk.jsoniter_scala.core.JsonReaderException
import fs2.Fallible
import fs2.Stream

/** What a decode pipe does with bytes that are not frames.
  *
  * Framing is the one part of b8 that no backend supplies, so it is also the
  * one part whose error messages nobody else has written. This suite pins them
  * down, and it pins down the division of labour behind them: a fault in the
  * delimiting is tagged `"framing"`, a fault in the message keeps whichever tag
  * the bridge that found it uses. A caller reading `"framing"` knows the stream
  * is not being cut in the right places; a caller reading `"Json"` knows the
  * cuts were right and the contents were not. Collapsing the two would turn two
  * different bugs, in two different pieces of code, into one indistinguishable
  * log line — which is why the pass-through cases at the bottom matter as much
  * as the framing cases above them.
  *
  * Everything runs in `Fallible`, so a failure is a `Left` rather than a thrown
  * exception, and every case inspects the error rather than merely counting it.
  * The suite ends with a positive control: a well-formed stream through the
  * same three pipes. Without it a change that made every decode fail would
  * leave this file green.
  */
class ErrorSuite extends munit.FunSuite:

  /** Runs a fallible stream to the end and hands back the `DecodeError` it
    * failed with, saying what turned up instead when it did not fail with one.
    */
  private def failure[A](s: Stream[Fallible, A]): DecodeError =
    s.compile.toList match
      case Left(e: DecodeError) => e
      case Left(e)              => fail(s"expected a DecodeError, got $e")
      case Right(as)            => fail(s"expected a failure, got $as")

  /** Bytes written as ints, because that is how a wire dump reads. */
  private def wire(bs: Int*): Stream[Fallible, Byte] =
    Stream.emits(bs.map(_.toByte)).covary[Fallible]

  private def ascii(s: String): Stream[Fallible, Byte] =
    Stream.emits(s.getBytes(UTF_8)).covary[Fallible]

  /** A `Fixed32` frame built by hand: the four-byte big-endian length computed
    * here, then the body verbatim.
    *
    * Written out rather than taken from the encode pipe on purpose. The
    * pass-through cases need framing that is beyond reproach around a body that
    * is not, and the encode pipe cannot produce a body that is not valid JSON.
    */
  private def fixed32Frame(body: Array[Byte]): Stream[Fallible, Byte] =
    val n = body.length
    wire(n >>> 24, n >>> 16, n >>> 8, n) ++
      Stream.emits(body).covary[Fallible]

  private def fixed32Values(
      in: Stream[Fallible, Byte],
      maxFrame: Int = Framing.DefaultMaxFrame
  ): Stream[Fallible, Nested] =
    in.through(decode[Json](Framing.Fixed32, maxFrame))

  private def varintValues(
      in: Stream[Fallible, Byte]
  ): Stream[Fallible, Nested] =
    in.through(decode[Json](Framing.Varint))

  private def lineValues(
      in: Stream[Fallible, Byte],
      maxFrame: Int = Framing.DefaultMaxFrame
  ): Stream[Fallible, Nested] =
    in.through(decode[Json](Framing.Newline, maxFrame))

  // -- fixed32 ----------------------------------------------------------------

  test("a fixed32 header cut short at end of stream is a truncated frame") {
    // One, two and three bytes: every prefix of a four-byte header a stream can
    // end on. None of them says how long the frame is, so none of them can be
    // reported as anything more specific than cut off.
    for n <- 1 to 3 do
      val e = failure(fixed32Values(wire(List.fill(n)(0)*)))
      assertEquals(e.format, "framing", clue(n))
      assertEquals(e.message, "truncated frame at end of stream", clue(n))
  }

  test("a fixed32 body cut short at end of stream is a truncated frame") {
    // A header that is itself perfectly readable — ten bytes — with two bytes
    // behind it. The header is not the problem here, the body is, and the two
    // failures share a message because they share a cause: the stream stopped
    // in the middle of a frame.
    val e = failure(fixed32Values(wire(0, 0, 0, 10, 0x7b, 0x7d)))
    assertEquals(e.format, "framing")
    assertEquals(e.message, "truncated frame at end of stream")
  }

  test("a fixed32 length with the high bit set names the unsigned value") {
    val e = failure(fixed32Values(wire(0xff, 0xff, 0xff, 0xff)))
    assertEquals(e.format, "framing")
    // Four `0xff` bytes are 4294967295 on the wire and -1 in an `Int`. The
    // header reads them the way they were written, unsigned, and the message
    // says the unsigned number: a message naming -1 would send whoever reads it
    // hunting for a sign bug in the writer instead of for a frame that is
    // simply too long to index into an array.
    assertEquals(
      e.message,
      "frame length 4294967295 does not fit in a signed 32-bit int"
    )
    assert(!clue(e.message).contains("-1"))
  }

  test("a fixed32 length past maxFrame fails before the body is waited for") {
    // Four bytes are fed and nothing else: a header declaring 1000 bytes, with
    // no body behind it and no end of stream either. It still fails, and that
    // is the whole point — the limit is checked against the *declared* length
    // the moment the header is readable, so refusing an oversized frame costs
    // the four bytes of its header and not one byte of buffer. A limit enforced
    // against what has accumulated instead would sit here waiting for a body
    // that a hostile writer never has to send.
    val e = failure(fixed32Values(wire(0, 0, 0x03, 0xe8), maxFrame = 16))
    assertEquals(e.format, "framing")
    // Both numbers, because either one alone leaves the reader guessing at the
    // other: what was asked for, and what is allowed.
    assertEquals(
      e.message,
      "frame of 1000 bytes exceeds the maxFrame limit of 16 bytes"
    )
  }

  // -- varint -----------------------------------------------------------------

  test("a varint length longer than five bytes is malformed") {
    // Five bytes with the continuation bit set and a sixth behind them. An
    // unsigned 32-bit varint is five bytes at the outside, so no further input
    // could make this legal — it is malformed rather than incomplete, and
    // waiting for more would be waiting forever.
    val e = failure(varintValues(wire(0x80, 0x80, 0x80, 0x80, 0x80, 0x01)))
    assertEquals(e.format, "framing")
    assertEquals(e.message, "malformed varint length")
  }

  test("a varint length past Int.MaxValue is malformed") {
    // 0xff 0xff 0xff 0xff 0x0f is 4294967295, the largest unsigned 32-bit
    // varint there is. The fifth byte carries bits 28 and up and only three of
    // them may be set; 0x0f sets four, which would land the length in the
    // negatives. Refused at the header rather than read as a negative size.
    val e = failure(varintValues(wire(0xff, 0xff, 0xff, 0xff, 0x0f)))
    assertEquals(e.format, "framing")
    assertEquals(e.message, "malformed varint length")
  }

  test("a varint header cut short at end of stream is a truncated frame") {
    // A lone continuation byte. Unlike the two above, this one is not malformed
    // — one more byte would complete it — so what makes it an error is the
    // stream ending, and the message has to say truncated and not malformed.
    val e = failure(varintValues(wire(0x80)))
    assertEquals(e.format, "framing")
    assertEquals(e.message, "truncated frame at end of stream")
  }

  // -- newline ----------------------------------------------------------------

  test("a line past maxFrame fails before the line ends") {
    // The source is infinite and contains no `\n` anywhere, so this test
    // terminating at all is the assertion. A limit checked only once a line is
    // complete would never be reached here and the suite would hang; the check
    // runs against what has accumulated, per arriving chunk, which is what
    // keeps a writer that never sends a terminator from filling the heap.
    val e = failure(
      lineValues(Stream.constant[Fallible, Byte]('x'.toByte), maxFrame = 4096)
    )
    assertEquals(e.format, "framing")
    // The size named is whatever had accumulated when the check tripped, which
    // depends on the chunk size upstream happened to choose and is not this
    // suite's business. The limit is the number the caller passed in and is
    // pinned exactly, because that is the one a reader has to recognise.
    assert(clue(e.message).startsWith("line of "))
    assert(clue(e.message).endsWith("exceeds the maxFrame limit of 4096 bytes"))
  }

  test("an unterminated final line is a truncated frame") {
    val body = Encoder[Nested, Json].encode(Fixtures.nested1)
    // One record, correct in every respect except that no `\n` follows it. JSON
    // Lines terminates its records rather than separating them, so this is a
    // record whose writer was cut off mid-flush, not the last record of a
    // well-formed file. Accepting it would silently turn a half-written log
    // into a complete one.
    val e = failure(lineValues(Stream.emits(body).covary[Fallible]))
    assertEquals(e.format, "framing")
    assertEquals(e.message, "truncated frame at end of stream")
  }

  test("a stream of nothing but blank lines completes empty") {
    val out = lineValues(ascii("\n\n\n"))
    // The mirror image of the case above, and the reason that one has to check
    // for a non-empty remainder rather than for a missing terminator: every
    // line here *is* terminated, they just carry nothing. Skipping them is what
    // lets a file with a blank line between records — or with a trailing
    // newline of its own — read back as the records alone.
    assertEquals(out.compile.toList, Right(List.empty[Nested]))
  }

  // -- pass-through -----------------------------------------------------------

  test("a malformed body in valid fixed32 framing is jsoniter's error") {
    // The framing is beyond reproach: a correct four-byte header in front of a
    // body of exactly that length. What is wrong is the JSON inside it, and the
    // error that comes out has to say so. This is the whole reason framing
    // errors are tagged "framing" instead of with a format name — the tag is
    // the one thing that tells "this stream is not being cut into frames
    // correctly" apart from "this frame is not a Nested", and those are two
    // different bugs in two different pieces of code.
    val e = failure(fixed32Values(fixed32Frame("{]".getBytes(UTF_8))))
    assertEquals(e.format, "Json")
    // jsoniter's own exception, kept as the cause by the bridge, is the proof
    // that this error was not re-made on the way out of the pipe.
    assert(e.getCause.isInstanceOf[JsonReaderException], e.getCause)
    // jsoniter appends `, offset: 0x…` to every reader message; the framing
    // never mentions an offset, so the two vocabularies do not overlap.
    assert(clue(e.message).contains("offset: 0x"))
    assert(!clue(e.message).contains("frame"))
  }

  test("a malformed line raises jsoniter's error, not a framing error") {
    // Same claim for the framing that finds its boundaries by scanning: the
    // line was delimited correctly, so nothing about the delimiting is wrong.
    val e = failure(lineValues(ascii("{]\n")))
    assertEquals(e.format, "Json")
    assert(e.getCause.isInstanceOf[JsonReaderException], e.getCause)
    assert(clue(e.message).contains("offset: 0x"))
    assert(!clue(e.message).contains("line of"))
  }

  // -- the control ------------------------------------------------------------

  test("the same three pipes read a well-formed stream back") {
    // Every case above asserts that something fails. This one asserts that not
    // everything does, through the identical pipes with the identical formats.
    // Without it, a change that made decoding fail unconditionally would leave
    // this entire suite green.
    val values = List(Fixtures.nested1, Fixtures.nested1.copy(opt = None))
    val source = Stream.emits(values).covary[Fallible]

    val fixed32: Stream[Fallible, Nested] =
      source
        .through(encode[Json](Framing.Fixed32))
        .through(in => fixed32Values(in))
    assertEquals(fixed32.compile.toList, Right(values))

    val varint: Stream[Fallible, Nested] =
      source
        .through(encode[Json](Framing.Varint))
        .through(in => varintValues(in))
    assertEquals(varint.compile.toList, Right(values))

    val newline: Stream[Fallible, Nested] =
      source
        .through(encode[Json](Framing.Newline))
        .through(in => lineValues(in))
    assertEquals(newline.compile.toList, Right(values))

    // And once more under a limit that the frames fit inside, so that the
    // maxFrame case above is pinned as "this length is too big" rather than as
    // "passing maxFrame breaks the pipe".
    val bounded: Stream[Fallible, Nested] =
      source
        .through(encode[Json](Framing.Fixed32))
        .through(in => fixed32Values(in, maxFrame = 64 * 1024))
    assertEquals(bounded.compile.toList, Right(values))
  }
