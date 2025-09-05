import sbt._
import Keys._

val scala3Version = "3.7.2"

val sttpV     = "3.9.5"
val upickleV  = "4.0.2"
val munitV    = "1.0.0"

lazy val commonSettings = Seq(
  scalaVersion := scala3Version,
  organization := "ai.dspy",
  ThisBuild / version := "0.1.0-SNAPSHOT",
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature"
  )
)

lazy val root = project
  .in(file("."))
  .aggregate(core, clients, predict, examples)
  .settings(commonSettings)
  .settings(
    name := "dspy4s",
    publish / skip := true
  )

lazy val core = project
  .in(file("core"))
  .settings(commonSettings)
  .settings(
    name := "dspy4s-core",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % upickleV,
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
      "com.softwaremill.sttp.client3" %% "core"               % sttpV,
      "com.softwaremill.sttp.client3" %% "httpclient-backend" % sttpV,
      "com.lihaoyi" %% "upickle" % upickleV,
      "org.scalameta" %% "munit"   % munitV % Test
    )
  )

lazy val predict = project
  .in(file("predict"))
  .dependsOn(core, clients)
  .settings(commonSettings)
  .settings(
    name := "dspy4s-predict",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % upickleV,
      "org.scalameta" %% "munit"   % munitV % Test
    )
  )

lazy val examples = project
  .in(file("examples"))
  .dependsOn(predict)
  .settings(commonSettings)
  .settings(
    name := "dspy4s-examples",
    publish / skip := true
  )
