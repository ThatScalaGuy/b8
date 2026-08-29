# fs2

`b8-fs2` is not a bridge. It adds no backend and no format, and like [`b8-scodec`](scodec.md) it fills in
the third axis, the target container: `b8.chunk` does for [fs2](https://fs2.io)'s `Chunk[Byte]` exactly
what `b8.array` does for `Array[Byte]`, with whatever bridge you already have in scope.

Then it does one thing no other module does. `b8.stream` adds **framing** — a pair of pipes that turn a
stream of values into a stream of bytes and back. That part is not a convenience wrapper over the
container, it is a piece of the problem no backend solves: an encoder writes one message and stops, so a
reader handed two messages back to back has no way to tell where the first one ended. Every wire protocol
answers that question somehow, and until now b8 left you to answer it yourself.

## Installation

> **Not yet released.** The coordinates below are what the first release will publish to Maven Central for
> Scala 3.

```scala
libraryDependencies += "de.thatscalaguy" %% "b8-fs2" % "0.1.0"
```

Beyond `b8-core` the module depends on `fs2-core` and on nothing else — not on `fs2-io`, and not on a
parser, since it has no idea which format you are going to ask for. `fs2-core` brings cats-effect with
it, which is the point rather than a cost: the pipes are ordinary `Pipe[F, ?, ?]` values with no
constraint on `F` beyond what the operation needs, so they drop into the `IO` — or `Fallible`, or
`SyncIO`, or your own `F` — you are already running.

## Getting started

Two imports and a codec, the same as always, except that the container import is `b8.chunk.*`:

```scala mdoc:silent
import b8.Format
import b8.chunk.*
import b8.jsoniter.given

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

import fs2.Chunk

case class User(id: Long, name: String)

given JsonValueCodec[User] = JsonCodecMaker.make
```

From there the four methods read exactly like their `b8.array` counterparts:

```scala mdoc
val user = User(1L, "Ada")

val bytes: Chunk[Byte] = user.encode[Format.Json]

bytes.decodeAs[User, Format.Json]
```

`encodeTo` is unchanged and shared: it takes a `ByteSink`, which has nothing to do with the container, so
the version in `b8.chunk` is the version in `b8.array`. `decodeAsUnsafe` is the throwing sibling of
`decodeAs`, for callers who frame their own error handling.

## What it costs

`encode` returns an **exact-size** chunk, and it costs one copy — the same single copy an `Array[Byte]`
costs, and no more. `Encoder.encode` renders into the sink's buffer and trims the result into a fresh array
that nothing else references; `b8.chunk` puts a `Chunk.array` around that array rather than copying it
again.

`decodeAs` goes the other way through `asByteSource`, and what that costs depends on the **shape** of the
chunk rather than on its size:

- an **array-backed chunk of two or more bytes** — anything `Chunk.array` produced, including every
  `drop`, `take` and `splitAt` of one — is read **in place**, at the right offset, with no copy at all;
- a **chunk over a heap `ByteBuffer`** — `Chunk.byteBuffer(...)` — is read in place too: b8 takes the
  buffer's array and honours both its `arrayOffset` and its position;
- a **chunk over a direct `ByteBuffer`** is copied on every call, because a direct buffer exposes no array
  to read from;
- a **concatenation** — a `Chunk.Queue`, which is what `++` and most of fs2's batching operators hand you
  — is copied once per call, into one contiguous array, because a decoder needs its input in one piece;
- a **one-byte chunk** is copied, and that one is fs2's rule rather than b8's: `Chunk.array` answers a
  one-element array with a `Chunk.Singleton` and an empty one with `Chunk.empty`, and neither keeps the
  array it was handed. The copy costs one byte.

Here is the difference in the two shapes that actually turn up. A slice is a view on the array it was cut
from; a message that arrived in two reads and was stitched back together is not:

```scala mdoc:silent
val backing = bytes.toArraySlice[Byte].values

val (head, tail) = bytes.splitAt(10)
val reassembled = head ++ tail
val payload = reassembled.compact[Byte]
```

```scala mdoc
bytes.drop(4).asByteSource.array eq backing

bytes.drop(4).asByteSource.offset

reassembled.asByteSource.array eq backing

payload.asByteSource.array eq backing
```

Both `reassembled` and `payload` decode, and both give the same value back; the difference is that the
first copies the message every time it is decoded and the second copied it once.

```scala mdoc
reassembled.decodeAs[User, Format.Json]

payload.decodeAs[User, Format.Json]
```

`compact` is the fix for a concatenation and for a direct buffer alike — it is the one call that
materializes an array behind a chunk that has none to share. Pass its type argument, as above: `compact`
and `toArraySlice` both pick their `ClassTag` from an `O2 >: O` you can let the compiler guess, and a
guess wider than `Byte` boxes the bytes into an `Array[Object]` with no warning and no error.

## Wiring your own decoding

`asByteSource` is the same conversion `decodeAs` uses, exposed for callers who do not want the extension
methods — because they are dispatching on a frame header, or reading one value out of a longer buffer, or
holding a `Decoder` they summoned themselves.

```scala mdoc
import b8.Decoder

Decoder[User, Format.Json].decode(payload.asByteSource)
```

It never copies more than once, and the `ByteSource` it hands back carries the chunk's own offset, so there
is no arithmetic to get wrong on your side.

## One container per file

`b8.array`, `b8.vector` and `b8.chunk` declare the same names — the four above, and `asByteSource` for
the two containers that have one. Importing two of them in one scope makes `encode` ambiguous and the
file stops compiling:

```scala
import b8.array.*
import b8.chunk.*

user.encode[Format.Json]
// value encode is not a member of User.
// An extension method was tried, but could not be fully constructed:
//
//     b8.chunk.encode[A](user)
//
// failed with:
//
//     Reference to encode is ambiguous.
//     It is both imported by import b8.array._
//     and imported subsequently by import b8.chunk._
```

That is deliberate, and it is the better failure. The alternative — one of the two silently winning — is
what happens with two JSON *bridges* in one scope, where the givens are anonymous and the second import
shadows the first. Here the names collide in the open, so the compiler asks the question instead of
answering it for you.

One container per file, then, the same rule as one backend per format. A file that genuinely needs both
containers can put each import in its own scope:

```scala mdoc:silent
object asArray:
  import b8.array.*
  def apply(u: User): Array[Byte] = u.encode[Format.Json]

object asChunk:
  import b8.chunk.*
  def apply(u: User): Chunk[Byte] = u.encode[Format.Json]
```

## The pipes

`b8.stream` has two entry points, and they mirror each other:

```scala
b8.stream.encode[Fmt](framing): Pipe[F, A, Byte]

b8.stream.decode[Fmt](framing, maxFrame): Pipe[F, Byte, A]
```

The format is the only type argument you write down. `F` and `A` are inferred where the pipe meets the
stream, and the encoder or decoder is found the usual way, from whichever bridge is in scope. Here is a
round trip through JSON Lines, in `Fallible` so that the snippet needs no effect runtime to run — an `IO`
would need an `IORuntime` and a `.unsafeRunSync()` and would be the same three lines otherwise:

```scala mdoc:silent
import b8.stream.Framing

import fs2.Fallible
import fs2.Stream

val users = List(User(1L, "Ada"), User(2L, "Grace"))
```

```scala mdoc
val lines: Stream[Fallible, Byte] =
  Stream.emits(users).through(b8.stream.encode[Format.Json](Framing.Newline))

lines.through(fs2.text.utf8.decode).compile.string

lines.through(b8.stream.decode[Format.Json](Framing.Newline)).compile.toList
```

Both arguments have defaults — `Framing.Fixed32` and a 16 MiB `maxFrame` — but the empty parameter list
still has to be written, because `b8.stream.encode[Format.Json]` on its own is the builder that holds the
format rather than the pipe itself:

```scala mdoc:silent
val fixed: Stream[Fallible, Byte] =
  Stream.emits(users).covary[Fallible].through(b8.stream.encode[Format.Json]())
```

Two places bite, and both are worth knowing before they happen to you.

The first is that a pipe has nothing to infer `F` and `A` from unless something asks for it, so binding one
to a bare `val` does not compile. Ascribe it, and the same ascription is what to reach for whenever the
element type cannot be recovered from the surroundings — after `.through(...)`, for instance, where the
next operator is `.compile.toList.map(...)` and nothing has pinned `A` down yet:

```scala mdoc:silent
val toJsonl: fs2.Pipe[Fallible, User, Byte] =
  b8.stream.encode[Format.Json](Framing.Newline)
```

The second is the reason `covary` is in the snippet above. Widening a `Stream[Pure, A]` to the effect you
actually run — the shape a literal source has in a snippet, and the shape it almost never has in an
application — is one inference too many when it happens in the same step as the pipe's own, and it does not
fail politely:

```scala
val fixed: Stream[Fallible, Byte] =
  Stream.emits(users).through(b8.stream.encode[Format.Json]())
// Recursion limit exceeded.
// Maybe there is an illegal cyclic reference?
// ...
// A recurring operation is (inner to outer):
//
//   traversing for avoiding local references fs2.Pipe[F, A, Byte]
//   traversing for avoiding local references (using x$2: b8.Encoder[A, b8.Format.Json]): fs2.Pipe[F, A, Byte]
```

`covary[F]` the source first and it goes away. Naming the framing instead of taking the default is enough
on the encode side; on the decode side it is not, because that pipe also asks for a `RaiseThrowable[F]` and
`F` is the thing still being guessed. So: covary, then `through`. A source that is already in `F` — a
socket, a file, a queue — never raises the question at all.

## Framing

| Framing           | On the wire                                   | Legal for         |
| ----------------- | --------------------------------------------- | ----------------- |
| `Framing.Fixed32` | four-byte big-endian length, then the message  | any format        |
| `Framing.Varint`  | protobuf LEB128 length, then the message       | any format        |
| `Framing.Newline` | the message, then `\n`                        | text formats only |

`Fixed32` is the default and the one to pick when nothing else decides for you: the header is a fixed cost,
it needs no scanning, and it is read with one bounds check.

`Varint` costs one byte for a message under 128 bytes instead of four, and it is
**wire-compatible with protobuf's own delimited encoding** — the bytes are exactly what ScalaPB's
`writeDelimitedTo` writes and exactly what `parseDelimitedFrom` reads. That is checked in both directions
below, and again in this module's tests, against ScalaPB itself rather than against b8's own encoder.

`Newline` is JSON Lines, and it is **for text formats only**, which the type enforces rather than the
documentation. `Framing.Newline` is a `Framing[Format.Text]`, and `Framing` is contravariant in its format,
so it is accepted for `Format.Json` and rejected for `Format.Proto` — where a `0x0A` byte inside a message
is an ordinary byte and a delimiter at the same time:

```scala
// messages: Stream[F, PFlat]
messages.through(b8.stream.encode[Format.Proto](Framing.Newline))
// Found:    (b8.stream.Framing.Newline : b8.stream.Framing[b8.Format.Text])
// Required: b8.stream.Framing[b8.Format.Proto]
```

That is a compile error rather than a corrupt stream discovered by whoever reads it back.

What the type cannot check is the *configuration* underneath it. `Format.Text` says the format is textual;
it says nothing about whether a particular writer keeps a message on one line. A pretty-printing bridge —
`b8.jsoniter.codec(writer = WriterConfig.withIndentionStep(2))`, or borer's equivalent — emits real `0x0A`
bytes inside the message, and `Newline` will then cut it into pieces. It fails loudly rather than silently,
because the fragments are not valid JSON, but it fails at the reader rather than at the writer. Compact
output is the default in every bridge b8 ships, so this only bites if you turned indentation on; if you
did, frame with `Fixed32` or `Varint`, which do not care what is in the bytes.

## Limits and failures

`maxFrame` bounds what the decode side will accept, at 16 MiB unless you say otherwise, and it exists
because a length prefix is attacker-controlled input: without a ceiling, four bytes on the wire can ask for
two gigabytes of buffer. The check is on the **declared** length, the moment the header is readable and
before a single byte of the body has been waited for, so an absurd header costs its own four bytes to
reject and nothing more. For `Newline`, where nothing is declared, the check is on the line as it
accumulates.

It is worth knowing exactly what the number counts, because it is not the same thing in both cases. For
`Fixed32` and `Varint` it bounds the **body**, so the four- or one-byte header sits outside the budget and
a 16 MiB limit admits a slightly larger message on the wire. For `Newline` it bounds the **line**, so the
terminating `\n` is outside it but a `\r` before that terminator is inside — a record written with CRLF
is measured one byte longer than the same record written with LF, and at exactly the limit the two can
disagree. That is deliberate rather than an oversight: the check has to fire while the line is still
arriving, before there is any way to know whether the last byte read is a `\r` that a terminator will
later make redundant, and a limit that changed its mind depending on where the chunk boundaries fell would
be far worse than one that is a byte strict.

```scala mdoc:silent
import b8.DecodeError

def outcome(s: Stream[Fallible, User]): String =
  s.compile.toList match
    case Left(e: DecodeError) => s"${e.format}: ${e.message}"
    case Left(e)              => e.toString
    case Right(as)            => as.mkString(", ")

val frames: Stream[Fallible, Byte] =
  Stream.emits(users).through(b8.stream.encode[Format.Json](Framing.Fixed32))
```

Everything the pipe rejects, it rejects into the stream, and it fails fast: the first malformed frame,
truncated tail, malformed varint or oversized frame ends the stream there.

```scala mdoc
outcome(frames.through(b8.stream.decode[Format.Json](Framing.Fixed32, maxFrame = 8)))

outcome(frames.dropRight(3).through(b8.stream.decode[Format.Json](Framing.Fixed32)))

outcome(
  Stream
    .emits(Array[Byte](0, 0, 0, 2, '{', '{'))
    .covary[Fallible]
    .through(b8.stream.decode[Format.Json](Framing.Fixed32))
)
```

Note the tags. A failure this module raises itself is a `DecodeError` whose format is `"framing"`, because
that is what it is about — the delimiting, not the message. A failure the value decoder reported keeps its
own bridge's tag, here jsoniter's `"Json"`, and is re-raised untouched. The distinction is the one you want
at three in the morning: `"framing"` says the two ends disagree about where messages begin, which is a
protocol or a truncation problem, while `"Json"` says the framing worked and one message is not what this
reader expected — a schema problem, on one message, with the rest of the stream probably fine.

The encode side is deliberately unchecked and takes no `maxFrame`. Encoders in b8 are total: an encoder
writes a complete encoding for any value of its type and has nothing to refuse. Keeping the two limits in
step across a producer and a consumer is therefore the operator's job, not the library's.

## Empty frames, blank lines and the last line

A **zero-length frame is valid** for `Fixed32` and for `Varint`, and it has to be: an empty protobuf
message is not an absent message, it is the default instance, and it encodes to zero bytes. So a frame with
a length of zero decodes from an empty source, and the value comes back:

```scala mdoc:silent
import b8.scalapb.given
import b8.scalapb.protos.PNested
```

```scala mdoc
val emptyFrame: Stream[Fallible, Byte] =
  Stream.emit(PNested()).through(b8.stream.encode[Format.Proto](Framing.Varint))

emptyFrame.compile.toList

val emptyBack: Stream[Fallible, PNested] =
  emptyFrame.through(b8.stream.decode[Format.Proto](Framing.Varint))

emptyBack.compile.toList.map(_ == List(PNested()))
```

(`PNested` comes from a `.proto` that b8's build compiles for its own tests, the same one the
[ScalaPB page](scalapb.md) uses. In your project it would be a message of your own.)

The ascription on `emptyBack` is the inference bite from the previous section, in the form you will meet it
most often. With jsoniter, circe or borer the element type can be recovered from the codec in scope, which
is why the JSON round trip above needed no annotation; with ScalaPB the instance comes from the message's
own companion object, and that cannot be found until the type is already known. On a `Format.Proto` pipe,
say what comes out.

For `Newline` there is no such thing as an empty record, and blank lines are skipped rather than handed to
the decoder. A trailing `\r` is stripped as well, so a file written with CRLF terminators — or with a blank
line between records — reads back as the records alone:

```scala mdoc:silent
import java.nio.charset.StandardCharsets.UTF_8

val jsonl = "{\"id\":1,\"name\":\"Ada\"}\r\n\r\n{\"id\":2,\"name\":\"Grace\"}\r\n"
```

```scala mdoc
Stream
  .emits(jsonl.getBytes(UTF_8))
  .covary[Fallible]
  .through(b8.stream.decode[Format.Json](Framing.Newline))
  .compile
  .toList
```

And now the rule that surprises people, because most line-based tooling is lenient about it: JSON Lines
**terminates** its records rather than separating them, so a final line with no `\n` behind it is a
truncated frame, not the last message. Cut the two bytes off the end of that same input and the stream
fails:

```scala mdoc
outcome(
  Stream
    .emits(jsonl.dropRight(2).getBytes(UTF_8))
    .covary[Fallible]
    .through(b8.stream.decode[Format.Json](Framing.Newline))
)
```

That is the only honest answer a streaming reader can give. "The input ended" and "the writer is still
writing" look identical from inside the stream, and a reader that guessed would hand you half a message on
the day a connection dropped mid-write.

## One chunk per message

The encode side emits **exactly one chunk per message**, prefix included, whatever the shape of the stream
it was given. That is a guarantee rather than an accident of the implementation: an operator upstream that
batched two values into one chunk does not merge their frames, and one that split a chunk does not split a
frame.

```scala mdoc
Stream
  .emits(users)
  .through(b8.stream.encode[Format.Json](Framing.Fixed32))
  .chunks
  .compile
  .toList
  .map(_.size)
```

A length-prefixed chunk is built by reserving room for the header on a fresh `ArraySink`, encoding over the
top of it and patching the length in afterwards — one pass over the bytes rather than encoding into one
buffer and copying it in behind a header in another. The chunk is a **view** of that sink's buffer, which
is what makes the trick free, and it has one consequence worth knowing: the array behind the chunk can be
larger than the chunk, because a sink that had to grow doubled. For a stream that consumes its chunks and
drops them that is exactly the trade you want. If you are going to hold on to one — put it in a queue, key
a cache with it, keep it past the stream — `compact[Byte]` it first and let the oversized buffer go.

## Wire compatibility with protobuf

`Framing.Varint` is not merely *a* varint length prefix, it is protobuf's. The claim is easy to check in
both directions, so here it is checked. Writing, against ScalaPB's own `writeDelimitedTo`:

```scala mdoc:silent
import b8.scalapb.protos.PFlat

import java.io.ByteArrayOutputStream

val message = PFlat(id = 1L, name = "Ada")

val fromScalapb =
  val out = new ByteArrayOutputStream
  message.writeDelimitedTo(out)
  Chunk.array(out.toByteArray)
```

```scala mdoc
val fromB8 = Stream
  .emit(message)
  .through(b8.stream.encode[Format.Proto](Framing.Varint))
  .covary[Fallible]
  .compile
  .to(Chunk)

fromB8 == Right(fromScalapb)
```

The chunk that comes back is the reserve-and-patch from the section above, made visible: a five-byte slot
was reserved for the widest varint a length can need, this length needed one byte, and the chunk starts at
offset 4 over a buffer whose first four bytes are nobody's business. What goes on the wire is the eight
bytes of the view.

And reading, with ScalaPB's bytes going into b8's pipe:

```scala mdoc
val readBack: Stream[Fallible, PFlat] =
  Stream
    .chunk(fromScalapb)
    .covary[Fallible]
    .through(b8.stream.decode[Format.Proto](Framing.Varint))

readBack.compile.toList
```

So a b8 pipe can read a topic, a file or a socket that a protobuf implementation in any language wrote,
and write one it can read. `Fixed32` makes no such claim — it is b8's own framing, and both ends have to
be b8 or agree with it by hand.

## Files, sockets and everything else

Nothing on this page needs `fs2-io`, and `b8-fs2` does not depend on it. That is on purpose: the pipes are
`Pipe[F, Byte, A]` and `Pipe[F, A, Byte]`, so anything that produces or consumes a `Stream[F, Byte]` is
already compatible, and the module has no opinion about where the bytes come from.

Add `fs2-io` yourself and it composes the way you would expect: `Files[F].readAll(path)` into
`decode[Format.Json](Framing.Newline)` reads a JSON Lines file record by record, without ever holding the
file in memory; `encode(...)` into `Files[F].writeAll(path)` writes one; and `Network[F]`'s sockets give
you the same two pipes over a connection, where the framing stops being a formality — a socket read
boundary has nothing to do with a message boundary, which is the whole reason the decode side buffers
across chunks instead of decoding each one.
