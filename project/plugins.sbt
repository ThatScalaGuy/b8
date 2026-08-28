addSbtPlugin("org.typelevel" % "sbt-typelevel" % "0.8.7")
addSbtPlugin("org.typelevel" % "sbt-typelevel-site" % "0.8.7")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.8")
// Only the `b8-scalapb` test sources need this: it compiles the proto mirrors of
// the law fixtures so the bridge is exercised against real generated messages.
// Nothing is generated into any module's Compile scope. Keep the compilerplugin
// version in `project/scalapb.sbt` in step with `V.scalapb` in build.sbt.
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.8")
