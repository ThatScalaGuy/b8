# scodec-bits

`b8-scodec` is not a bridge. It adds no backend and no format — it fills in the third axis, the target
container, for [scodec-bits](https://github.com/scodec/scodec-bits): `b8.vector` does for
`scodec.bits.ByteVector` exactly what `b8.array` does for `Array[Byte]`, and it does it with whatever
bridge you already have in scope. jsoniter-scala, circe, borer, your own hand-written codec — none of them
know this module exists, and none of them need to.

`ByteVector` is worth having as a target because of what it is: an immutable, persistent byte sequence
whose `++` is O(log n) rather than a copy, and whose slices are views. That is the shape a wire protocol
wants — frame headers concatenated onto payloads, a length-prefixed message sliced back out of a buffer —
and it is a shape `Array[Byte]` does badly.

## Installation

> **Not yet released.** The coordinates below are what the first release will publish to Maven Central for
> Scala 3.

```scala
libraryDependencies += "de.thatscalaguy" %% "b8-scodec" % "0.1.0"
```

The module depends on `scodec-bits` and on nothing else — not even on a JSON parser, since it has no idea
which format you are going to ask for. Add a bridge alongside it; the two are independent choices.

## Getting started

Two imports and a codec, the same as always, except that the container import is `b8.vector.*` instead of
`b8.array.*`:

```scala mdoc:silent
import b8.Format
import b8.vector.*
import b8.jsoniter.given

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

import scodec.bits.ByteVector

case class User(id: Long, name: String)

given JsonValueCodec[User] = JsonCodecMaker.make
```

From there the four methods read exactly like their `b8.array` counterparts:

```scala mdoc
val user = User(1L, "Ada")

val bytes: ByteVector = user.encode[Format.Json]

bytes.decodeAs[User, Format.Json]
```

`encodeTo` is unchanged and shared: it takes a `ByteSink`, which has nothing to do with the container, so
the version in `b8.vector` is the version in `b8.array`. `decodeAsUnsafe` is the throwing sibling of
`decodeAs`, for callers who frame their own error handling.

## What it costs

`encode` returns an **exact-size** vector, and it costs one copy — the same single copy an
`Array[Byte]` costs, and no more. `Encoder.encode` renders into the sink's buffer and trims the result into
a fresh array that nothing else references; `b8.vector` puts a `ByteVector.view` around that array rather
than copying it again. Nothing is shared with the pool afterwards, so a pooled sink is as safe here as it
is with an array.

```scala mdoc
bytes.size == user.encode[Format.Json].size
```

`decodeAs` goes the other way through `asByteSource`, and what that costs depends on the shape of the
vector rather than on its size:

- a **single chunk over an array** — anything built by `ByteVector.view(array)` or `ByteVector(array)`,
  including a slice of one produced by `drop`, `take` or `slice` — is read **in place**, at the right
  offset, with no copy at all;
- a **single chunk over a heap `ByteBuffer`** — `ByteVector.view(buffer)` — is read in place too: b8 takes
  the buffer's array and honours both its `arrayOffset` and its position;
- a **single chunk over a direct `ByteBuffer`** is copied on every call, because a direct buffer exposes no
  array to read from;
- a **concatenation** (`a ++ b`) or any other multi-chunk vector is copied once per call, into one
  contiguous array, because a decoder needs its input in one piece.

The last two are the ones to know about, and they are fixed differently. A concatenation is flattened by
`compact`; a direct buffer is not, since it is already a single chunk and `compact` returns those
unchanged. `copy` is what materializes an array in either case.

Here is a message that arrived in two reads and was stitched back together — the shape that actually costs
you, as opposed to a slice, which does not:

```scala mdoc:silent
val (head, tail) = (bytes.take(10), bytes.drop(10))
val reassembled = head ++ tail
val payload = reassembled.compact
```

```scala mdoc
reassembled.decodeAs[User, Format.Json]
payload.decodeAs[User, Format.Json]
```

Both decode; the difference is that the first copies the message every time it is decoded and the second
copied it once. Slicing, by contrast, is never what costs you: `drop`, `take` and `slice` of a single
chunk stay views on that chunk's array, at the right offset, and scodec-bits will even hand a slice of a
concatenation back as the untouched chunk it came from when the cut happens to land on a boundary.

## Wiring your own decoding

`asByteSource` is the same conversion `decodeAs` uses, exposed for callers who do not want the extension
methods — because they are dispatching on a frame header, or reading one value out of a longer buffer, or
holding a `Decoder` they summoned themselves.

```scala mdoc
import b8.Decoder

Decoder[User, Format.Json].decode(payload.asByteSource)
```

It never copies more than once, and the `ByteSource` it hands back carries the vector's own offset, so
there is no arithmetic to get wrong on your side.

## One container per file

`b8.array` and `b8.vector` declare the same four names. Importing both in one scope makes `encode`
ambiguous and the file stops compiling:

```scala
import b8.array.*
import b8.vector.*

user.encode[Format.Json]
// value encode is not a member of User.
// An extension method was tried, but could not be fully constructed:
//     b8.vector.encode(user)
//   failed with:
//     Reference to encode is ambiguous.
//     It is both imported by import b8.array._
//     and imported subsequently by import b8.vector._
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

object asVector:
  import b8.vector.*
  def apply(u: User): ByteVector = u.encode[Format.Json]
```

## BitVector

There is no `BitVector` support, on purpose. b8 encodes to and decodes from bytes, and a `BitVector` whose
length is not a multiple of eight has no byte representation to hand a decoder. Converting is one call, and
it belongs on your side of the line where you know what a partial byte means:

```scala mdoc
import scodec.bits.BitVector

BitVector(bytes).bytes.decodeAs[User, Format.Json]
```

`bits.bytes` right-pads to a whole byte if it has to, so check `bits.size % 8 == 0` first when a
short read is a real possibility on your wire.
