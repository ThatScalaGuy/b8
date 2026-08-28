lazy val V = new {
  val scala3 = "3.3.8"
  val fs2 = "3.13.0"
  val scodecBits = "1.2.5"
  val jsoniter = "2.40.1"
  val circe = "0.14.16"
  val borer = "1.17.0"
  val scalapb = "0.11.20"
  val munit = "1.3.5"
  val scalaCheck = "1.19.0"
  val munitScalaCheck = "1.3.0"
}

ThisBuild / tlBaseVersion := "0.1"
ThisBuild / organization := "de.thatscalaguy"
ThisBuild / organizationName := "ThatScalaGuy"
ThisBuild / startYear := Some(2026)
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers ++= List(
  tlGitHubDev("ThatScalaGuy", "Sven Herrmann")
)

// b8 targets JDK 11 and up; sbt-typelevel would otherwise default to 8, which
// recent JDKs no longer accept as a --release target.
ThisBuild / tlJdkRelease := Some(11)

ThisBuild / githubWorkflowJavaVersions := Seq(
  JavaSpec.temurin("11"),
  JavaSpec.temurin("17"),
  JavaSpec.temurin("21"),
  JavaSpec.temurin("25")
)

ThisBuild / scalaVersion := V.scala3
ThisBuild / scalacOptions ++= Seq(
  "-Wunused:all"
)

// `b8-fs2` is still a bare `package.scala`. An empty compilation unit carries no
// dependency information, which the compiler warns about and CI turns into an
// error — silence it until the module grows its first definition.
lazy val stubSettings = Seq(
  scalacOptions += "-Wconf:msg=defined in the compilation unit:s"
)

lazy val root = project
  .in(file("."))
  .enablePlugins(NoPublishPlugin)
  .aggregate(
    core,
    fs2,
    scodec,
    jsoniter,
    circe,
    borer,
    scalapb,
    laws,
    benchmarks,
    docs
  )
  .settings(
    name := "b8"
  )

lazy val core = project
  .in(file("core"))
  .settings(
    name := "b8-core",
    // b8-core deliberately has no compile-scope dependencies: standard library only.
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % V.munit % Test,
      "org.scalacheck" %% "scalacheck" % V.scalaCheck % Test,
      "org.scalameta" %% "munit-scalacheck" % V.munitScalaCheck % Test
    ),
    Test / fork := true
  )

lazy val fs2 = project
  .in(file("fs2"))
  .dependsOn(core, laws % "test->compile")
  .settings(
    name := "b8-fs2",
    stubSettings,
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % V.fs2
    ),
    Test / fork := true
  )

lazy val scodec = project
  .in(file("scodec"))
  // `jsoniter % Test` is for the tests only: the container axis has to work
  // with an ordinary bridge in scope, and running the shared fixtures through
  // one is what shows it. The module itself knows no backend and no format.
  .dependsOn(core, jsoniter % Test, laws % "test->compile")
  .settings(
    name := "b8-scodec",
    libraryDependencies ++= Seq(
      "org.scodec" %% "scodec-bits" % V.scodecBits,
      // Only the tests derive codecs; the container never needs them.
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % V.jsoniter % Test
    ),
    Test / fork := true
  )

lazy val jsoniter = project
  .in(file("jsoniter"))
  // `borer % Test` and `circe % Test` are for the mixing tests only: the one
  // showing that b8.jsoniter answers for JSON next to borer's CBOR bridge, and
  // the one pinning down what two JSON bridges in one scope actually do. Both
  // stay test-scoped.
  .dependsOn(core, borer % Test, circe % Test, laws % "test->compile")
  .settings(
    name := "b8-jsoniter",
    libraryDependencies ++= Seq(
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % V.jsoniter,
      // Only the tests derive codecs; the bridge itself never needs the
      // derivation macros.
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % V.jsoniter % Test,
      "io.bullet" %% "borer-derivation" % V.borer % Test,
      "io.circe" %% "circe-generic" % V.circe % Test
    ),
    Test / fork := true
  )

lazy val circe = project
  .in(file("circe"))
  .dependsOn(core, laws % "test->compile")
  .settings(
    name := "b8-circe",
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % V.circe,
      // The bridge parses through `io.circe.jawn.JawnParser`, which is also the
      // type of the `parser` argument on `decoder` and `codec`. circe-parser is
      // deliberately not here: it is a two-class wrapper this module never
      // names, and depending on it would put an artifact nobody uses in every
      // consumer's classpath.
      "io.circe" %% "circe-jawn" % V.circe,
      // Only the tests derive codecs; the bridge itself never needs the
      // generic derivation machinery.
      "io.circe" %% "circe-generic" % V.circe % Test
    ),
    Test / fork := true
  )

lazy val borer = project
  .in(file("borer"))
  // `circe % Test` is for one test only: the one showing that b8.borer.cbor and
  // another backend's JSON bridge coexist in a single scope, which is the whole
  // reason the per-format sub-packages exist. It stays out of the POM.
  .dependsOn(core, circe % Test, laws % "test->compile")
  .settings(
    name := "b8-borer",
    libraryDependencies ++= Seq(
      "io.bullet" %% "borer-core" % V.borer,
      // Only the tests derive codecs; the bridge itself never needs the
      // derivation macros.
      "io.bullet" %% "borer-derivation" % V.borer % Test,
      "io.circe" %% "circe-generic" % V.circe % Test
    ),
    Test / fork := true
  )

lazy val scalapb = project
  .in(file("scalapb"))
  // `jsoniter % Test` is for the mixing test only: the one showing that a Proto
  // bridge and a JSON bridge answer for their own format tags in a single
  // scope. It stays out of the POM.
  .dependsOn(core, jsoniter % Test, laws % "test->compile")
  .settings(
    name := "b8-scalapb",
    libraryDependencies ++= Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime" % V.scalapb,
      // Only the mixing test derives a codec; the bridge itself never needs
      // the derivation macros.
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % V.jsoniter % Test
    ),
    // b8 generates no protobuf code for its users — the bridge adapts whatever
    // ScalaPB already produced. The protos here exist only so the laws run
    // against real generated messages, so codegen is Test-scoped and the
    // published jar keeps exactly the one hand-written package it had before.
    // `Compile / PB.targets` defaults to `Nil` and `Test / PB.protoSources` to
    // `src/test/protobuf`, so neither needs saying.
    //
    // `_root_` is not decoration: `lazy val scalapb` above shadows the
    // generator's own `scalapb` package and the build stops loading without it.
    // `Scala3Sources` is not optional either — ScalaPB's default output spells
    // wildcards `[_]`, which sbt-typelevel's `-Ykind-projector:underscores`
    // reads as a type lambda, and that is a compile error rather than a
    // warning. `FlatPackage` keeps the generated types in `b8.scalapb.protos`
    // instead of a further `b8_fixtures` package named after the file.
    Test / PB.targets := Seq(
      _root_.scalapb.gen(
        _root_.scalapb.GeneratorOption.Scala3Sources,
        _root_.scalapb.GeneratorOption.FlatPackage
      ) -> (Test / sourceManaged).value / "scalapb"
    ),
    // ScalaPB's generated parsers discard the builder `parseField` returns,
    // which `-Wvalue-discard` reports once per message and CI turns into an
    // error. Silence warnings from generated sources, and only those: the
    // module's own test sources stay under `-Wunused:all` like everything else.
    Test / scalacOptions += "-Wconf:src=.*/src_managed/.*:s",
    Test / fork := true
  )

lazy val laws = project
  .in(file("laws"))
  .dependsOn(core)
  .settings(
    name := "b8-laws",
    // The law suites are the artifact here, so munit and scalacheck are part of
    // the published compile-scope API rather than test-only dependencies.
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % V.munit,
      "org.scalacheck" %% "scalacheck" % V.scalaCheck,
      "org.scalameta" %% "munit-scalacheck" % V.munitScalaCheck
    ),
    Test / fork := true
  )

lazy val benchmarks = project
  .in(file("benchmarks"))
  // `scalapb % "compile->test"` reaches the proto classes generated for that
  // module's tests, so the ScalaPB benchmark measures the same messages the
  // laws run on without a second codegen pass. Safe only because this module
  // publishes nothing: those classes are in no jar a consumer could resolve.
  .dependsOn(
    core,
    scodec,
    jsoniter,
    circe,
    borer,
    scalapb % "compile->test",
    laws
  )
  .enablePlugins(JmhPlugin, NoPublishPlugin)
  .settings(
    name := "b8-benchmarks",
    // The benchmarks measure the bridges against the shared law fixtures, which
    // need codecs derived for each backend.
    libraryDependencies ++= Seq(
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % V.jsoniter,
      "io.circe" %% "circe-generic" % V.circe,
      "io.bullet" %% "borer-derivation" % V.borer
    ),
    // JMH generates Java sources compiled with an obsolete --release 8; under CI
    // sbt-typelevel turns warnings into errors. This is a NoPublish dev tool, so
    // don't fail its build on those warnings.
    tlFatalWarnings := false,
    Compile / javacOptions ~= (_.filterNot(_ == "-Werror"))
  )

lazy val docs = project
  .in(file("site"))
  .enablePlugins(TypelevelSitePlugin)
  // Same `compile->test` as the benchmarks, and for the same reason: the
  // ScalaPB page's snippets need a generated message, and b8's own test protos
  // are the only ones in the build. This module publishes nothing either.
  .dependsOn(core, scodec, jsoniter, circe, borer, scalapb % "compile->test")
  .settings(
    name := "b8-docs",
    // The bridge pages are mdoc-verified, so the snippets need the bridges and
    // the derivation they use to build codecs.
    libraryDependencies ++= Seq(
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % V.jsoniter,
      "io.circe" %% "circe-generic" % V.circe,
      "io.bullet" %% "borer-derivation" % V.borer
    ),
    // Read markdown sources from the repo-root `docs/` dir (deterministic; the
    // plugin otherwise inherits MdocPlugin's project-relative `site/docs` default).
    mdocIn := (ThisBuild / baseDirectory).value / "docs",
    // mdoc snippets trip `-Wunused:all`, which CI promotes to errors. Same
    // rationale as the benchmarks module — don't fail the docs build on those.
    tlFatalWarnings := false,
    // Adds an "API" link in the site navigation pointing at the published Scaladoc.
    tlSiteApiUrl := Some(
      url("https://www.javadoc.io/doc/de.thatscalaguy/b8-core_3/latest/")
    )
  )
