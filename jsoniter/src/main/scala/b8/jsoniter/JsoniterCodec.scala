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

package b8.jsoniter

import b8.ArraySink
import b8.ByteSink
import b8.ByteSource
import b8.Codec
import b8.DecodeError
import b8.Format.Json

import com.github.plokhotnyuk.jsoniter_scala.core.JsonReaderException
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.core.ReaderConfig
import com.github.plokhotnyuk.jsoniter_scala.core.WriterConfig
import com.github.plokhotnyuk.jsoniter_scala.core.readFromSubArray
import com.github.plokhotnyuk.jsoniter_scala.core.readFromSubArrayReentrant
import com.github.plokhotnyuk.jsoniter_scala.core.writeToStream
import com.github.plokhotnyuk.jsoniter_scala.core.writeToStreamReentrant
import com.github.plokhotnyuk.jsoniter_scala.core.writeToSubArray
import com.github.plokhotnyuk.jsoniter_scala.core.writeToSubArrayReentrant

/** Both directions of the jsoniter-scala bridge for one type.
  *
  * jsoniter generates its codecs at compile time and writes UTF-8 bytes without
  * building a tree, so the fastest thing the bridge can do is get out of the
  * way: `writeToSubArray` renders straight into the `ArraySink`'s own array,
  * and `readFromSubArray` parses the caller's array where it lies. Nothing is
  * copied in either direction and no `String`, `ByteBuffer` or scratch array
  * appears on the way.
  *
  * @param writer
  *   jsoniter's encoding settings; `indentionStep` turns on pretty printing
  * @param reader
  *   jsoniter's decoding settings; `checkForEndOfInput` is what rejects
  *   trailing bytes
  * @param reentrant
  *   selects jsoniter's `*Reentrant` entry points, which allocate a fresh
  *   reader or writer per call instead of taking the thread's pooled one. See
  *   `b8.jsoniter.codec` for when that is required.
  */
final class JsoniterCodec[A](
    writer: WriterConfig,
    reader: ReaderConfig,
    reentrant: Boolean
)(using codec: JsonValueCodec[A])
    extends Codec[A, Json]:

  /** Size of the last encoding this instance produced.
    *
    * A plain `var`, read and written without synchronisation, and that is the
    * whole design: a codec is shared across threads, but the field only ever
    * holds a size, a torn or stale read only makes the next hint wrong, and a
    * wrong hint only costs one retry. Making it `@volatile` would put a memory
    * barrier on the encode path to protect a number nothing depends on.
    */
  private var lastSize: Int = 256

  /** The last encoded size plus a quarter, plus a fixed 32 bytes.
    *
    * Both terms earn their place. The quarter absorbs the ordinary spread
    * between one value of `A` and the next. The 32 bytes are jsoniter's, and
    * without them the hint is wrong nearly every time: `writeToSubArray`
    * reserves room for a whole token before it writes one, so a buffer sized to
    * the *exact* encoding overflows on the last field and forces a re-encode.
    * Measured over the law fixtures and a scan of pathological inputs, that
    * reservation never exceeded 21 bytes, and it does not grow with the payload
    * — which is why this is a constant and not a third proportion.
    */
  override def sizeHint(a: A): Int =
    val size = lastSize
    math
      .min(
        size.toLong + (size >>> 2) + JsoniterCodec.Headroom,
        JsoniterCodec.MaxCapacity.toLong
      )
      .toInt

  def encodeTo(a: A, out: ByteSink): Unit =
    out match
      case s: ArraySink => encodeToArray(a, s)
      case other        =>
        val os = other.asOutputStream
        if reentrant then writeToStreamReentrant(a, os, writer)
        else writeToStream(a, os, writer)

  /** The fast path: jsoniter writes into the sink's array, at the sink's
    * position, and the sink is told about it afterwards.
    *
    * `writeToSubArray` cannot grow what it is given, so the loop exists to make
    * the sink big enough and try again. Three things make that safe rather than
    * merely plausible:
    *
    *   - A failed attempt leaves nothing behind. The sink's `position` is only
    *     moved by `advance`, which runs after a successful write, so the
    *     half-written bytes sit beyond `position` where `result()` and the next
    *     attempt both ignore them.
    *   - jsoniter's own writer survives the throw. The sub-array entry point
    *     restores the pooled writer's buffer in a `finally`, and every entry
    *     point re-initialises the rest on the way in.
    *   - The loop is bounded. `want` at least quadruples per round from a
    *     positive start, so the ceiling is reached in a handful of iterations
    *     even in the worst case.
    *
    * `to` is the sink's whole capacity rather than `position + want`: a sink
    * that is already large — a pooled one, or one the caller sized — then has
    * all of its room available on the first attempt, and the loop never runs a
    * second time.
    *
    * The `catch` is as narrow as jsoniter allows, and it is worth being precise
    * about how narrow that is. The overflow is a plain
    * `ArrayIndexOutOfBoundsException` carrying a fixed message, so an ordinary
    * indexing bug inside a hand-written `JsonValueCodec` — which carries a
    * different message, or none — propagates untouched. What cannot be told
    * apart is a codec that writes without bound: it produces the identical
    * exception from the identical throw site. That is what the ceiling is for.
    * Comparing the constant against `getMessage`, and not the other way round,
    * is what keeps a `null` message from turning into an exception of its own.
    */
  private def encodeToArray(a: A, s: ArraySink): Unit =
    var want = sizeHint(a)
    var done = false
    while !done do
      s.ensure(want)
      val from = s.position
      try
        val end =
          if reentrant then
            writeToSubArrayReentrant(a, s.buffer, from, s.capacity, writer)
          else writeToSubArray(a, s.buffer, from, s.capacity, writer)
        // jsoniter returns the position after the last byte it wrote, not the
        // number of bytes; the sink wants the count.
        val written = end - from
        s.advance(written)
        lastSize = written
        done = true
      catch
        case e: ArrayIndexOutOfBoundsException
            if JsoniterCodec.Overflow == e.getMessage =>
          if want >= JsoniterCodec.MaxCapacity then throw e
          want = math
            .min(
              math.max(want.toLong * 4, (s.capacity - from).toLong * 2),
              JsoniterCodec.MaxCapacity.toLong
            )
            .toInt

  /** Reads the window where it lies: `readFromSubArray` takes the caller's
    * array with a start and an end, keeps no reference to it once it returns,
    * and never copies the bytes. `in.toArray` appears nowhere.
    *
    * Only `JsonReaderException` becomes a `DecodeError`, and only here.
    * jsoniter raises it for everything that is wrong with the *input* —
    * malformed syntax, a missing required field, a wrong type, and, because
    * `checkForEndOfInput` is on by default, bytes left over after the value.
    * Its message already ends in `, offset: 0x…`, so the position is not
    * appended a second time.
    *
    * Anything else propagates unwrapped, which is a real difference from the
    * circe and borer bridges and worth stating plainly: a bug in a hand-written
    * `JsonValueCodec` — a `MatchError`, an `IllegalStateException` — comes out
    * of `decode` as itself rather than as a `Left`. jsoniter does not dress its
    * codecs' exceptions up as parse errors, and the bridge does not either;
    * calling every exception malformed input would report a broken codec as a
    * broken message.
    */
  def decodeUnsafe(in: ByteSource): A =
    val to = in.offset + in.length
    try
      if reentrant then
        readFromSubArrayReentrant(in.array, in.offset, to, reader)
      else readFromSubArray(in.array, in.offset, to, reader)
    catch
      case e: JsonReaderException => throw DecodeError(e.getMessage, "Json", e)

private object JsoniterCodec:

  /** The message `writeToSubArray` throws when the target is too small.
    * Verbatim from `JsonWriter.flushAndGrowBuf`, backticks included.
    */
  final val Overflow: String = "`buf` length exceeded"

  /** Room for the token jsoniter reserves before writing it; see `sizeHint`. */
  final val Headroom: Int = 32

  /** The largest array `ArraySink` will grow to, and therefore the point at
    * which a retry has stopped being a retry.
    */
  final val MaxCapacity: Int = Int.MaxValue - 8
