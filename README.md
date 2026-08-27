<div align="center">

# b8 📦➡️🔢

**A universal serialization façade for Scala 3 — any value, to bytes, in any format, through the backend you already trust.**

_Pick the format with a type. Pick the backend with an import. Pick the container with a package._

[![Maven Central](https://img.shields.io/maven-central/v/de.thatscalaguy/b8-core_3?style=flat-square&logo=apachemaven&logoColor=white&label=Maven%20Central&color=blue)](https://central.sonatype.com/artifact/de.thatscalaguy/b8-core_3)
[![CI](https://img.shields.io/github/actions/workflow/status/ThatScalaGuy/b8/ci.yml?branch=main&style=flat-square&logo=github&label=CI)](https://github.com/ThatScalaGuy/b8/actions/workflows/ci.yml)
[![javadoc](https://javadoc.io/badge2/de.thatscalaguy/b8-core_3/scaladoc.svg?style=flat-square&label=API%20docs)](https://javadoc.io/doc/de.thatscalaguy/b8-core_3)
<br/>
[![Scala 3.3 LTS](https://img.shields.io/badge/Scala-3.3%20LTS-DC322F?style=flat-square&logo=scala&logoColor=white)](https://www.scala-lang.org/)
[![JDK 11+](https://img.shields.io/badge/JDK-11%2B-007396?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net/)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue?style=flat-square)](https://www.apache.org/licenses/LICENSE-2.0)

</div>

---

`b8` is the thin layer between your domain types and the bytes on the wire. It does not encode anything
itself — jsoniter-scala, circe, borer and ScalaPB already do that better than a new library could. What
`b8` gives you is one API in front of all of them, so that swapping JSON for CBOR, or circe for
jsoniter-scala, is a one-line change instead of a rewrite. The façade costs nothing per field and at most
one virtual call per message: the backend writes straight into the buffer `b8` hands it. 🎉

## 🤖 AI Usage Disclaimer

This project is developed with the assistance of AI tools. Specifically, AI is used to:

- write documentation, large parts of the code comments, commit messages, and PR/release descriptions
- build the benchmark harness and a portion of the test suite
- verify the implementation against the format specifications the backends implement
- perform small refactorings, code adjustments, and style guide enforcement

All AI-generated documents and code are reviewed by the maintainer before they are merged.

## ✨ Highlights

- 🧭 **Three independent axes** — the format is a type parameter, the backend is an import, the target container is a package
- 🪶 **Zero-dependency core** — `b8-core` is standard library only, so it never drags a JSON parser into a module that does not want one
- ⚡ **No overhead per field** — encoders write into the sink's buffer directly; the façade adds at most one virtual call per message
- 🧊 **No hidden pooling** — every `encode` allocates a fresh buffer unless you opt into `SinkPool.threadLocal()`
- 🎯 **Errors at the boundary** — exceptions inside the hot path, one `Either[DecodeError, A]` per message at the edge
- 🔌 **Bring your own format** — `Format` and its tags are plain traits, so a private wire format is a one-liner
- 🧵 **`ByteVector` today** — `b8-scodec` gives you `scodec.bits.ByteVector` as a target container; `b8-fs2` will add `Chunk[Byte]` and framing pipes, and is still a stub
- 📐 **Law-checked backends** — every bridge is held to the same suite in `b8-laws` before it counts as one

## 🧩 Compatibility

|                | Version                             |
| -------------- | ----------------------------------- |
| **Scala**      | 3.3.8 (Scala 3.3 **LTS**)           |
| **JDK**        | 11, 17, 21, 25 — **minimum JDK 11** |
| **FS2**        | 3.13.x (`b8-fs2` only)              |
| **jsoniter-scala** | 2.40.x (`b8-jsoniter` only)     |
| **circe**      | 0.14.x (`b8-circe` only)            |
| **borer**      | 1.17.x (`b8-borer` only)            |
| **scodec-bits** | 1.2.x (`b8-scodec` only)           |

> `b8-core` has no library dependencies at all. Every other module pulls in exactly one backend, so you
> depend on the bridges you actually use and nothing more.

## 📦 Installation

> **Not yet released.** The coordinates below are what the first release will publish to Maven Central for Scala 3.

**sbt**

```scala
libraryDependencies ++= Seq(
  "de.thatscalaguy" %% "b8-core" % "0.1.0",
  "de.thatscalaguy" %% "b8-jsoniter" % "0.1.0", // or -circe, -borer, -scalapb
  // b8 does not re-export a backend's derivation macros: how you come by your
  // codecs stays your decision. The internal scopes keep them out of your POM;
  // `test-internal` does not extend `compile-internal`, so add both if you
  // derive codecs in test sources too.
  "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.40.1" % "compile-internal"
)
```

**Mill**

```scala
ivy"de.thatscalaguy::b8-core:0.1.0"
ivy"de.thatscalaguy::b8-jsoniter:0.1.0"
ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros:2.40.1" // compileIvyDeps
```

**scala-cli**

```scala
//> using dep de.thatscalaguy::b8-core:0.1.0
//> using dep de.thatscalaguy::b8-jsoniter:0.1.0
//> using dep com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros:2.40.1
```

## 🚀 Quick Start

Three imports decide everything: `b8.*` brings the type classes, `b8.jsoniter.given` picks the backend,
`b8.array.*` picks `Array[Byte]` as the target container.

```scala
import b8.*
import b8.array.*
import b8.jsoniter.given

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

case class User(id: Long, name: String)

given JsonValueCodec[User] = JsonCodecMaker.make

val user = User(1L, "Ada")

val bytes: Array[Byte] = user.encode[Format.Json]
val back: Either[DecodeError, User] = bytes.decodeAs[User, Format.Json]
```

Switching the backend is one import — nothing else in the snippet changes:

```scala
import b8.circe.given // instead of b8.jsoniter.given
```

jsoniter-scala is the backend to reach for unless you have a reason not to —
[docs/jsoniter.md](docs/jsoniter.md) covers its configuration, the adaptive size hint and when reentrancy
matters. `b8-circe` is the compatibility bridge for codebases already built on `io.circe.Json`
([docs/circe.md](docs/circe.md)), and `b8-borer` covers CBOR and JSON from one set of instances
([docs/borer.md](docs/borer.md)).

Switching the target container is one import as well:

```scala
import b8.vector.* // scodec.bits.ByteVector, from b8-scodec
// or b8.chunk.*   — fs2.Chunk[Byte], from b8-fs2
```

One at a time, though: the container packages declare the same four names, so importing two of them in one
file is a compile error by design — one container per file, the same rule as one backend per format.
`b8-scodec` is the one that exists today, and [docs/scodec.md](docs/scodec.md) covers what a `ByteVector`
costs on each side and when to `compact` one.

### Writing into a buffer you own

`encode` allocates an exact-size array. When you already have a destination, encode into it instead and
skip the copy:

```scala
import java.nio.ByteBuffer

val sink = ByteBufferSink(ByteBuffer.allocate(4096))
user.encodeTo[Format.Json](sink)
```

`ArraySink` is the growable one, `OutputStreamSink` writes straight through to a stream.

### Reusing buffers

Nothing is pooled unless you say so. Opt in per scope:

```scala
given SinkPool = SinkPool.threadLocal()
```

Each thread then reuses one `ArraySink` across encodes, and a sink that grew past the retain limit is
dropped instead of pinned to the thread.

### Your own format

`Format` is not sealed, so a private wire format needs no changes to b8:

```scala
trait Avro extends Format
```

Any `Encoder[A, Avro]` you write joins the same API as the built-in tags.

## 🏗️ Architecture

```
b8
├── core/           b8-core        # no dependencies
│   ├── b8
│   │   ├── Format          # phantom tags: Text, Json, Cbor, Proto
│   │   ├── Encoder         # A => bytes, total
│   │   ├── Decoder         # bytes => A, DecodeError on malformed input
│   │   ├── Codec           # both directions
│   │   ├── ByteSink        # ArraySink / ByteBufferSink / OutputStreamSink
│   │   ├── ByteSource      # read-only (array, offset, length) window
│   │   ├── SinkPool        # none (default) / threadLocal (opt-in)
│   │   └── DecodeError     # single error type, no stack trace
│   └── b8.array            # Array[Byte] container
├── fs2/            b8-fs2         # b8.chunk (Chunk[Byte]), b8.stream (pipes, framing)
├── scodec/         b8-scodec      # b8.vector (ByteVector)
├── jsoniter/       b8-jsoniter    # b8.jsoniter — Format.Json
├── circe/          b8-circe       # b8.circe   — Format.Json
├── borer/          b8-borer       # b8.borer   — Format.Cbor, Format.Json
├── scalapb/        b8-scalapb     # b8.scalapb — Format.Proto
├── laws/           b8-laws        # the suite every backend must pass
├── benchmarks/     b8-benchmarks  # JMH, measures the façade overhead
└── site/           b8-docs        # mdoc + Laika, sources in docs/
```

## 📚 Modules

| Module           | Package                 | Provides                                                               | Status                        |
| ---------------- | ----------------------- | ---------------------------------------------------------------------- | ----------------------------- |
| `b8-core`        | `b8`, `b8.array`        | the type classes, sinks, sources, and `Array[Byte]` as a container      | ✅ implemented                |
| `b8-laws`        | `b8.laws`               | the shared suite every backend must pass, plus the fixtures it runs on  | ✅ implemented                |
| `b8-jsoniter`    | `b8.jsoniter`           | jsoniter-scala behind `Format.Json` — **the recommended JSON backend**   | ✅ implemented — [docs](docs/jsoniter.md) |
| `b8-circe`       | `b8.circe`              | circe behind `Format.Json` — the compatibility bridge                   | ✅ implemented — [docs](docs/circe.md) |
| `b8-borer`       | `b8.borer`              | borer behind `Format.Cbor` and `Format.Json`                            | ✅ implemented — [docs](docs/borer.md) |
| `b8-scalapb`     | `b8.scalapb`            | ScalaPB behind `Format.Proto`                                           | 🚧 stub                       |
| `b8-fs2`         | `b8.chunk`, `b8.stream` | `fs2.Chunk[Byte]` as a container, plus encode/decode pipes and framing   | 🚧 stub                       |
| `b8-scodec`      | `b8.vector`             | `scodec.bits.ByteVector` as a container                                 | ✅ implemented — [docs](docs/scodec.md) |
| `b8-benchmarks`  | `b8.benchmarks`         | JMH, measures the façade overhead against a direct backend call         | 🔒 not published              |
| `b8-docs`        | —                       | mdoc + Laika, sources in `docs/`                                        | 🔒 not published              |

Every stub is already wired into the build and waiting for its bridge. `b8-core` never gains a dependency,
so adding one of the others never drags a parser into a module that does not want it.

## 🧪 Testing

Run the whole suite:

```bash
sbt test
```

Run one module:

```bash
sbt core/test
```

Compile the benchmark harness (running it needs `sbt benchmarks/Jmh/run`):

```bash
sbt benchmarks/Jmh/compile
```

### Pre-push hook (optional)

A `.githooks/pre-push` hook runs the same gate CI enforces (`githubWorkflowCheck`,
`scalafmtCheckAll`/`headerCheckAll`, `test`, `mimaReportBinaryIssues`, `doc`).
Enable it once per clone:

```bash
git config core.hooksPath .githooks
```

Bypass a single push with `SKIP_PREPUSH=1 git push`.

## 💼 Commercial Support

b8 is built and maintained by **[ThatScalaGuy](https://www.thatscalaguy.de)**

Need extended support or help on your project?
**Get in touch at [thatscalaguy.de](https://www.thatscalaguy.de).**

## 🤝 Contributing

Contributions are welcome! Issues and pull requests are happily accepted over on
[GitHub](https://github.com/ThatScalaGuy/b8). The pre-push hook above runs the same checks as CI, so
enabling it is the quickest way to keep the build green.

## 📄 License

Licensed under the [Apache License 2.0](LICENSE).
