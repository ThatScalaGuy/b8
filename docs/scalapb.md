# ScalaPB

`b8-scalapb` puts [ScalaPB](https://scalapb.github.io/) behind `Format.Proto`. ScalaPB turns a `.proto` into
Scala case classes that carry their own `writeTo` and `parseFrom`, written out field by field, and this
bridge adapts those two methods. It generates nothing and it rewrites nothing.

What sets it apart from every other bridge in b8 is that a ScalaPB message knows its exact encoded length
before a byte is written. `serializedSize` walks the value once, memoizes the answer on the instance, and
that number is what `sizeHint` returns — the truth rather than an estimate. This is therefore the only
bridge with no size guess and no retry path anywhere in it. Encoding into an `ArraySink` is one `ensure`,
one `CodedOutputStream` laid over the sink's own array, one `checkNoSpaceLeft`: exactly the sequence
ScalaPB's own `toByteArray` runs, minus its array allocation. Decoding lays a `CodedInputStream` over the
`ByteSource` window where it lies, so nothing is copied on that side either.

One thing to know before the snippets confuse you. The message types they use — `PFlat`, `PNested`,
`PRecursive` — come from a `.proto` that b8's own build compiles for its own tests, and they are not part
of anything b8 publishes. In your project the equivalent types come out of your own sbt-protoc setup, and
every line on this page reads the same for them. The same goes for `b8.scalapb.ProtoFixtures`, which turns
up in one snippet below: it is a test helper holding one prepared `PNested`, it has no equivalent to
generate, and anywhere it appears you would put a message of your own.

## Installation

> **Not yet released.** The coordinates below are what the first release will publish to Maven Central for
> Scala 3.

```scala
libraryDependencies += "de.thatscalaguy" %% "b8-scalapb" % "0.1.0"
```

The module depends on `scalapb-runtime` and on nothing else.

### b8 generates no protobuf code

This is the prerequisite no other bridge page has, so it comes before anything else on this one: **b8 runs
no protoc, adds no code generation and contributes no `.proto` of its own.** The bridge adapts the types
ScalaPB has already produced. Setting up sbt-protoc and ScalaPB is your own job, it looks exactly the same
whether or not b8 is in the build, and until it is done there is nothing here for the bridge to adapt.

The standard setup is two files under `project/` and one setting in `build.sbt`. In `project/plugins.sbt`,
the plugin that runs protoc:

```scala
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.8")
```

In `project/scalapb.sbt`, the code generator the plugin invokes:

```scala
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.20"
```

And in `build.sbt`, where the generated sources go:

```scala
Compile / PB.targets := Seq(
  scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"
)
```

`.proto` sources are picked up from `src/main/protobuf` by default. Everything past this point — package
options, gRPC services, generating into `Test` rather than `Compile`, Java interop — is ScalaPB's own
business and [its documentation](https://scalapb.github.io/docs/sbt-settings) is the place to read about
it. Keep the `compilerplugin` version in step with the `scalapb-runtime` that `b8-scalapb` pulls in: the
generated sources are compiled against that runtime and the two are meant to move together.

Two things bite on Scala 3, and b8's own build hit both.

**Wildcards.** ScalaPB's default output spells a wildcard type argument `[_]`. Under
`-Ykind-projector:underscores`, which sbt-typelevel turns on for you, `_` is a type-lambda placeholder
rather than a wildcard, so the generated sources stop compiling — a hard error, not a warning. The
generator has an option for it, and it is the fix:

```scala
Compile / PB.targets := Seq(
  scalapb.gen(scalapb.GeneratorOption.Scala3Sources) ->
    (Compile / sourceManaged).value / "scalapb"
)
```

**`-Wvalue-discard`.** Generated parsers discard the builder that `parseField` hands back, which is one
warning per generated message, and a build that promotes warnings to errors stops right there. Silence the
generated sources, and only those:

```scala
scalacOptions += "-Wconf:src=.*/src_managed/.*:s"
```

## Getting started

Two things have to be in scope: `b8.array.*` for the extension methods and `b8.scalapb.given` for the
bridge. There is no third one, and that is the difference from every other bridge here: you bring no codec.
ScalaPB already put a `GeneratedMessageCompanion` into each message's companion object, that instance is
what the given picks up, and so a message type is ready the moment protoc has run over its `.proto`.

```scala mdoc:silent
import b8.Format
import b8.array.*
import b8.scalapb.given
import b8.scalapb.protos.PFlat
import b8.scalapb.protos.PNested
```

From there, encoding and decoding are methods on the values themselves:

```scala mdoc
val flat = PFlat(id = 1L, name = "Ada", active = true)

val bytes = flat.encode[Format.Proto]

bytes.length

bytes.decodeAs[PFlat, Format.Proto]
```

Nine bytes: a tag and a value for each of the three fields that were set. `score` and `tags` were left at
their defaults and proto3 writes neither, which is the rule to have in mind before the first message goes
out — for a scalar field, "absent" and "set to the default" are the same bytes and stay the same value.

`encodeTo` reads the same and takes any `ByteSink` you already own, which skips the exact-size array
`encode` hands back.

## The exact-size fast path

The jsoniter page has a section about guessing a buffer size and about what it costs when the guess is
short. This page does not need one. `serializedSize` is not an estimate: it is the number of bytes
`writeTo` is about to write, computed by walking the value once and then memoized on the instance, so the
`sizeHint` that b8 asks the sink for is the exact answer. A sink sized from it is sized right the first
time and never grows, and on the `ArraySink` path `checkNoSpaceLeft` checks that promise against what was
actually written before the sink's position moves. There is no second pass over the value to avoid,
because there is no retry.

```scala mdoc:silent
import b8.SinkPool
import b8.scalapb.ProtoFixtures.pNested1
```

```scala mdoc
pNested1.serializedSize

pNested1.encode[Format.Proto].length

pNested1.encode[Format.Proto].sameElements(pNested1.toByteArray)
```

That last line is the whole claim of the bridge in one expression: the bytes are ScalaPB's, unchanged. What
differs is where they were written — into the sink b8 laid the `CodedOutputStream` over, rather than into an
array allocated for the purpose and then handed on.

There is nothing to tune here, then, and one thing left to save. `encode` still borrows an `ArraySink` per
call and still copies the finished message out of it with `result()`, because that is what "hand me an
`Array[Byte]`" means. A pool removes the first of the two:

```scala mdoc:silent
given SinkPool = SinkPool.threadLocal()
```

Each thread then keeps one sink and reuses its array, so a hot encode loop allocates the result copy and
the `CodedOutputStream` laid over the sink, and nothing else. To lose the copy as well, call `encodeTo`
with the sink you already own and let the message be written straight into it.

## Configuration

There is none, and the three factories exist only to let you name an instance rather than let the given
build a fresh one:

```scala
b8.scalapb.encoder[A <: GeneratedMessage]: Encoder[A, Proto]

b8.scalapb.decoder[A <: GeneratedMessage](using
  GeneratedMessageCompanion[A]
): Decoder[A, Proto]

b8.scalapb.codec[A <: GeneratedMessage](using
  GeneratedMessageCompanion[A]
): Codec[A, Proto]
```

`encoder` needs no companion because writing asks only the value: every `GeneratedMessage` carries both
`serializedSize` and `writeTo` itself. Reading has to build a value from nothing, which is what the
companion is for.

That emptiness is deliberate and worth explaining, because protobuf has exactly two knobs you will come
looking for and **ScalaPB reads neither**. Offering them would have meant offering guarantees the backend
does not make.

**`CodedOutputStream.useDeterministicSerialization`** is protobuf's switch for writing map entries in a
stable order, and protobuf-java's own map writer honours it. ScalaPB-generated code never reaches that
writer: a `map<string, string>` field is a Scala `Map`, and the generated `writeTo` iterates it directly and
writes the entries in whatever order it gets them. The word does not occur anywhere in the sources the
0.11.20 generator emits, and setting the flag by hand on a `CodedOutputStream` changes no byte of what
ScalaPB writes.

So the order of a map field on the wire is the Scala `Map`'s iteration order, and that is a worse thing to
depend on than it first looks. Two messages that are `==` but whose maps were built in different insertion
orders encode **differently** when the map holds four entries or fewer, because `Map1` through `Map4` keep
the order they were built in — and encode **identically** from five entries on, where Scala switches to a
hash-ordered `HashMap`. Which of the two you get is decided by the collection's internal representation,
not by anything protobuf or this bridge promises:

```scala mdoc
val m1 = Map("a" -> "1", "b" -> "2")
val m2 = Map("b" -> "2", "a" -> "1")

m1 == m2

PNested(meta = m1).encode[Format.Proto]
  .sameElements(PNested(meta = m2).encode[Format.Proto])
```

What does hold is the weaker property most callers reaching for the flag actually want: one value encodes
to the same bytes every time, because one `Map` iterates the same way twice. If you need stable bytes across
values — to hash, sign or content-address them — compare the parsed messages instead, or carry the entries
in a `repeated` field the sender sorts.

**`CodedInputStream.setRecursionLimit`** is protobuf's bound on nesting depth, and ScalaPB does not enforce
it either. A nested message field is read through `scalapb.LiteParser.readMessage`, which pushes a length
limit and then recurses without ever touching protobuf's recursion counter, so nothing compares a depth
against the limit. Two hundred levels — twice protobuf's own default of 100 — come back whole:

```scala mdoc:silent
import b8.scalapb.protos.PRecursive

def chain(depth: Int): PRecursive =
  (1 to depth).foldLeft(PRecursive(label = "leaf"))((inner, _) =>
    PRecursive(child = Some(inner))
  )
```

```scala mdoc
chain(200).encode[Format.Proto].decodeAs[PRecursive, Format.Proto].isRight
```

Push the depth far enough and the parse does fail, but it fails as a `StackOverflowError` — which `decode`
does not catch, which no `Try` would catch either, and which is not a `DecodeError`. Against untrusted
input, bound the **length** instead: every level of nesting costs at least two bytes on the wire, so a cap
on the size of the message you are willing to read is a cap on how deep it can be.

Both facts are pinned by tests — the first by setting protobuf's flag by hand and checking that ScalaPB
still ignores it — so a release that starts honouring either one fails the build and brings someone back to
this section.

## Errors

Malformed input becomes b8's single error type. Only `com.google.protobuf.InvalidProtocolBufferException`
is caught, and it becomes `DecodeError(e.getMessage, "Proto", e)` with the original kept as the cause. That
one type is enough because it is what both protobuf-java and ScalaPB's own unknown-field reader throw for
everything that is wrong at the wire level. Protobuf's messages say what went wrong and never where, so b8
appends no offset of its own — it has none to append and will not invent one. The `DecodeError` itself
carries no stack trace, because b8 never fills one in; unlike jsoniter's exceptions, protobuf's do fill in
theirs, so the trace is on the cause if you want it and costs what it costs.

```scala mdoc:silent
def why(bs: Array[Byte]): String =
  bs.decodeAs[PFlat, Format.Proto].fold(_.message, _.toString)
```

```scala mdoc
why(bytes :+ 0x00.toByte)

why(bytes.take(4))

why(Array[Byte](0x0e, 0x01))
```

A trailing NUL is an invalid tag, because a tag encodes the field number in its upper bits and field number
0 does not exist. A truncated message is a field that promised more bytes than the input has. `0x0e` is
field 1 with wire type 6, and protobuf defines no wire type 6, so there is no way to skip past it either.

One failure surprises people by being a failure at all: **a `string` field whose bytes are not valid
UTF-8 is rejected.** ScalaPB generates `readStringRequireUtf8`, not the lenient reader, so nothing is
substituted and nothing is repaired.

```scala mdoc
why(Array[Byte](0x12, 0x01, 0xff.toByte))
```

Two things that readers do expect to be errors, and are not. A **known field arriving with the wrong wire
type** is not rejected: the parser matches on the whole tag, field number and wire type together, so a
mismatch simply does not match, the bytes are demoted to an unknown field, and the declared field is left
at its default. And a **field that arrives twice** is not rejected either: for a scalar the last occurrence
wins, quietly.

```scala mdoc
val wrongWireType = Array[Byte](0x0a, 0x01, 0x01)

wrongWireType.decodeAs[PFlat, Format.Proto]

val idTwice = Array[Byte](0x08, 0x01, 0x08, 0x02)

idTwice.decodeAs[PFlat, Format.Proto].map(_.id)
```

Both are the wire format working as specified rather than the bridge being lax. Neither is something a
decoder can tighten without breaking protobuf's compatibility rules, so if a message has to be rejected for
either reason, that check belongs in your code after the decode.

## Protobuf semantics

Protobuf is the format on this site with the most opinions of its own, and three of them reach far enough
into the b8 API to be worth stating here. The bridge does not soften any of them.

### Unknown fields are preserved

A field the reader's schema does not mention is not an error and is not dropped. It is parsed, kept in the
message's `unknownFields`, and written back out the next time the message is encoded. This is ScalaPB's
default, it is what makes a proxy safe to put between two services that were generated from different
versions of a `.proto`, and the bridge does not touch it.

```scala mdoc:silent
def hex(bs: Array[Byte]): String = bs.map(b => f"$b%02x").mkString(" ")
```

```scala mdoc
val extended = bytes ++ Array[Byte](0x38, 0x01)

val kept = extended.decodeAs[PFlat, Format.Proto]

kept.map(_.unknownFields)

kept.fold(_.message, m => hex(m.encode[Format.Proto]))
```

`0x38 0x01` is field 7, a varint, and `PFlat` has no field 7. It survives the round trip and comes back out
at the end of the re-encoded message.

### Trailing bytes are, by design, not detectable

`Decoder`'s contract says a decode consumes the whole source, and here that holds only as far as the wire
format allows it to. The block above is the proof: two bytes appended after a complete message were not
left over, they were a field. Protobuf cannot tell "bytes that follow the message" from "a field this
reader has not been told about", and a format that could would not have the forward compatibility protobuf
is chosen for.

What is rejected is the one case the wire format really does forbid, which is the `0x00` from the errors
section: field number 0 is illegal, so a single NUL after a message is an invalid tag rather than an
unknown field. That is what the law suite's `trailingRejected` property pins down, and it is the only
trailing byte that could be pinned down.

If bytes after the message must be detected, that is a job for the framing around it, not for the decoder.

### An empty input is a valid message

Zero bytes is a well-formed proto3 message in which every field holds its default. It decodes, and it
decodes to the default instance:

```scala mdoc
Array.emptyByteArray.decodeAs[PNested, Format.Proto]
```

So a decode that succeeds tells you nothing about whether anything was sent. A caller that has to tell
"absent" apart from "empty" — a cache miss from a cached empty value, say — has to carry that distinction
outside the message, in the frame or in the envelope, because protobuf will not carry it for you.

## Framing

Protobuf messages are not self-delimiting. A message ends where its bytes end, and a reader given two of
them back to back reads one merged message rather than two, so anything that puts more than one message
into a stream needs a length prefix of its own.

For a single message written to or read from a stream, ScalaPB already has the standard answer:
`writeDelimitedTo` and `parseDelimitedFrom` write and read a varint length in front of the message, which
is the same framing protobuf's other implementations use. For a pipe of them, that belongs in `b8-fs2`,
which is a stub today. Either way it does not belong in the bridge, which deliberately frames nothing: it
writes the message and stops.

## When to use it

Pick `b8-scalapb` when the wire format is already protobuf — when a `.proto` is the contract, when the
other end is a service or a topic that was generated from that same file, or when the bytes have to be read
by a language that is not Scala. This is not a bridge to choose for its performance over a Scala-native
format; it is the bridge to choose when the format is not yours to choose.

Having said that, the façade costs less here than in any other b8 bridge, and for a reason that is
structural rather than lucky: the exact size removes the one thing the other bridges have to guess at, so
what is left between `encode` and ScalaPB's own `toByteArray` is a virtual call and the sink it wrote
through — and `encodeTo` into a sink you already own removes the one array `toByteArray` cannot avoid.
`benchmarks/ScalapbBench` measures the pair side by side; numbers belong to the machine they were taken on,
so none are quoted here.

One habit is worth picking up in a hot loop. The given takes a type parameter, so it is a method, and every
place the compiler summons it builds a fresh `ScalapbCodec` — inside `xs.map(_.encode[Format.Proto])` that
means one per element. Here the cost is only the allocation, since there is no size-hint history for a new
instance to have lost, but it is an allocation per message on a path whose whole point is not having any.
Bind the instance once where the loop can see it:

```scala mdoc:silent
import b8.Codec

object hot:
  given Codec[PFlat, Format.Proto] = b8.scalapb.codec

  def encodeAll(flats: List[PFlat]): List[Array[Byte]] =
    flats.map(_.encode[Format.Proto])
```
