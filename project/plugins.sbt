// Project-level helpers. Secrets loading is performed by DotEnvLoader.scala
// (no external plugins required).

addSbtPlugin("org.scalameta"      % "sbt-scalafmt"  % "2.5.4")
addSbtPlugin("pl.project13.scala" % "sbt-jmh"       % "0.4.8")
addSbtPlugin("org.scoverage"      % "sbt-scoverage" % "2.4.4")
addSbtPlugin("io.stryker-mutator" % "sbt-stryker4s" % "0.20.3")
