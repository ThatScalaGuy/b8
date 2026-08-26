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

package b8.laws

import b8.*

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

import org.scalacheck.Arbitrary
import org.scalacheck.Prop.forAll
import org.scalacheck.Properties

/** The behaviour every b8 bridge owes its users, as a scalacheck `Properties`.
  *
  * A bridge is a thin layer over somebody else's encoder, and the mistakes are
  * always the same ones: writing into the sink's array without honouring its
  * position, handing a decoder the whole backing array instead of the window it
  * was given, or keeping state across calls. These laws pin exactly that down.
  */
object CodecLaws:

  /** Builds the law set for `A` in format `F`.
    *
    * @param name
    *   prefix for the property names; scalacheck reports them as
    *   `name.roundTrip` and so on, so a name like `"circe.Flat"` reads well in
    *   test output
    * @param trailing
    *   bytes appended to a valid encoding that the decoder has to reject. Pass
    *   `None` for formats that cannot detect trailing input — a raw UTF-8
    *   string, say, where any appended byte is just a longer string. `None`
    *   leaves the `trailingRejected` property out of the set entirely rather
    *   than registering one that asserts nothing.
    * @param eq
    *   equality used to compare a decoded value against the original, for the
    *   types whose `==` is not what the round trip should be judged by
    */
  def apply[A, F <: Format](
      name: String,
      trailing: Option[Array[Byte]] = Some(Array(0.toByte)),
      eq: (A, A) => Boolean = (x: A, y: A) => x == y
  )(using codec: Codec[A, F], arb: Arbitrary[A]): Properties =
    // `eq` is also `AnyRef.eq`, which the `Properties` subclass below inherits
    // and which would make every reference to it ambiguous. Bind it out here.
    val equal = eq
    new Properties(name):

      /** Encoding into a sink that starts far too small, so every sample runs
        * through the growth path — the one where a backend that cached
        * `sink.buffer` across an `ensure` writes into a stale array.
        */
      private def viaArraySink(a: A): Array[Byte] =
        val sink = ArraySink(1)
        codec.encodeTo(a, sink)
        sink.result()

      /** `ByteBufferSink` writes from the buffer's current position on and
        * never flips, so the caller flips before reading the bytes back.
        */
      private def viaByteBufferSink(a: A, size: Int): Array[Byte] =
        val bb = ByteBuffer.allocate(size)
        codec.encodeTo(a, ByteBufferSink(bb))
        bb.flip()
        val out = new Array[Byte](bb.remaining())
        bb.get(out)
        out

      private def viaOutputStreamSink(a: A): Array[Byte] =
        val os = new ByteArrayOutputStream()
        codec.encodeTo(a, OutputStreamSink(os))
        os.toByteArray

      private def sameResult(
          x: Either[DecodeError, A],
          y: Either[DecodeError, A]
      ): Boolean =
        (x, y) match
          case (Right(a), Right(b)) => equal(a, b)
          case (Left(_), Left(_))   => true
          case _                    => false

      // What a codec is for. Breaks when the encoder drops a field the decoder
      // expects, or when either side disagrees about the character encoding.
      property("roundTrip") = forAll { (a: A) =>
        codec.decode(ByteSource(codec.encode(a))) match
          case Right(b) => equal(a, b)
          case Left(_)  => false
      }

      // The same value has to produce the same bytes every time. Breaks on
      // encoders that iterate a hash map in an unstable order, or that keep a
      // buffer of their own and forget to reset it between calls.
      property("deterministic") = forAll { (a: A) =>
        codec.encode(a).sameElements(codec.encode(a))
      }

      // Where the bytes go must not change what the bytes are. Breaks when the
      // encoder writes straight into `ArraySink.buffer` and ignores `position`,
      // or when it takes the array fast path for one sink and the byte-at-a-
      // time path for another.
      property("sinkIndependence") = forAll { (a: A) =>
        val expected = viaArraySink(a)
        expected.sameElements(viaByteBufferSink(a, expected.length)) &&
        expected.sameElements(viaOutputStreamSink(a))
      }

      // A `ByteSource` is a window into somebody else's array: the same bytes
      // at offset 17 of a larger buffer must decode to the same value as at
      // offset 0. Breaks whenever a decoder passes `in.array` on without
      // `in.offset` and `in.length` — which happens to work for every test that
      // only ever decodes freshly allocated arrays.
      property("sourceIndependence") = forAll {
        (a: A, prefix: Array[Byte], suffix: Array[Byte]) =>
          val bytes = codec.encode(a)
          // A non-empty prefix is what forces the non-zero offset.
          val head = if prefix.isEmpty then Array(0x7f.toByte) else prefix
          val padded = head ++ bytes ++ suffix
          val direct = codec.decode(ByteSource(bytes))
          val windowed =
            codec.decode(ByteSource(padded, head.length, bytes.length))
          val buffered =
            codec.decode(
              ByteSource(ByteBuffer.wrap(padded, head.length, bytes.length))
            )
          sameResult(direct, windowed) && sameResult(direct, buffered)
      }

      // A decoder consumes the whole source: bytes left over are malformed
      // input, not a partial read. Breaks on backends that stop at the end of
      // the first value and never look at the rest of the window.
      trailing.foreach { garbage =>
        property("trailingRejected") = forAll { (a: A) =>
          codec.decode(ByteSource(codec.encode(a) ++ garbage)).isLeft
        }
      }

      /** One pool for the whole property rather than one per sample.
        *
        * `property(name) = body` stores the body as `Prop.delay`, which
        * re-evaluates it for every generated value. A pool built inside the
        * body would therefore be a fresh one each time, hand out a virgin sink
        * each time, and the law would never once see the recycled sink it
        * exists to check.
        */
      private val pooled: SinkPool = SinkPool.threadLocal()

      // From the second sample on, the pooled sink is one that has already been
      // written to and reset. Breaks on encoders that assume a fresh zeroed
      // array, that write at index 0 instead of the sink's position, or that
      // hold on to the sink after `encode` returned.
      property("pooledEquivalent") = forAll { (a: A) =>
        codec
          .encode(a)(using pooled)
          .sameElements(codec.encode(a)(using SinkPool.none))
      }
