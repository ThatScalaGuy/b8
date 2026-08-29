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

import b8.ArraySink
import b8.DecodeError
import b8.Decoder
import b8.Encoder
import b8.Format
import b8.chunk.asByteSource

import scala.annotation.tailrec

import fs2.Chunk
import fs2.Pipe
import fs2.Pull
import fs2.RaiseThrowable
import fs2.Stream

/** The framing itself: where one message ends and the next begins.
  *
  * Nothing here is public. The two builders in `b8.stream` are the whole API;
  * this is the part that has to be right.
  */
private[stream] object Framed:

  /** `0x0A`. */
  private final val Lf: Byte = 10

  /** `0x0D`. */
  private final val Cr: Byte = 13

  /** Errors this module raises itself are tagged `"framing"` rather than with a
    * format name, because that is what they are about: the delimiting, not the
    * message. A failure the value decoder reported keeps its own bridge's tag
    * and is re-raised untouched, so a caller can always tell "this is not a
    * frame" from "this frame is not a User".
    */
  private def framingError(message: String): DecodeError =
    new DecodeError(message, "framing")

  // -- encoding ---------------------------------------------------------------

  def encodePipe[F[_], A, Fmt <: Format](
      framing: Framing[Fmt]
  )(using e: Encoder[A, Fmt]): Pipe[F, A, Byte] =
    val frame: A => Chunk[Byte] = framing match
      case Framing.Fixed32 => fixed32Frame(e, _)
      case Framing.Varint  => varintFrame(e, _)
      case Framing.Newline => newlineFrame(e, _)
    in => encodeGo(in, frame).stream

  /** One message, one chunk, one output.
    *
    * Not `mapChunks`: that would emit one chunk per *input* chunk and lose the
    * one-chunk-per-message guarantee the moment an upstream operator batched
    * two values together. The index loop runs over `toIndexedChunk`, which is a
    * no-op for the array-backed chunks that arrive in practice and flattens the
    * `Chunk.Queue` an upstream merge produces — where indexing would otherwise
    * be O(number of chunks) per element.
    */
  private def encodeGo[F[_], A](
      in: Stream[F, A],
      frame: A => Chunk[Byte]
  ): Pull[F, Byte, Unit] =
    in.pull.uncons.flatMap {
      case None            => Pull.done
      case Some((hd0, tl)) =>
        val hd = hd0.toIndexedChunk
        def loop(i: Int): Pull[F, Byte, Unit] =
          if i >= hd.size then encodeGo(tl, frame)
          else Pull.output(frame(hd(i))) >> loop(i + 1)
        loop(0)
    }

  /** Reserve four bytes, encode over the top of them, then patch the length in.
    *
    * The alternative is encoding into one buffer and copying it behind a header
    * in another, which is a second pass over every byte. `ArraySink` exposes
    * `buffer` and `position`, so the header can be written after the fact — but
    * `encodeTo` may have grown the sink, so `buffer` is re-read afterwards and
    * never before.
    *
    * The chunk is a view of that buffer, not a copy of it. Safe because the
    * sink is fresh per message and nothing else ever sees it; the cost is that
    * the array behind a chunk can be larger than the chunk, which for a stream
    * that consumes and drops its chunks is a trade worth making.
    */
  private def fixed32Frame[A, Fmt <: Format](
      e: Encoder[A, Fmt],
      a: A
  ): Chunk[Byte] =
    val sink = new ArraySink(e.sizeHint(a) + 4)
    sink.ensure(4)
    sink.advance(4)
    e.encodeTo(a, sink)
    val buf = sink.buffer
    val len = sink.position - 4
    buf(0) = (len >>> 24).toByte
    buf(1) = (len >>> 16).toByte
    buf(2) = (len >>> 8).toByte
    buf(3) = len.toByte
    Chunk.array(buf, 0, sink.position)

  /** Same trick, except the header's width is not known until the message is
    * written: reserve the widest varint, then write the real one so that it
    * ends where the message begins, and start the chunk at whatever offset that
    * left.
    */
  private def varintFrame[A, Fmt <: Format](
      e: Encoder[A, Fmt],
      a: A
  ): Chunk[Byte] =
    val sink = new ArraySink(e.sizeHint(a) + 5)
    sink.ensure(5)
    sink.advance(5)
    e.encodeTo(a, sink)
    val buf = sink.buffer
    val len = sink.position - 5
    val start = 5 - varintWidth(len)
    var v = len
    var i = start
    while v >= 0x80 do
      buf(i) = ((v & 0x7f) | 0x80).toByte
      v >>>= 7
      i += 1
    buf(i) = v.toByte
    Chunk.array(buf, start, sink.position - start)

  private def newlineFrame[A, Fmt <: Format](
      e: Encoder[A, Fmt],
      a: A
  ): Chunk[Byte] =
    val sink = new ArraySink(e.sizeHint(a) + 1)
    e.encodeTo(a, sink)
    sink.write(Lf)
    Chunk.array(sink.buffer, 0, sink.position)

  /** Index of the first `\n` in `values` between `from` and `until`, or `until`
    * when there is none.
    */
  private def indexOfLf(values: Array[Byte], from: Int, until: Int): Int =
    var i = from
    while i < until && values(i) != Lf do i += 1
    i

  /** Bytes an LEB128 varint takes for a non-negative `n`. */
  private def varintWidth(n: Int): Int =
    if n < 0x80 then 1
    else if n < 0x4000 then 2
    else if n < 0x200000 then 3
    else if n < 0x10000000 then 4
    else 5

  // -- decoding ---------------------------------------------------------------

  /** What the bytes at the head of the buffer say about the next frame. */
  private enum Header:
    /** Not enough bytes yet to tell. */
    case NeedMore

    /** The prefix itself is broken; no amount of further input fixes it. */
    case Malformed(message: String)

    /** A frame of `length` bytes starts `offset` bytes in. */
    case Frame(offset: Int, length: Int)

  def decodePipe[F[_], A, Fmt <: Format](
      framing: Framing[Fmt],
      maxFrame: Int
  )(using d: Decoder[A, Fmt], rt: RaiseThrowable[F]): Pipe[F, Byte, A] =
    framing match
      case Framing.Fixed32 =>
        in => prefixed(in, maxFrame, fixed32Header, d).stream
      case Framing.Varint =>
        in => prefixed(in, maxFrame, varintHeader, d).stream
      case Framing.Newline =>
        in => newlines(in, maxFrame, d).stream

  private def fixed32Header(buf: Chunk[Byte]): Header =
    if buf.size < 4 then Header.NeedMore
    else
      // Four bytes at most, and none at all when the buffer is one slice —
      // `take` of an array-backed chunk stays a view on the same array.
      val h = buf.take(4).toArraySlice[Byte]
      val v = h.values
      val o = h.offset
      val len =
        ((v(o) & 0xff) << 24) | ((v(o + 1) & 0xff) << 16) |
          ((v(o + 2) & 0xff) << 8) | (v(o + 3) & 0xff)
      // The prefix is unsigned on the wire and an array index is not, so a
      // length past 2 GiB is refused here rather than read as negative.
      if len < 0 then
        Header.Malformed(
          s"frame length ${Integer.toUnsignedString(len)} does not fit in a signed 32-bit int"
        )
      else Header.Frame(4, len)

  private def varintHeader(buf: Chunk[Byte]): Header =
    val avail = math.min(5, buf.size)
    if avail == 0 then Header.NeedMore
    else
      val h = buf.take(avail).toArraySlice[Byte]
      val v = h.values
      val o = h.offset

      // Bounded to five bytes: that is every value an unsigned 32-bit varint
      // can take, so a sixth continuation byte is malformed input rather than a
      // reason to keep reading. The fifth byte carries bits 28 and up, and only
      // three of them may be set — a fourth would push the length past
      // `Int.MaxValue` and make it negative.
      @tailrec def read(i: Int, len: Int): Header =
        if i == avail then
          if avail == 5 then Header.Malformed("malformed varint length")
          else Header.NeedMore
        else
          val b = v(o + i).toInt
          val payload = b & 0x7f
          if i == 4 && payload > 0x07 then
            Header.Malformed("malformed varint length")
          else
            val next = len | (payload << (7 * i))
            if (b & 0x80) == 0 then Header.Frame(i + 1, next)
            else read(i + 1, next)

      read(0, 0)

  /** The length-prefixed loop, shared by `Fixed32` and `Varint`.
    *
    * The limit is checked against the *declared* length, the moment the header
    * is readable and before a single byte of the body has been waited for. A
    * four-byte header claiming two gigabytes costs four bytes to reject.
    */
  private def prefixed[F[_], A, Fmt <: Format](
      in: Stream[F, Byte],
      maxFrame: Int,
      header: Chunk[Byte] => Header,
      d: Decoder[A, Fmt]
  )(using RaiseThrowable[F]): Pull[F, A, Unit] =

    def drain(buf: Chunk[Byte]): Pull[F, A, Chunk[Byte]] =
      header(buf) match
        case Header.NeedMore              => Pull.pure(buf)
        case Header.Malformed(m)          => Pull.raiseError[F](framingError(m))
        case Header.Frame(offset, length) =>
          if length > maxFrame then
            Pull.raiseError[F](
              framingError(
                s"frame of $length bytes exceeds the maxFrame limit of $maxFrame bytes"
              )
            )
          else if buf.size - offset < length then Pull.pure(buf)
          else
            // `drop` and `take` of an array-backed buffer are views, so a frame
            // that arrived inside one chunk reaches the decoder without a copy.
            d.decode(buf.drop(offset).take(length).asByteSource) match
              case Right(a) =>
                Pull.output1(a) >> drain(buf.drop(offset + length))
              case Left(e) => Pull.raiseError[F](e)

    def go(buf: Chunk[Byte], s: Stream[F, Byte]): Pull[F, A, Unit] =
      s.pull.uncons.flatMap {
        case Some((hd, tl)) => drain(buf ++ hd).flatMap(rest => go(rest, tl))
        case None           =>
          if buf.isEmpty then Pull.done
          else
            Pull.raiseError[F](framingError("truncated frame at end of stream"))
      }

    go(Chunk.empty, in)

  /** The line loop.
    *
    * Each arriving chunk is scanned once, from where the last scan stopped:
    * `pending` holds the part of the current line that is already known to
    * contain no `\n`, so no byte is ever looked at twice.
    */
  private def newlines[F[_], A, Fmt <: Format](
      in: Stream[F, Byte],
      maxFrame: Int,
      d: Decoder[A, Fmt]
  )(using RaiseThrowable[F]): Pull[F, A, Unit] =

    def overlong(size: Int): String =
      s"line of $size bytes exceeds the maxFrame limit of $maxFrame bytes"

    /** Splits an arriving chunk into the lines it completes and whatever is
      * left over.
      *
      * Blank lines are dropped and a trailing `\r` is cut, which is what lets a
      * file written with CRLF terminators — or with a blank line between
      * records — read back as the records alone.
      *
      * The window is `(values, from, until)` rather than a `Chunk`, because
      * `Chunk#indexWhere` scans through the boxed iterator: for messages of a
      * kilobyte that costs more per line than decoding the line does. One
      * `toArraySlice` per arriving chunk — free for the array-backed chunks a
      * socket or a file produces — buys a plain array scan instead.
      */
    @tailrec def split(
        pending: Chunk[Byte],
        values: Array[Byte],
        from: Int,
        until: Int,
        out: List[Chunk[Byte]]
    ): Either[String, (List[Chunk[Byte]], Chunk[Byte])] =
      val nl = indexOfLf(values, from, until)
      if nl == until then
        val acc = pending ++ Chunk.array(values, from, until - from)
        if acc.size > maxFrame then Left(overlong(acc.size))
        else Right((out.reverse, acc))
      else
        val line = pending ++ Chunk.array(values, from, nl - from)
        if line.size > maxFrame then Left(overlong(line.size))
        else
          // The last index of a chunk is the one index a `Chunk.Queue`
          // answers in constant time, so this costs nothing even mid-stitch.
          val trimmed =
            if line.nonEmpty && line(line.size - 1) == Cr then
              line.take(line.size - 1)
            else line
          split(
            Chunk.empty,
            values,
            nl + 1,
            until,
            if trimmed.isEmpty then out else trimmed :: out
          )

    def emit(lines: List[Chunk[Byte]]): Pull[F, A, Unit] =
      lines match
        case Nil          => Pull.done
        case line :: rest =>
          d.decode(line.asByteSource) match
            case Right(a) => Pull.output1(a) >> emit(rest)
            case Left(e)  => Pull.raiseError[F](e)

    def go(pending: Chunk[Byte], s: Stream[F, Byte]): Pull[F, A, Unit] =
      s.pull.uncons.flatMap {
        case Some((hd, tl)) =>
          val slice = hd.toArraySlice[Byte]
          val from = slice.offset
          split(pending, slice.values, from, from + slice.length, Nil) match
            case Left(m)              => Pull.raiseError[F](framingError(m))
            case Right((lines, rest)) => emit(lines) >> go(rest, tl)
        case None =>
          // JSON Lines terminates its records rather than separating them, so a
          // final line with no `\n` behind it is a message that was cut off,
          // not the last message.
          if pending.isEmpty then Pull.done
          else
            Pull.raiseError[F](framingError("truncated frame at end of stream"))
      }

    go(Chunk.empty, in)
