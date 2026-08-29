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

import scala.collection.mutable

import fs2.Chunk
import fs2.Fallible
import fs2.Stream

/** Test-only text format, so that all three framings are legal for it. */
trait Probe extends Format.Text

/** What the decode pipe hands its decoder, and whether it copied it first.
  *
  * The decode side claims that a frame which arrived inside one chunk reaches
  * the decoder in place, and the cost table on the docs page is built on that
  * claim. It is exactly the kind of promise a round trip cannot see: a copy
  * decodes to the same value, in the same order, with the same errors, and
  * every other suite in this module would stay green if one were added. Put
  * `.compact[Byte]` anywhere in `Framed.prefixed` or `Framed.newlines` and this
  * is the only file that notices.
  *
  * So the codec here reports the array it was given, and the assertions are
  * reference equality against the array the input chunk was built over — the
  * same technique, and the same reasoning, as `b8.chunk.AsByteSourceSuite` one
  * layer down.
  *
  * The copying case is here too, and it asserts only what is actually promised.
  * A frame stitched together from two chunks has to be copied, because a
  * decoder needs its input in one piece; what would be a bug is copying the
  * other one.
  */
class ZeroCopySuite extends munit.FunSuite:

  /** A codec that keeps every array its decoder was handed.
    *
    * The encoding is the string's UTF-8 bytes and nothing around them, so the
    * frame body is precisely whatever the pipe sliced out and there is no
    * backend in the way of the question being asked.
    */
  private final class Recorder extends Codec[String, Probe]:
    val arrays: mutable.ListBuffer[Array[Byte]] = mutable.ListBuffer.empty

    def encodeTo(a: String, out: ByteSink): Unit = out.write(a.getBytes(UTF_8))
    override def sizeHint(a: String): Int = a.length

    def decodeUnsafe(in: ByteSource): String =
      arrays += in.array
      new String(in.array, in.offset, in.length, UTF_8)

  private val framings: List[Framing[Probe]] =
    List(Framing.Fixed32, Framing.Varint, Framing.Newline)

  /** Long enough that a copy would be worth noticing, and free of `0x0a` so
    * that newline framing is legal for it.
    */
  private val message = "the quick brown fox jumps over the lazy dog"

  /** The frame for `message`, flattened into an array of its own — the shape a
    * socket read or a file read hands the pipe.
    */
  private def frameBytes(framing: Framing[Probe]): Array[Byte] =
    given Codec[String, Probe] = new Recorder
    Stream
      .emit(message)
      .through(encode[Probe](framing))
      .compile
      .to(Array)

  test("a frame that arrives in one chunk reaches the decoder in place") {
    for framing <- framings do
      val recorder = new Recorder
      given Codec[String, Probe] = recorder

      val backing = frameBytes(framing)
      val decoded: Stream[Fallible, String] =
        Stream
          .chunk(Chunk.array(backing))
          .covary[Fallible]
          .through(decode[Probe](framing))

      assertEquals(decoded.compile.toList, Right(List(message)), framing)
      assertEquals(recorder.arrays.size, 1, framing)
      // The whole point. `Chunk.empty ++ hd` is `hd` by identity, `drop` and
      // `take` of an array-backed chunk stay windows on the same array, and
      // `asByteSource` hands that array through — so the decoder sees the very
      // bytes the stream delivered, at an offset, and nothing was duplicated.
      assert(
        recorder.arrays.head eq backing,
        s"$framing copied a frame that arrived whole"
      )
  }

  test("a frame stitched from two chunks is copied, once") {
    for framing <- framings do
      val recorder = new Recorder
      given Codec[String, Probe] = recorder

      val backing = frameBytes(framing)
      // Cut inside the body rather than at the header, so that the pipe has to
      // join the pieces rather than merely wait for them.
      val cut = backing.length - 7
      val decoded: Stream[Fallible, String] =
        Stream
          .chunk(Chunk.array(backing, 0, cut))
          .append(Stream.chunk(Chunk.array(backing, cut, backing.length - cut)))
          .covary[Fallible]
          .through(decode[Probe](framing))

      assertEquals(decoded.compile.toList, Right(List(message)), framing)
      assertEquals(recorder.arrays.size, 1, framing)
      // Copied — and that is the promise, not a defect: the two halves are not
      // adjacent in memory, and a `ByteSource` is a window on one array.
      assert(
        recorder.arrays.head ne backing,
        s"$framing decoded from an array it could not have been holding"
      )
      assert(
        recorder.arrays.head.length >= message.length,
        clue(recorder.arrays.head.length)
      )
  }
