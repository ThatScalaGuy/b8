# jsoniter-scala

`b8-jsoniter` puts [jsoniter-scala](https://github.com/plokhotnyuk/jsoniter-scala) behind `Format.Json`, and
it is **b8's recommended JSON path**. jsoniter generates the codec for a type at compile time — a plain
class with a `readVal` and a `writeVal` written out field by field — and that class puts UTF-8 bytes into a
byte array directly. No AST is built, no `String` is made, no field name is looked up in a map at runtime.
That is what makes it the fastest JSON library on the JVM for Scala.

Being that fast also leaves the façade nowhere to hide. A copy that costs a circe user a few percent of a
much larger number would show up here as a decent fraction of the total, so any buffer b8 slipped in
between would be visible in a benchmark rather than lost in the noise. Which is why this bridge does not
have one: it encodes through `writeToSubArray` into the sink's own array, and decodes through
`readFromSubArray` over the window the caller handed it.

## Installation

> **Not yet released.** The coordinates below are what the first release will publish to Maven Central for
> Scala 3.

```scala
libraryDependencies += "de.thatscalaguy" %% "b8-jsoniter" % "0.1.0"
```

The module depends on `jsoniter-scala-core` and on nothing else. Your own project needs one more thing that
b8 does not re-export: `jsoniter-scala-macros`, which is where `JsonCodecMaker` lives. The bridge turns a
`JsonValueCodec[A]` into a b8 codec and never asks where that instance came from, so how you come by it —
derived, hand-written, or a mix — stays your decision.

```scala
libraryDependencies ++= Seq(
  "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.40.1" % "compile-internal",
  "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.40.1" % "test-internal"
)
```

The macros run at compile time and leave nothing behind that your code needs at runtime, so the artifact
does not belong in your published POM. `compile-internal` keeps it out of one while still putting it on the
compiler's classpath. The second line is not redundant: sbt's `test-internal` does not extend
`compile-internal`, so without it a `JsonCodecMaker.make` in `src/test` fails to resolve. Drop the line you
do not need. (b8's own build takes the macros at plain `% Test` instead, because only its test sources
derive anything.)

There is one form that does not fit that rule. `derives ConfiguredJsonValueCodec` needs the macros module at
ordinary `compile` scope, because the instance it produces is a wrapper that forwards to the generated codec
on every call rather than being it. Prefer `JsonCodecMaker.make` and a `given`: it is a scope narrower in the
build and one indirection shorter on the hot path.

## Getting started

Three things have to be in scope: `b8.array.*` for the extension methods, `b8.jsoniter.given` for the
bridge, and a `JsonValueCodec` for the type.

```scala mdoc:silent
import b8.Format
import b8.array.*
import b8.jsoniter.given

import java.nio.charset.StandardCharsets.UTF_8

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

case class User(id: Long, name: String)

given JsonValueCodec[User] = JsonCodecMaker.make
```

From there, encoding and decoding are methods on the values themselves:

```scala mdoc
val user = User(1L, "Ada")

val json = new String(user.encode[Format.Json], UTF_8)

json.getBytes(UTF_8).decodeAs[User, Format.Json]
```

`encodeTo` reads the same and takes any `ByteSink` you already own, which skips the exact-size array
`encode` hands back.

## What the bytes look like

The bridge never rewrites what the backend produced, so the wire format is `JsonCodecMaker`'s and its
defaults are the ones to know before the first message goes out. Two of them surprise people.

A Scala 3 enum is an ADT to jsoniter, and an ADT is an object carrying a discriminator field named `type` —
not a bare string. And `transientNone` and `transientEmpty` are both on, so a `None` field and an empty
collection are **omitted** rather than written as `null`, `[]` or `{}`. Decoding treats those fields as
optional, so the round trip still holds; it is the readers on the other side of the wire that need warning.

```scala mdoc:silent
enum Role:
  case Admin, Guest

case class Account(
    user: User,
    role: Role,
    tags: List[String],
    note: Option[String]
)

given JsonValueCodec[Account] = JsonCodecMaker.make
```

```scala mdoc
new String(Account(user, Role.Guest, Nil, None).encode[Format.Json], UTF_8)
```

If you want the bare case name instead, that is one word in the derivation and nothing at all in b8:

```scala mdoc:silent
object bare:
  given JsonValueCodec[Role] = JsonCodecMaker.makeWithoutDiscriminator
  def render(r: Role): String = new String(r.encode[Format.Json], UTF_8)
```

```scala mdoc
bare.render(Role.Guest)
```

## Configuration

One factory, because jsoniter has one instance: a `JsonValueCodec[A]` carries both directions, so there is
no such thing as a type jsoniter can write but not read, and therefore no encoder-only or decoder-only
variant to offer.

```scala
b8.jsoniter.codec[A](
  writer: WriterConfig = WriterConfig,
  reader: ReaderConfig = ReaderConfig,
  reentrant: Boolean = false
)(using JsonValueCodec[A]): Codec[A, Json]
```

**`writer`** is jsoniter's own encoding settings, unchanged. Note the spelling of the default: in
jsoniter-scala the companion object *is* the default instance, so you write `WriterConfig` where every
other library would have you write `WriterConfig.default`, and the builder methods hang off that value.
`withIndentionStep(2)` is the one worth knowing — it turns compact output into pretty-printed output.

```scala mdoc:silent
import b8.Codec

import com.github.plokhotnyuk.jsoniter_scala.core.ReaderConfig
import com.github.plokhotnyuk.jsoniter_scala.core.WriterConfig

object pretty:
  given Codec[User, Format.Json] =
    b8.jsoniter.codec(WriterConfig.withIndentionStep(2))
  def render(u: User): String = new String(u.encode[Format.Json], UTF_8)
```

```scala mdoc
pretty.render(user)
```

**`reader`** is the same story on the way in, and the field that decides something b8 cares about is
`checkForEndOfInput`. It is on by default, and it is the whole reason the bridge rejects bytes left over
after a value, as `Decoder` requires it to. `ReaderConfig.withCheckForEndOfInput(false)` opts out of that
part of the contract — a fine thing to do when you are reading one value out of a longer buffer on purpose,
and a bad surprise when you did it by accident.

The other one to know about is `withAppendHexDumpToParseException(false)`. jsoniter's default is `true`,
which makes every failure message multi-line and appends a hex dump of the input around the offending byte.
At a REPL that is exactly what you want. In a structured log it is a dozen lines of table per rejected
message, and the flag is how you turn it off.

**`reentrant`** decides which pair of entry points the bridge calls. Left at `false` it uses jsoniter's
thread-pooled reader and writer, which is what every direct jsoniter user gets and where a good part of the
speed comes from. The catch is that the pool holds one of each per thread. If the codec for your type calls
b8 — or jsoniter — again *while it is already encoding or decoding*, the nested call takes the very writer
the outer one is in the middle of using. **The failure mode is silent corrupt output, not an exception**:
the outer message comes back malformed and nothing anywhere reports a problem. `reentrant = true` switches
to jsoniter's `*Reentrant` entry points, which allocate a fresh reader or writer per call and nest safely.

```scala
// A hand-written JsonValueCodec that encodes an embedded payload with b8
// while the outer value is still being written is the case that needs this.
given Codec[Envelope, Format.Json] = b8.jsoniter.codec(reentrant = true)
```

A hand-built instance like these outranks the bridge's given: it is a value of exactly the type being
summoned, while the bridge's given is parameterised and takes arguments of its own, which makes it the less
specific of the two. Each one reaches only as far as its enclosing scope; everything outside still sees
jsoniter's defaults.

## Buffers and the size hint

`writeToSubArray` renders into the array it is handed, with no intermediate buffer anywhere — but it cannot
grow that array, so somebody has to guess the size up front. The bridge guesses from history: it remembers
how many bytes the last value of this type encoded to, and asks the sink for that plus a quarter plus 32.

The quarter absorbs the ordinary spread between one record and the next. The fixed 32 is jsoniter's own
doing and is not optional: the writer reserves room for a whole token before it starts writing one, so a
buffer sized to the *exact* length of the encoding overflows on the last field. Measured across the law
fixtures and a scan of deliberately awkward inputs the reservation never exceeded 21 bytes, and it does not
grow with the payload, which is why it is a constant rather than a third proportion.

When the guess is still short the bridge grows the sink and encodes again. That is correct — nothing
half-written survives, because the sink's position only moves after a successful write — but it is a second
full pass over the value, and it is the one thing on this path worth avoiding.

On a hot path, avoid it by reusing the sink:

```scala mdoc:silent
import b8.SinkPool

given SinkPool = SinkPool.threadLocal()
```

A pooled sink keeps the capacity it grew to. After the first message on a thread it is already large enough
for the ones that follow, so the retry stops firing and no buffer is allocated per encode at all.

## Errors

Malformed input becomes b8's single error type. jsoniter raises `JsonReaderException` for everything that is
wrong with the *input* — broken syntax, a missing required field, a value of the wrong type, and, since
`checkForEndOfInput` is on, bytes left over after the value — and the bridge turns it into
`DecodeError(e.getMessage, "Json", e)` with the original kept as the cause. The message already ends in
`, offset: 0x…`, so b8 appends no position of its own and never has two of them to keep in sync. Neither
exception carries a stack trace: jsoniter leaves its own empty by default, and `DecodeError` never fills
one in.

```scala mdoc
"""{"id":1,"name":7}""".getBytes(UTF_8).decodeAs[User, Format.Json]
```

That is the hex dump the previous section mentioned, in the shape it actually arrives in. Reading further
examples is easier with it switched off:

```scala mdoc:silent
object terse:
  given Codec[User, Format.Json] =
    b8.jsoniter.codec(reader =
      ReaderConfig.withAppendHexDumpToParseException(false)
    )
  def why(s: String): String =
    s.getBytes(UTF_8).decodeAs[User, Format.Json].fold(_.message, _.toString)
```

```scala mdoc
terse.why("""{"id":1,"name":""")

terse.why("""{"id":1}""")

terse.why("""{"id":1,"name":"Ada"} x""")

terse.why("""{"id":1,"name":"Ada"}   """)
```

The last two are `checkForEndOfInput` at work. Trailing whitespace is fine and a trailing anything-else is
not, where jsoniter's idea of whitespace is exactly space, tab, CR and LF — so a stray NUL byte after a
well-formed value is rejected rather than skipped.

Now the part that differs from the circe and borer bridges, stated plainly: **only `JsonReaderException` is
wrapped.** An exception thrown from inside a hand-written `JsonValueCodec` — a `MatchError`, an
`IllegalStateException`, an arithmetic slip in a custom number reader — comes out of `decode` as itself
rather than as a `Left`. That is not an oversight and it is not b8 being lazy. jsoniter, unlike borer's
decoding DSL, does not catch its codecs' exceptions and re-throw them as parse errors, so the bridge can
still tell the two apart at this point — and calling every exception malformed input would report a broken
codec as a broken message, which sends whoever is on call to the wrong place.

## One JSON backend per file

`import b8.jsoniter.given` and another backend's JSON bridge in the same file do not clash — and that is the
problem. Every b8 bridge given is anonymous, so they all carry the same name the compiler made up for them,
and importing that name a second time shadows the first rather than competing with it. So this:

```scala
import b8.jsoniter.given
import b8.circe.given
```

leaves **circe** answering for `Format.Json`, and the same two lines in the other order leave **jsoniter**
answering. A different wire format, decided by the order of two import lines — which is the kind of thing an
editor reorders on save. There is one thread to pull if it happens to you: under `-Wunused:all` the compiler
reports the *shadowed* import as unused, so `unused import: b8.jsoniter.given` on a line you are certain you
need is the symptom. Without that flag there is no diagnostic at all.

One JSON backend per file, then. The combination that is unambiguous is a JSON bridge next to a bridge for a
different format, where the format tags decide and nothing is shadowed:

```scala
import b8.borer.cbor.given
import b8.jsoniter.given
```

CBOR is borer's, JSON is jsoniter's, and the line means the same thing wherever it sits in the file.

## When to use it

Pick `b8-jsoniter` for JSON unless you have a reason not to. It is the fastest of the three backends in both
directions, and behind b8 it is also the one that costs the least on top of what the backend itself does,
because nothing is copied on either side. `benchmarks/JsoniterBench` measures the façade against a direct
jsoniter call pair by pair, and `benchmarks/JsonBackendsBench` puts the three backends side by side over the
same fixtures; numbers belong to the machine they were taken on, so none are quoted here.

Two reasons you might still choose otherwise. If circe is already in the project and the instances you need
exist only there — or the code around the wire works on `io.circe.Json` anyway — then [circe](circe.md) is
the compatibility bridge and this is not a fight worth picking. And if you want CBOR and JSON out of one set
of derived instances, [borer](borer.md) is the only bridge that gives you both from one codec.

One habit is worth picking up in a hot loop. The given takes a type parameter, so it is a method, and every
place the compiler summons it builds a fresh `JsoniterCodec` — inside `xs.map(_.encode[Format.Json])` that
means one per element. The allocation is the smaller half of it. A fresh instance has no history to guess
from, so its hint starts at the default 256 and asks the sink for 352 bytes; for anything bigger than that
the first attempt overflows and the value is encoded a second time, for every element. Bind the instance
once where the loop can see it and both costs disappear:

```scala mdoc:silent
object hot:
  given Codec[User, Format.Json] = b8.jsoniter.codec()

  def encodeAll(users: List[User]): List[Array[Byte]] =
    users.map(_.encode[Format.Json])
```
