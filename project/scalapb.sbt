// The code generator sbt-protoc runs. It has to match the `scalapb-runtime` the
// generated sources are compiled against, which is `V.scalapb` in build.sbt —
// the meta-build cannot see that value, so the version is written out here and
// the two are bumped together (scala-steward groups `com.thesamet.scalapb`).
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.20"
