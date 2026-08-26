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

// fs2, scodec, jsoniter, borer and scalapb are still a bare `package.scala`. An
// empty compilation unit carries no dependency information, which the compiler
// warns about and CI turns into an error — silence it until the module grows
// its first definition.
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
  .dependsOn(core, laws % "test->compile")
  .settings(
    name := "b8-scodec",
    stubSettings,
    libraryDependencies ++= Seq(
      "org.scodec" %% "scodec-bits" % V.scodecBits
    ),
    Test / fork := true
  )

lazy val jsoniter = project
  .in(file("jsoniter"))
  .dependsOn(core, laws % "test->compile")
  .settings(
    name := "b8-jsoniter",
    stubSettings,
    libraryDependencies ++= Seq(
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % V.jsoniter,
      // Macros are only needed to derive codecs at compile time, never at runtime,
      // so they must not leak into the published POM.
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % V.jsoniter % "compile-internal"
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
      "io.circe" %% "circe-parser" % V.circe,
      // Only the tests derive codecs; the bridge itself never needs the
      // generic derivation machinery.
      "io.circe" %% "circe-generic" % V.circe % Test
    ),
    Test / fork := true
  )

lazy val borer = project
  .in(file("borer"))
  .dependsOn(core, laws % "test->compile")
  .settings(
    name := "b8-borer",
    stubSettings,
    libraryDependencies ++= Seq(
      "io.bullet" %% "borer-core" % V.borer
    ),
    Test / fork := true
  )

lazy val scalapb = project
  .in(file("scalapb"))
  .dependsOn(core, laws % "test->compile")
  .settings(
    name := "b8-scalapb",
    stubSettings,
    libraryDependencies ++= Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime" % V.scalapb
    ),
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
  .dependsOn(core, circe, laws)
  .enablePlugins(JmhPlugin, NoPublishPlugin)
  .settings(
    name := "b8-benchmarks",
    // The benchmarks measure the bridges against the shared law fixtures, which
    // need derived circe codecs.
    libraryDependencies += "io.circe" %% "circe-generic" % V.circe,
    // JMH generates Java sources compiled with an obsolete --release 8; under CI
    // sbt-typelevel turns warnings into errors. This is a NoPublish dev tool, so
    // don't fail its build on those warnings.
    tlFatalWarnings := false,
    Compile / javacOptions ~= (_.filterNot(_ == "-Werror"))
  )

lazy val docs = project
  .in(file("site"))
  .enablePlugins(TypelevelSitePlugin)
  .dependsOn(core)
  .settings(
    name := "b8-docs",
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
