import sbt.*
import Keys.*

val scala3Version = "3.7.2"

val sttpV    = "4.0.9"
val upickleV = "4.3.0"
val munitV   = "1.1.1"

lazy val commonSettings = Seq(
  scalaVersion        := scala3Version,
  organization        := "ai.dspy",
  ThisBuild / version := "0.1.0-SNAPSHOT",
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature"
  )
)

lazy val root = project
  .in(file("."))
  .aggregate(core, clients, adapters, predict, evaluate, examples)
  .settings(commonSettings)
  .settings(
    name           := "dspy4s",
    publish / skip := true
  )

lazy val core = project
  .in(file("core"))
  .settings(commonSettings)
  .settings(
    name := "dspy4s-core",
    libraryDependencies ++= Seq(
      "com.lihaoyi"   %% "upickle" % upickleV,
      "org.scalameta" %% "munit"   % munitV % Test
    )
  )

lazy val clients = project
  .in(file("clients"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "dspy4s-clients",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %% "core"    % sttpV,
      "com.lihaoyi"                   %% "upickle" % upickleV,
      "org.scalameta"                 %% "munit"   % munitV % Test
    )
  )

lazy val adapters = project
  .in(file("adapters"))
  .dependsOn(core, clients)
  .settings(commonSettings)
  .settings(
    name := "dspy4s-adapters",
    libraryDependencies ++= Seq(
      "com.lihaoyi"   %% "upickle" % upickleV,
      "org.scalameta" %% "munit"   % munitV % Test
    )
  )

lazy val predict = project
  .in(file("predict"))
  .dependsOn(core, clients, adapters)
  .settings(commonSettings)
  .settings(
    name := "dspy4s-predict",
    libraryDependencies ++= Seq(
      "com.lihaoyi"   %% "upickle" % upickleV,
      "org.scalameta" %% "munit"   % munitV % Test
    )
  )

lazy val evaluate = project
  .in(file("evaluate"))
  .dependsOn(core, predict)
  .settings(commonSettings)
  .settings(
    name := "dspy4s-evaluate",
    libraryDependencies ++= Seq(
      "com.lihaoyi"   %% "upickle" % upickleV,
      "org.scalameta" %% "munit"   % munitV % Test
    )
  )

lazy val examples = project
  .in(file("examples"))
  .dependsOn(predict)
  .settings(commonSettings)
  .settings(
    name := "dspy4s-examples",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %% "core" % sttpV
    ),
    publish / skip := true
  )
