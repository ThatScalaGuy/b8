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

import b8.Format

/** How messages are delimited on the wire.
  *
  * A stream of encoded values is not a stream of messages until something says
  * where one ends and the next begins. No backend answers that — an encoder
  * writes a message and stops — so it is the one piece b8 has to supply itself.
  *
  * The type parameter is the format the framing is legal for, and it is
  * contravariant, so a `Framing[Format]` is accepted wherever a
  * `Framing[Format.Json]` is asked for. That is what makes `Newline`, declared
  * as a `Framing[Format.Text]`, a compile error against `Format.Proto` and
  * accepted for `Format.Json`: newline framing is only sound where the encoding
  * cannot contain a `0x0A` byte of its own.
  */
enum Framing[-Fmt <: Format]:

  /** A four-byte big-endian unsigned length in front of each message.
    *
    * The default. Fixed cost, no scanning, and a header that can be read with
    * one bounds check.
    */
  case Fixed32 extends Framing[Format]

  /** A protobuf LEB128 varint length in front of each message.
    *
    * Wire-compatible with protobuf's own delimited encoding, so a stream framed
    * this way is exactly what `writeDelimitedTo` writes and what
    * `parseDelimitedFrom` reads.
    */
  case Varint extends Framing[Format]

  /** One message per `\n`-terminated line, JSON Lines style.
    *
    * Text formats only, which the type enforces. Empty lines are skipped and a
    * trailing `\r` is stripped, so a stream written with CRLF terminators — or
    * with a blank line between records — reads back unchanged.
    */
  case Newline extends Framing[Format.Text]

object Framing:

  /** Largest frame a decode pipe accepts before failing, in bytes: 16 MiB.
    *
    * A length prefix is attacker-controlled input. Without a ceiling, four
    * bytes on the wire can ask a decoder to reserve two gigabytes, so the
    * default is a limit rather than none, and it is the decode side that
    * carries it — encoders are total and have nothing to refuse.
    */
  val DefaultMaxFrame: Int = 16 * 1024 * 1024
