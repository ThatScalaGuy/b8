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

package b8.scalapb

import b8.ArraySink
import b8.ByteSource
import b8.Decoder
import b8.Format.Proto
import b8.array.*
import b8.scalapb.given
import b8.scalapb.protos.PFlat
import b8.scalapb.protos.PNested

/** The bridge as a user meets it: two imports, and from there encoding and
  * decoding are methods on the values themselves.
  *
  * Two is the whole list, and that is one fewer than the jsoniter bridge asks
  * for. There is no user-visible codec to bring here. The
  * `GeneratedMessageCompanion` ScalaPB already put in each message's own
  * companion object is the evidence the given picks up, so a message type
  * arrives carrying everything the bridge needs and nothing has to be derived,
  * summoned or imported per type.
  *
  * The suite sits inside `b8.scalapb`, so `import b8.scalapb.given` names a
  * given that is a package member here anyway. It is written out because it is
  * the line a user elsewhere has to write, and showing that shape is the point
  * of the suite.
  */
class ExtensionSuite extends munit.FunSuite:

  private val encoded: Array[Byte] = ProtoFixtures.pNested1.encode[Proto]

  test("encode and decode are inverses") {
    assertEquals(
      ProtoFixtures.pFlat1.encode[Proto].decodeAs[PFlat, Proto],
      Right(ProtoFixtures.pFlat1)
    )
    assertEquals(
      ProtoFixtures.pNested1.encode[Proto].decodeAs[PNested, Proto],
      Right(ProtoFixtures.pNested1)
    )
  }

  test("encodeTo writes into a sink the caller brought") {
    // Deliberately far too small. The encoder reserves the message's exact
    // `serializedSize` in a single `ensure` and writes straight into the
    // sink's own array, so a sink that starts at one byte has to grow before
    // that reservation can be met at all.
    val sink = ArraySink(1)
    ProtoFixtures.pNested1.encodeTo[Proto](sink)
    assert(sink.result().sameElements(encoded))
  }

  test("a window of a larger array decodes to the same value") {
    val padded = Array[Byte](0x7f, 0x7f, 0x7f) ++ encoded ++ Array[Byte](0x7f)
    // `decodeAs` reads a whole array, so a window into a bigger one is spelled
    // out with the `ByteSource` that extension wraps around it.
    assertEquals(
      Decoder[PNested, Proto].decode(ByteSource(padded, 3, encoded.length)),
      Right(ProtoFixtures.pNested1)
    )
    // And the whole array really is a different input: the padding is not
    // quietly skipped. The byte 0x7f reads as a tag for field number 15 with
    // wire type 7, and protobuf defines no wire type 7, so the parse stops on
    // the first padding byte and never reaches the message behind it.
    assert(padded.decodeAs[PNested, Proto].isLeft)
  }
