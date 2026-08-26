# circe

`b8-circe` puts [circe](https://circe.github.io/circe/) behind `Format.Json`. It derives nothing of its
own: every type that already has an `io.circe.Encoder` or an `io.circe.Decoder` gets the matching b8
instance from one import, and the bytes on the wire are exactly the ones circe's `Printer` produces.

## Installation

> **Not yet released.** The coordinates below are what the first release will publish to Maven Central for
> Scala 3.

```scala
libraryDependencies += "de.thatscalaguy" %% "b8-circe" % "0.1.0"
```

The module depends on `circe-core` and `circe-parser`, and on nothing else. How you come by your circe
instances — a `derives` clause, `semiauto`, or hand-written ones — stays your decision, so `circe-generic`
is not dragged in on your behalf.

## Getting started

Three things have to be in scope: `b8.array.*` for the extension methods, `b8.circe.given` for the bridge,
and a circe instance for the type.

```scala mdoc:silent
import b8.Format.Json
import b8.array.*
import b8.circe.given

import java.nio.charset.StandardCharsets.UTF_8

case class User(id: Long, name: String) derives io.circe.Codec.AsObject
```

From there, encoding and decoding are methods on the values themselves:

```scala mdoc
val user = User(1L, "Ada")

val json = new String(user.encode[Json], UTF_8)

val back = json.getBytes(UTF_8).decodeAs[User, Json]
```

`encodeTo` reads the same and takes any `ByteSink` you already own, so the exact-size array `encode` hands
back is never allocated in the first place.

## Configuration

There are two knobs, one per direction: circe's `Printer` decides spacing, key order and number rendering
on the way out, and jawn's `JawnParser` decides the value-size limit and duplicate-key handling on the way
in. Three factories take them:

```scala
b8.circe.encoder[A](printer)
b8.circe.decoder[A](parser)
b8.circe.codec[A](printer, parser)
```

The defaults are `Printer.noSpaces` — the most compact form circe prints, which is what a wire format
wants — and `new JawnParser`, which means no limit on the size of a single value and duplicate object keys
allowed, last one wins. It is spelled `new JawnParser` and not `JawnParser()` because in circe 0.14 the
companion carries only `apply` overloads that take arguments, so the empty call does not compile. Named
arguments do: `JawnParser(allowDuplicateKeys = false)` is the strict parser.

To change either one, build the instance yourself and put it in scope as a `given`. A hand-built instance
outranks the bridge's: it is a value of exactly the type being summoned, while the bridge's given is
parameterised and takes arguments of its own, which makes it the less specific of the two.

```scala mdoc:silent
import b8.Codec
import b8.Decoder
import io.circe.Printer
import io.circe.jawn.JawnParser

object pretty:
  given Codec[User, Json] = b8.circe.codec(Printer.spaces2)
  def render(u: User): String = new String(u.encode[Json], UTF_8)

object strict:
  given Decoder[User, Json] =
    b8.circe.decoder(JawnParser(allowDuplicateKeys = false))
  def read(s: String) = s.getBytes(UTF_8).decodeAs[User, Json].isRight
```

Each `given` reaches only as far as its enclosing scope; everything outside it still sees the bridge's
defaults.

```scala mdoc
pretty.render(user)

strict.read("""{"id":1,"name":"Ada","name":"Grace"}""")

"""{"id":1,"name":"Ada","name":"Grace"}""".getBytes(UTF_8).decodeAs[User, Json]
```

## What resolves for a type

`b8.circe` holds three givens, and `scala.util.NotGiven` keeps them from ever competing: the two one-way
givens each ask for the *absence* of the opposite circe instance, so for any type at most one of the three
applies.

| circe instances for `A`     | `import b8.circe.given` provides        |
| --------------------------- | --------------------------------------- |
| `Encoder` **and** `Decoder` | `Codec[A, Json]`                        |
| `Encoder` only              | `Encoder[A, Json]`                      |
| `Decoder` only              | `Decoder[A, Json]`                      |
| neither                     | nothing — the summon is a compile error |

A type circe knows in both directions therefore summons as an `Encoder[A, Json]` and as a
`Decoder[A, Json]` as well, because `Codec` is both — and it is the same `CirceCodec` definition in each
case, built with the same printer and the same parser, so the two directions cannot end up configured
apart by accident.

A type circe knows nothing about resolves as nothing, and that is a compile error where you write the
summon rather than a surprise at the point the message is sent:

```scala mdoc:silent
case class Unsupported(n: Int)
```

```scala mdoc:fail
summon[b8.Encoder[Unsupported, Json]]
```

Read that message from both ends. The first line is the one you asked for; the last one names what was
actually missing underneath — here the `io.circe.Encoder`. In between, the compiler names the codec given
as the candidate it tried, even though the summon was for an `Encoder`, because that is the branch whose
search got furthest. A print-only type asked for a `Decoder` gives the same shape, with
`io.circe.Decoder` named at the end.

## Errors

The bridge raises b8's single error type, `DecodeError`, and keeps circe's own failure as its cause. Which
of circe's two failures it was decides where the message comes from:

- a `ParsingFailure` — the bytes are not JSON — becomes `DecodeError(pf.message, "Json", pf)`
- a `DecodingFailure` — the JSON is fine but the shape is not — becomes
  `DecodeError(df.getMessage, "Json", df)`. `getMessage` rather than `message`, because only `getMessage`
  renders circe's cursor history, and that is the part saying *where* in the document it went wrong
- anything else propagates unwrapped: `Decoder.decode` catches `DecodeError` and nothing more, so a bug
  in a hand-written circe instance surfaces as itself instead of being reported as malformed input

Trailing input needs no rule of its own. Jawn reads one value and then insists on end of input, accepting
only space, tab, CR and LF after it — so `}`, a stray `x` and even a single NUL byte are all rejected as
malformed, while trailing whitespace is not. The bridge adds no second check on top.

```scala mdoc:silent
def why(s: String): String =
  s.getBytes(UTF_8).decodeAs[User, Json].fold(_.message, _.toString)
```

```scala mdoc
why("""{"id":1,"name":""")

why("""{"id":1,"name":7}""")

why("""{"id":1,"name":"Ada"} x""")

why("""{"id":1,"name":"Ada"}   """)
```

## When to use it

`b8-circe` is the compatibility path. Take it when circe is already in the project, when the instances you
need exist only for circe, or when the code around the wire works on `Json` anyway.

It cannot be the fast path, and the reason is structural rather than fixable. `io.circe.Encoder` produces
a `Json` tree, so a whole AST is built for every message before a single byte is written. `Printer` then
prints that tree into a `ByteBuffer` of its own — it has no way to write into a foreign array — which
leaves one copy from circe's buffer into the sink. What the bridge controls is that copy, and it spends
exactly one: into an `ArraySink` it copies straight into the sink's array through the fast path, into
anything else it hands over the printer's own slice, and it never builds a `String` on the way. The AST
above it stays. What the façade itself costs on top of a bare circe call is what `benchmarks/CirceBench`
measures, pair by pair; numbers belong to the machine they were taken on, so none are quoted here.

One habit is worth picking up in a hot loop. The three givens take type parameters, so each of them is a
method, and every place the compiler summons one builds a fresh `CirceCodec` — inside
`xs.map(_.encode[Json])` that means one per element, along with the `JawnParser` it carries. Bind the
instance once where the loop can see it and the allocation disappears:

```scala mdoc:silent
given userCodec: Codec[User, Json] = b8.circe.codec()

def encodeAll(users: List[User]): List[Array[Byte]] = users.map(_.encode[Json])
```

Two smaller notes in the same spirit. The bridge overrides no `sizeHint`, so the inherited 256 stands:
circe cannot tell how long a value prints without printing it, and a made-up number would be a guess
dressed up as knowledge. And `Printer.reuseWriters` is deliberately not enabled in the default given —
b8 pools nothing you did not ask for — which costs nothing anyway, since `printToByteBuffer` does not go
through a `Writer` and ignores the flag.

For throughput, reach for a backend that writes bytes without an AST in between. jsoniter-scala is that
bridge, and swapping it in is one import once it lands.
