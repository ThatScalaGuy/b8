# borer

`b8-borer` puts [borer](https://sirthias.github.io/borer/) behind `Format.Cbor` **and** `Format.Json`. It is
the only bridge that covers two formats, and that follows from borer's own design rather than from anything
b8 does: an `io.bullet.borer.Encoder` describes the *shape* of a value — a map with these keys, an array of
that many elements — and not its spelling. CBOR and JSON are two spellings of the same shapes, so **one
borer codec yields two b8 codecs**, and the type parameter at the call site decides which.

The other thing borer does differently is that it writes through an `Output` and reads through an `Input`,
with no AST in between. The bridge hands borer an `Output` that *is* the sink b8 gave it, and an `Input`
that is the caller's own array or a window into it. Nothing is copied in either direction, which is why
borer is b8's default binary backend.

## Installation

> **Not yet released.** The coordinates below are what the first release will publish to Maven Central for
> Scala 3.

```scala
libraryDependencies += "de.thatscalaguy" %% "b8-borer" % "0.1.0"
```

The module depends on `borer-core` and on nothing else. `borer-derivation` is a test dependency here, never
a published one: how you come by your borer instances — derived, hand-written, or a mix — stays your
decision.

## Getting started

Three things have to be in scope: `b8.array.*` for the extension methods, `b8.borer.given` for the bridge,
and a borer instance for the type.

```scala mdoc:silent
import b8.Format
import b8.array.*
import b8.borer.given

import java.nio.charset.StandardCharsets.UTF_8

import io.bullet.borer.derivation.MapBasedCodecs.deriveCodec

case class User(id: Long, name: String)

given io.bullet.borer.Codec[User] = deriveCodec
```

From there the format is the only thing that changes between the two calls:

```scala mdoc:silent
val user = User(1L, "Ada")

val cbor = user.encode[Format.Cbor]
```

```scala mdoc
cbor.map(b => f"$b%02x").mkString(" ")

val json = new String(user.encode[Format.Json], UTF_8)

cbor.decodeAs[User, Format.Cbor]

json.getBytes(UTF_8).decodeAs[User, Format.Json]
```

The CBOR is a map of two entries — `a2` — then the key `id` and the value `1`, then `name` and `Ada`. The
same field names as the JSON carries, in the same order, and 14 bytes against 21: what CBOR saves on a
record this small is the punctuation, not the keys.

One `given io.bullet.borer.Codec[User]` produced all four instances above. Nothing in it mentions CBOR or
JSON, and nothing had to be derived twice — `Format.Cbor` and `Format.Json` picked the renderer and the
parser, the shape stayed the same. `encodeTo` reads like `encode` and takes any `ByteSink` you already own,
which skips the exact-size array `encode` hands back.

## Which import

There are three ways in, and they are alternatives rather than layers:

- `import b8.borer.given` — both formats, six givens
- `import b8.borer.cbor.given` — CBOR only, three givens
- `import b8.borer.json.given` — JSON only, three givens

Take one of them, and not two. Combining the aggregate with a sub-package, or with another backend's
bridge, compiles cleanly — and that is exactly what makes it a bad idea. Every one of these givens is
anonymous, so they all carry the same name the compiler made up for them, and importing that name a second
time shadows the first rather than competing with it. So this:

```scala
import b8.borer.given
import b8.circe.given
```

leaves **circe** answering for `Format.Json`, and the same two lines in the other order leave **borer**
answering. A different wire format, decided by the order of two import lines — which is the kind of thing
an editor reorders on save. There is one thread to pull if it happens to you: under `-Wunused:all` the
compiler reports the *shadowed* import as unused, so `unused import: b8.borer.given` on a line you are
certain you need is the symptom. Without that flag there is no diagnostic at all.

That is what the sub-packages are for. Two formats from two backends is a reasonable thing to want, and

```scala
import b8.borer.cbor.given
import b8.circe.given
```

asks for it in a line that means the same thing wherever it sits in the file: CBOR is borer's, JSON is
circe's, and nothing else was implied.

## Configuration

borer takes its settings as four case classes — one per format and direction — and the bridge takes them
through six factories:

```scala
b8.borer.cbor.encoder[A](encodingConfig)
b8.borer.cbor.decoder[A](decodingConfig)
b8.borer.cbor.codec[A](encodingConfig, decodingConfig)

b8.borer.json.encoder[A](encodingConfig)
b8.borer.json.decoder[A](decodingConfig)
b8.borer.json.codec[A](encodingConfig, decodingConfig)
```

The fields that actually decide something, with borer's defaults:

| Config                | Fields that matter                                                                                                            |
| --------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| `Cbor.EncodingConfig` | `bufferSize = 1024`, `allowBufferCaching = true`, `compressFloatingPointValues = true`, `maxArrayLength`, `maxMapLength`, `maxNestingLevels = 1000` |
| `Cbor.DecodingConfig` | `readIntegersAlsoAsFloatingPoint = true`, `readDoubleAlsoAsFloat = false`, the same three limits, `maxNestingLevels = 1000`      |
| `Json.EncodingConfig` | `bufferSize = 1024`, `allowBufferCaching = true`, `indent = 0`                                                                  |
| `Json.DecodingConfig` | `readIntegersAlsoAsFloatingPoint = true`, `readDecimalNumbersOnlyAsNumberStrings = false`, `maxNumberAbsExponent = 64`, `maxNumberMantissaDigits = 34`, `maxStringLength` |

Two of those need a word, because they do not mean here quite what borer's own documentation says.

**`bufferSize` is b8's `sizeHint`.** borer documents it as the size of the buffer it would allocate for the
`Output` it writes into. The bridge never lets borer allocate that buffer — the `Output` is the sink — so
the number would otherwise be dead. Reading it as the initial capacity of the sink keeps it meaningful and
keeps its meaning close to the original: it is still "how much room do you expect this to need". For the
same reason `allowBufferCaching` is inert on the encode side: borer's byte-buffer caches sit behind the
allocation that never happens, so nothing the bridge does can reach them.

Decoding is a different matter, and the honest version is worth stating. `Json.DecodingConfig` also carries
`allowBufferCaching`, defaulting to `true`, and there it is *not* inert — borer's JSON parser takes its
char buffer from a process-wide cache and hands it back when the decode ends. The bridge leaves that alone
rather than switching it off, so a b8 JSON decode behaves exactly like the plain borer call it wraps.
b8's own promise not to pool is about b8's buffers, the ones `SinkPool` hands out; borer's internals stay
borer's, and `b8.borer.json.decoder[A](defaultDecodingConfig.copy(allowBufferCaching = false))` turns this
one off if you would rather it were.

**One default is not borer's.** `b8.borer.json`'s decoding config raises `maxNumberAbsExponent` from 64 to
999. borer's cap is a fine guard against a stranger sending a megabyte of digits, but it is lower than what
borer's own JSON encoder writes: `Double.MaxValue` prints as `1.7976931348623157E308`, and under the
default cap it comes back as a decode failure. A codec has to be able to read its own output. Nothing else
is touched, and borer's own setting is one argument away:
`b8.borer.json.decoder[A](Json.DecodingConfig.default)`.

To change any of them, build the instance yourself and put it in scope as a `given`. A hand-built instance
outranks the bridge's: it is a value of exactly the type being summoned, while the bridge's given is
parameterised and takes arguments of its own, which makes it the less specific of the two.

```scala mdoc:silent
import b8.Codec
import b8.Decoder

import io.bullet.borer.Cbor
import io.bullet.borer.Json

object indented:
  given Codec[User, Format.Json] =
    b8.borer.json.codec(Json.EncodingConfig.default.copy(indent = 2))
  def render(u: User): String = new String(u.encode[Format.Json], UTF_8)

object shallow:
  given Decoder[List[User], Format.Cbor] =
    b8.borer.cbor.decoder(
      Cbor.DecodingConfig.default.copy(maxNestingLevels = 0)
    )
  def read(bytes: Array[Byte]) = bytes.decodeAs[List[User], Format.Cbor]

object borerCap:
  given Decoder[Double, Format.Json] =
    b8.borer.json.decoder(Json.DecodingConfig.default)
  def read(s: String) = s.getBytes(UTF_8).decodeAs[Double, Format.Json]
```

`maxNestingLevels = 0` allows one array or map and nothing inside it, so a list of records is one level too
deep for it. It is the limit to lower when the input comes from a stranger and the shape you expect is
known to be shallow.

Each `given` reaches only as far as its enclosing scope; everything outside still sees the bridge's
defaults, which is what the last two lines show.

```scala mdoc
indented.render(user)

shallow.read(List(user).encode[Format.Cbor])

Double.MaxValue.toString.getBytes(UTF_8).decodeAs[Double, Format.Json]

borerCap.read(Double.MaxValue.toString)
```

## What resolves for a type

Each of the two sub-packages holds three givens, and `scala.util.NotGiven` keeps them from ever competing:
the two one-way givens each ask for the *absence* of the opposite borer instance, so for any type at most
one of the three applies. `import b8.borer.given` gives you all six at once.

| borer instances for `A`     | per format, the bridge provides         |
| --------------------------- | --------------------------------------- |
| `Encoder` **and** `Decoder` | `Codec[A, F]`                           |
| `Encoder` only              | `Encoder[A, F]`                         |
| `Decoder` only              | `Decoder[A, F]`                         |
| neither                     | nothing — the summon is a compile error |

A type borer knows in both directions therefore summons as an `Encoder[A, Format.Cbor]` and as a
`Decoder[A, Format.Cbor]` as well, because `Codec` is both — and it is the same `CborCodec` in each case,
built with the same pair of configs, so the two directions cannot end up configured apart by accident. The
JSON side reads identically with `JsonCodec`.

A type borer knows nothing about resolves as nothing, and that is a compile error where you write the
summon rather than a surprise at the point the message is sent:

```scala mdoc:silent
case class Unsupported(n: Int)
```

```scala mdoc:fail
summon[b8.Encoder[Unsupported, Format.Cbor]]
```

Read that message from both ends. The first line is the one you asked for; the last one names what was
actually missing underneath — here the `io.bullet.borer.Encoder`. borer derives nothing implicitly, so this
is the message you get for every type whose codec you forgot to derive, and it points at the right file.

## Errors

Malformed input becomes b8's single error type, `DecodeError`, with `format` set to `"Cbor"` or `"Json"`
and borer's own `Borer.Error` kept as the cause. The message is borer's, quoted unchanged: it already ends
in `(input position N)`, so b8 appends no position of its own and never has two of them to keep in sync.

Only `Borer.Error` is caught, and only on the decode side — but be careful about what that leaves out,
because it is less than it sounds. borer's decoding DSL catches every non-fatal exception itself and
re-throws it as a `Borer.Error.General` before b8 sees it, so a bug in a hand-written borer decoder does
come back as a `DecodeError`, with the exception it really was two links down the cause chain. b8 does not
unpick that: telling a genuine bug apart from a decoder that raises on input it dislikes is a judgement b8
has no way to make. What still comes through untouched is what `NonFatal` does not cover — a
`StackOverflowError` from a runaway recursive decoder stays a `StackOverflowError`.

Encoding, on the other hand, stays total in b8's sense: a failure the backend cannot avoid — a
`ByteBufferSink` that runs out of room — comes out as its own `java.nio.BufferOverflowException`. That is
the reason the bridge encodes through `Cbor.writer(…)` instead of borer's `Cbor.encode(x).to(…)` DSL; the
DSL catches every `NonFatal` on that side too and would rewrite the overflow into a borer error.

```scala mdoc:silent
def whyJson(s: String): String =
  s.getBytes(UTF_8).decodeAs[User, Format.Json].fold(_.message, _.toString)

def whyCbor(bytes: Array[Byte]): String =
  bytes.decodeAs[User, Format.Cbor].fold(_.message, _.toString)
```

```scala mdoc
whyJson("""{"id":1,"name":""")

whyJson("""{"id":1,"name":7}""")

whyJson("""{"id":1}""")

whyCbor(cbor.take(3))

whyCbor(1.encode[Format.Cbor])
```

### Trailing input

CBOR is strict: borer reads one value, and any byte after it is an error. Nothing in the format looks like
padding, so there is nothing to be lenient about.

borer's JSON reads one value and then insists on end of input too, so a `}` too many or a stray `x` is
rejected while trailing whitespace is not. Two byte values fall on the accepting side that a reader would
not guess. Every byte up to `0x20` counts as whitespace to borer's JSON parser, so a trailing NUL passes.
And `0xFF` is the parser's own end-of-input marker, so it passes as well.

```scala mdoc
whyCbor(cbor :+ 0x00.toByte)

whyCbor(cbor :+ 0xff.toByte)

whyJson(json + "}")

whyJson(json + "   ")

(json.getBytes(UTF_8) :+ 0x00.toByte).decodeAs[User, Format.Json]

(json.getBytes(UTF_8) :+ 0xff.toByte).decodeAs[User, Format.Json]
```

b8 does not add a second scan over the input to catch two byte values, because that scan would cost every
well-formed message something to protect against a case a well-formed message never hits. If a trailing
byte has to be rejected, CBOR is the format that already does it.

## What the bytes look like

The bridge does not touch the wire format. What comes out of `encode[Format.Cbor]` is byte for byte what
`Cbor.encode(x).toByteArray` produces, and `encode[Format.Json]` matches `Json.encode(x).toByteArray` —
that equality is a test in `SinkSuite`, not a claim. So borer's own rules are the whole answer, and these
are the ones worth knowing before the first message goes out:

- **`Option`** is an array of zero or one element: `None` is `[]`, `Some(x)` is `[x]`. `null` for `None` is
  available through `import io.bullet.borer.NullOptions.given`, which is a choice about the wire and
  therefore borer's to offer, not b8's.
- **`Map`** becomes a CBOR map or a JSON object. CBOR takes any data item as a key; JSON does not, so a
  `Map[Int, A]` encodes fine as CBOR and fails as JSON with borer's "JSON does not support integer values
  as a map key". This is the one place where "one codec, two formats" does not hold, and it shows up when
  the value is encoded rather than when the codec is summoned.
- **Case classes and enums**, with `MapBasedCodecs`: a case class is a map of its field names. A Scala 3
  enum whose cases carry no data derives to a plain string. One whose cases carry data derives to a
  single-entry map keyed by the case name, and needs `deriveAllCodecs` rather than `deriveCodec`, because
  the cases need codecs of their own.
- **Floating point values in CBOR** are compressed, losslessly: with `compressFloatingPointValues = true`,
  a `Double` that fits into a float or even a float16 without losing a bit is written in the shorter form
  and read back bit-exact. Turning it off makes the encoding larger and changes no value.

## When to use it

CBOR is b8's default binary format, and this bridge is the reason. It is compact, self-describing and needs
no schema, and borer reaches it without an AST on either side. Reach for it when the bytes leave the
process and nobody needs to read them by eye.

borer's JSON is AST-free and fast, and it is the right choice when a project wants one backend for both
formats — one derived codec, two wire formats, one set of settings to understand. For JSON alone,
jsoniter-scala is usually faster still and has a bridge of its own, and circe is the compatibility path
when the instances you need already exist there. Numbers belong to the machine they were taken on, so none
are quoted here; `benchmarks/BorerBench` measures the façade against a direct borer call, pair by pair.

One habit is worth picking up in a hot loop. The givens take type parameters, so each of them is a method,
and every place the compiler summons one builds a fresh codec — inside `xs.map(_.encode[Format.Cbor])` that
means one per element. Bind the instance once where the loop can see it and the allocation disappears:

```scala mdoc:silent
object hot:
  given Codec[User, Format.Cbor] = b8.borer.cbor.codec()

  def encodeAll(users: List[User]): List[Array[Byte]] =
    users.map(_.encode[Format.Cbor])
```
