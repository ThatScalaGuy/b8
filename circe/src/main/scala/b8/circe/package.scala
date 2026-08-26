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

package b8.circe

import b8.Codec
import b8.Decoder
import b8.Encoder
import b8.Format.Json

import io.circe.Printer
import io.circe.jawn.JawnParser

import scala.util.NotGiven

/** Builds the b8 encoder for `A` from its circe encoder.
  *
  * @param printer
  *   how the JSON is rendered; the default is the most compact form circe
  *   prints, which is what a wire format wants
  */
def encoder[A](printer: Printer = Printer.noSpaces)(using
    io.circe.Encoder[A]
): Encoder[A, Json] =
  CirceEncoder(printer)

/** Builds the b8 decoder for `A` from its circe decoder.
  *
  * The default parser is written `new JawnParser` because `JawnParser()` does
  * not compile — the companion only has `apply` overloads that take arguments.
  * It stands for the same settings jawn's own default has: no limit on the size
  * of a single value, and duplicate object keys allowed, last one wins.
  */
def decoder[A](parser: JawnParser = new JawnParser)(using
    io.circe.Decoder[A]
): Decoder[A, Json] =
  CirceDecoder(parser)

/** Builds the b8 codec for `A` from its circe encoder and decoder.
  *
  * The default parser is written `new JawnParser` because `JawnParser()` does
  * not compile — the companion only has `apply` overloads that take arguments.
  * It stands for the same settings jawn's own default has: no limit on the size
  * of a single value, and duplicate object keys allowed, last one wins.
  */
def codec[A](
    printer: Printer = Printer.noSpaces,
    parser: JawnParser = new JawnParser
)(using io.circe.Encoder[A], io.circe.Decoder[A]): Codec[A, Json] =
  CirceCodec(printer, parser)

/** Every type circe can both print and read gets the codec, with the default
  * printer and parser.
  *
  * This one and the two below never compete: the one-way givens ask for the
  * absence of the other circe instance, so for any given type at most one of
  * the three applies. A type with both instances therefore also summons as an
  * `Encoder` or a `Decoder` on its own — through this codec, unambiguously.
  */
given [A](using io.circe.Encoder[A], io.circe.Decoder[A]): Codec[A, Json] =
  codec()

/** Write-only bridge, for the types circe can print but not read back. */
given [A](using
    io.circe.Encoder[A],
    NotGiven[io.circe.Decoder[A]]
): Encoder[A, Json] =
  encoder()

/** Read-only bridge, for the types circe can read but not print. */
given [A](using
    io.circe.Decoder[A],
    NotGiven[io.circe.Encoder[A]]
): Decoder[A, Json] =
  decoder()
