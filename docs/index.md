# b8

**A universal serialization façade for Scala 3 — any value, to bytes, in any format, through the backend
you already trust.**

*Pick the format with a type. Pick the backend with an import. Pick the container with a package.*

`b8` is the thin layer between your domain types and the bytes on the wire. It does not encode anything
itself — jsoniter-scala, circe, borer and ScalaPB already do that better than a new library could. What
`b8` gives you is one API in front of all of them, so that swapping JSON for CBOR, or circe for
jsoniter-scala, is a one-line change instead of a rewrite. The façade costs nothing per field and at most
one virtual call per message: the backend writes straight into the buffer `b8` hands it.

## The three axes

Serialization decisions that usually come bundled together are three separate choices in b8, and each is
made independently of the other two.

| Axis                 | Chosen by           | Looks like                             |
| -------------------- | ------------------- | -------------------------------------- |
| **Format**           | a phantom type      | `Encoder[User, Format.Json]`           |
| **Backend**          | an import           | `import b8.jsoniter.given`             |
| **Target container** | a package           | `import b8.array.*`                    |

Because the format is a type parameter and not a runtime value, asking for JSON where a CBOR encoder is in
scope is a compile error, not a surprise at runtime. Because the backend is an import, replacing it is a
one-line diff. And because the container is a package, `Array[Byte]`, `fs2.Chunk[Byte]` and
`scodec.bits.ByteVector` all read the same way at the call site.

## Getting started

`b8-core`, the [jsoniter-scala bridge](jsoniter.md), the [circe bridge](circe.md), the
[borer bridge](borer.md) and the [scodec-bits container](scodec.md) exist today; the other backends are in
progress. The core module has no dependencies at all, so it never pulls a JSON parser into a module that
does not want one.

```scala
libraryDependencies += "de.thatscalaguy" %% "b8-core" % "0.1.0"
```

A codec is a pair of methods: write into a `ByteSink`, read from a `ByteSource`. Here is one written by
hand — normally a bridge module derives this for you from the backend's own codec.

```scala mdoc:silent
import b8.*
import java.nio.charset.StandardCharsets.UTF_8

trait Utf8 extends Format.Text

given Codec[String, Utf8] with
  def encodeTo(a: String, out: ByteSink): Unit = out.write(a.getBytes(UTF_8))
  override def sizeHint(a: String): Int = a.length * 3
  def decodeUnsafe(in: ByteSource): String = new String(in.array, in.offset, in.length, UTF_8)
```

With a codec in scope, `b8.array` adds the two extension methods you actually call:

```scala mdoc
import b8.array.*

val bytes = "hello".encode[Utf8]
val back = bytes.decodeAs[String, Utf8]
```

## Sinks and sources

`encode` hands you a fresh, exact-size `Array[Byte]` — or, with [`b8.vector`](scodec.md) imported instead,
an exact-size `scodec.bits.ByteVector` over that same array. When you already own the destination, encode
into it directly and skip the copy — `encodeTo` writes into any `ByteSink`:

- `ArraySink` — growable heap buffer, with a fast path (`ensure` / `buffer` / `advance`) that lets a
  backend serialize straight into the array
- `ByteBufferSink` — writes into a buffer you sized yourself; overflow is the buffer's own
  `BufferOverflowException`
- `OutputStreamSink` — pass-through to a stream

```scala mdoc
import java.nio.ByteBuffer

val bb = ByteBuffer.allocate(64)
"hello".encodeTo[Utf8](ByteBufferSink(bb))
bb.position()
```

On the way in, a `ByteSource` is a read-only view of `(array, offset, length)`. It never copies, so
decoding the middle of a frame costs nothing extra:

```scala mdoc
val framed = Array[Byte](0, 0) ++ bytes ++ Array[Byte](0)
Decoder[String, Utf8].decode(ByteSource(framed, 2, bytes.length))
```

## Errors

Decoding fails with exactly one error type, `DecodeError`. It carries no stack trace, because a rejected
message is data rather than a crash, and filling in a trace would cost more than the decode itself.

`decode` returns `Either[DecodeError, A]` — one allocation per message, never per field. Inside the hot
path, failures stay exceptions; `decodeUnsafe` exposes that directly for callers who frame their own
error handling.

Encoders, by contrast, are total: `encodeTo` writes a complete encoding for any value of the type and
raises no b8-specific exception.

## Buffer reuse

b8 never pools behind your back. The default `given SinkPool` allocates a fresh sink per `encode`. Reuse is
opt-in and scoped:

```scala mdoc:silent
given SinkPool = SinkPool.threadLocal()
```

Each thread then reuses one `ArraySink`, and a sink that grew past the retain limit is dropped on release
instead of being pinned to the thread for the rest of its life.

## Modules

| Module          | Package                | Provides                                                          |
| --------------- | ---------------------- | ----------------------------------------------------------------- |
| `b8-core`       | `b8`, `b8.array`       | the type classes, sinks, sources, and `Array[Byte]` as a container |
| `b8-fs2`        | `b8.chunk`, `b8.stream` | `fs2.Chunk[Byte]` as a container, plus encode/decode pipes and framing |
| `b8-scodec`     | `b8.vector`            | `scodec.bits.ByteVector` as a container                            |
| `b8-jsoniter`   | `b8.jsoniter`          | jsoniter-scala behind `Format.Json` — the recommended JSON backend |
| `b8-circe`      | `b8.circe`             | circe behind `Format.Json`                                         |
| `b8-borer`      | `b8.borer`             | borer behind `Format.Cbor` and `Format.Json`                       |
| `b8-scalapb`    | `b8.scalapb`           | ScalaPB behind `Format.Proto`                                      |
| `b8-laws`       | `b8.laws`              | the shared suite every backend must pass                           |

`b8-core`, `b8-laws`, `b8-jsoniter`, `b8-circe`, `b8-borer` and `b8-scodec` are implemented today; the
[jsoniter-scala bridge](jsoniter.md), the [circe bridge](circe.md), the [borer bridge](borer.md) and the
[scodec-bits container](scodec.md) have a page each. `b8-fs2` and `b8-scalapb` are stubs for now.

## Defining your own format

`Format` and its tags are plain traits, deliberately not sealed. A private wire format needs no change to
b8:

```scala mdoc:silent
trait Avro extends Format
```

Any `Encoder[A, Avro]` you write joins the same API as the built-in tags, including the `b8.array` and
`b8.vector` extension methods.
