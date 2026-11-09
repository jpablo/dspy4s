// Phase 0 of docs/code-harness/CODE_HARNESS.md: an embedded Scala REPL in the same JVM. The report is
// docs/code-harness/CODE_HARNESS_PHASE0.md. The build of the repository does not include this project.
// The nightly comes from the coursier cache. The nightly repository was not reachable on 2026-10-21, and the
// cache has no scaladoc_3, so the build excludes it (`doc` does not work).
ThisBuild / scalaVersion := "3.10.2-RC1-bin-20261015-88be2f5-NIGHTLY"
ThisBuild / resolvers += "Scala nightlies" at "https://repo.scala-lang.org/artifactory/maven-nightlies/"
ThisBuild / excludeDependencies += ExclusionRule("org.scala-lang", "scaladoc_3")

lazy val root = (project in file("."))
  .settings(
    name := "repl-experiment",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
    libraryDependencies += "org.scala-lang" %% "scala3-repl" % scalaVersion.value,
    Compile / run / mainClass               := Some("experiment.Main"),
    run / fork                              := true,
    run / connectInput                      := false,
    // One heap limit for all the measurements, so that the numbers compare.
    run / javaOptions ++= Seq("-Xmx4g", "-Xss4m", "-XX:+UseG1GC", "-Dfile.encoding=UTF-8"),
    run / outputStrategy := Some(StdoutOutput)
  )
