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

import b8.Format.Proto
import b8.scalapb.ProtoFixtures
import b8.scalapb.given
import b8.scalapb.protos.PKind
import b8.scalapb.protos.PNested

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

import fs2.Fallible
import fs2.Pipe
import fs2.Stream

/** `Framing.Varint` is protobuf's delimited encoding, held against protobuf.
  *
  * The scaladoc on `Framing.Varint` claims that a stream framed this way "is
  * exactly what `writeDelimitedTo` writes and what `parseDelimitedFrom` reads".
  * That is an interoperability promise about somebody else's format, so the
  * only test worth writing compares b8 against that somebody — ScalaPB — rather
  * than against b8. A suite that encoded and decoded with b8 alone would pass
  * just as happily on a length prefix b8 had invented.
  *
  * Three assertions, in increasing strength. b8 reads what ScalaPB wrote;
  * ScalaPB reads what b8 wrote; and the two byte streams are the same bytes.
  * The last one subsumes the first two for these messages, but not in general —
  * a reader is allowed to accept more than one writer emits, and the two
  * directions are what a caller actually depends on — so all three are here and
  * a failure in any of them says something different about where the fault is.
  *
  * The messages are chosen for the widths of the length prefix they produce:
  * `pNested1` is around a kilobyte and needs a two-byte varint, the
  * single-field message needs one byte, and the default instance encodes to
  * zero bytes and so declares a length of zero. That last one is the case a
  * hand-written framing gets wrong — protobuf writes a `0x00` and reads it back
  * as a message, and treating it as end-of-input would truncate the stream
  * silently.
  */
class VarintInteropSuite extends munit.FunSuite:

  private val messages: List[PNested] = List(
    ProtoFixtures.pNested1,
    PNested(),
    PNested(kind = PKind.BETA),
    ProtoFixtures.pNested1
  )

  /** The wire ScalaPB writes, through protobuf's own `CodedOutputStream`. */
  private val scalapbWire: List[Byte] =
    val out = new ByteArrayOutputStream()
    messages.foreach(_.writeDelimitedTo(out))
    out.toByteArray.toList

  /** The wire b8 writes. Encoding is total, so the `Left` branch is
    * unreachable; it is spelled out rather than ignored so that a failure here
    * names itself instead of arriving as a `NoSuchElementException`.
    */
  private val b8Wire: List[Byte] =
    Stream
      .emits(messages)
      .covary[Fallible]
      .through(b8.stream.encode[Proto](Framing.Varint))
      .compile
      .toList
      .getOrElse(fail("b8 failed to encode a list of well-formed messages"))

  /** Reads a delimited stream the way protobuf documents it: call
    * `parseDelimitedFrom` until it answers `None`, which is how a reader learns
    * the input ended rather than being told.
    */
  private def parseDelimited(bytes: List[Byte]): List[PNested] =
    val in = new ByteArrayInputStream(bytes.toArray)
    Iterator
      .continually(PNested.parseDelimitedFrom(in))
      .takeWhile(_.isDefined)
      .flatten
      .toList

  /** The ascribed `Pipe` is what tells `decode` which message to read. A
    * `Pipe[F, Byte, A]` leaves `A` open and the `Decoder` lookup needs it
    * settled; the enclosing method's result type does not reach back through
    * `.compile.toList` to supply it, so without this line the compiler never
    * gets as far as `PNested`'s companion.
    */
  private def decodeVarint(
      bytes: List[Byte]
  ): Either[Throwable, List[PNested]] =
    val pipe: Pipe[Fallible, Byte, PNested] =
      b8.stream.decode[Proto](Framing.Varint)
    Stream.emits(bytes).covary[Fallible].through(pipe).compile.toList

  test("b8 reads what scalapb's writeDelimitedTo wrote") {
    assertEquals(decodeVarint(scalapbWire), Right(messages))
  }

  test("scalapb's parseDelimitedFrom reads what b8 wrote") {
    assertEquals(parseDelimited(b8Wire), messages)
  }

  test("both sides write the same bytes") {
    // The strongest form of the claim, and the reason the two directions above
    // can be trusted for messages other than these: nothing about b8's output
    // is merely *acceptable* to protobuf, it is byte for byte what protobuf
    // itself produces. Compared as lists rather than arrays, because
    // `Array[Byte]` compares by identity and `assertEquals` would pass on two
    // arrays that differ.
    assertEquals(b8Wire, scalapbWire)
    // Sanity on the fixture rather than on the code: if every message were
    // small, the two-byte varint length would never be exercised and the
    // agreement above would say less than it looks like it says.
    assert(b8Wire.size > 1024, clue(b8Wire.size))
  }

  test("the default instance is a frame of length zero") {
    // Spelled out because it is what the round-trip properties cannot show:
    // that the empty frame is one byte on the wire, `0x00`, and that b8 and
    // protobuf agree it is a message. `PNested()` sits second in the list, so
    // the byte after the first message's bytes end is the one to look at.
    val firstLength = messages.head.serializedSize
    // Stated rather than assumed: a length in this range is two varint bytes,
    // so a change to `nested1` that moved it out of the range fails here with
    // the number rather than somewhere below with a byte.
    assert(firstLength > 0x7f && firstLength <= 0x3fff, clue(firstLength))
    val zeroAt = 2 + firstLength
    assertEquals(b8Wire(zeroAt), 0.toByte)
    assertEquals(parseDelimited(List(0.toByte)), List(PNested()))
  }
