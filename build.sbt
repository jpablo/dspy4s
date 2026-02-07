ThisBuild / organization := "io.github.jpablo"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.8.1"

lazy val munitVersion = "1.1.1"

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked"
  )
)

lazy val root = (project in file("."))
  .aggregate(
    core,
    lm,
    adapters,
    programs,
    evaluation,
    optimize,
    streaming
  )
  .settings(commonSettings)
  .settings(
    name := "dspy4s",
    publish / skip := true
  )

lazy val core = (project in file("modules/core"))
  .settings(commonSettings)
  .settings(name := "dspy4s-core")
  .settings(
    libraryDependencies += "org.scalameta" %% "munit" % munitVersion % Test,
    Test / parallelExecution := false
  )

lazy val lm = (project in file("modules/lm"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(name := "dspy4s-lm")
  .settings(
    libraryDependencies += "org.scalameta" %% "munit" % munitVersion % Test
  )

lazy val adapters = (project in file("modules/adapters"))
  .dependsOn(core, lm)
  .settings(commonSettings)
  .settings(name := "dspy4s-adapters")

lazy val programs = (project in file("modules/programs"))
  .dependsOn(core, lm, adapters)
  .settings(commonSettings)
  .settings(name := "dspy4s-modules")
  .settings(
    libraryDependencies += "org.scalameta" %% "munit" % munitVersion % Test
  )

lazy val evaluation = (project in file("modules/eval"))
  .dependsOn(core, programs)
  .settings(commonSettings)
  .settings(name := "dspy4s-eval")

lazy val optimize = (project in file("modules/optimize"))
  .dependsOn(core, programs, evaluation)
  .settings(commonSettings)
  .settings(name := "dspy4s-optimize")

lazy val streaming = (project in file("modules/streaming"))
  .dependsOn(core, lm, adapters, programs)
  .settings(commonSettings)
  .settings(name := "dspy4s-streaming")
