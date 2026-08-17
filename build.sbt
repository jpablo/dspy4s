ThisBuild / organization := "io.github.jpablo"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.8.4"

addCommandAlias("fmt", ";scalafmtAll;scalafmtSbt")
addCommandAlias("fmtCheck", ";scalafmtCheckAll;scalafmtSbtCheck")
addCommandAlias("bench", "benchmarks/Jmh/run -i 5 -wi 3 -f 2 -t 1")
addCommandAlias("benchQuick", "benchmarks/Jmh/run -i 2 -wi 1 -f 1 -t 1")
addCommandAlias(
  "coverageAll",
  ";clean;coverage;core/Compile/copyResources;test;coverageReport;coverageAggregate"
)
addCommandAlias("mutationOptics", ";project programs;stryker")

lazy val munitVersion           = "1.3.4"
lazy val munitScalacheckVersion = "1.3.0" // own line; munit-scalacheck has no 1.3.1 (patch-compatible with munit 1.3.1)
lazy val zioBlocksVersion       = "0.0.41"
lazy val zioVersion             = "2.1.26"
lazy val ujsonVersion           = "4.4.3"
lazy val dotenvVersion          = "3.2.0"
lazy val scalaXmlVersion        = "2.4.0"
lazy val ironVersion            = "3.3.2"

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-language:strictEquality",
    "-Werror",
    "-Wunused:all",
    "-Wvalue-discard",
    "-Wnonunit-statement",
    "-Wimplausible-patterns",
    "-Wsafe-init",
    "-Wshadow:all",
    "-Wenum-comment-discard",
    "-Wrecurse-with-default",
    "-Wwrong-arrow",
    "-explain"
  )
)

ThisBuild / strykerReporters         := Seq("console", "html", "json")
ThisBuild / strykerThresholdsBreak   := 0
ThisBuild / strykerExcludedMutations := Seq("StringLiteral")

lazy val root = (project in file("."))
  .aggregate(
    algebra,
    core,
    signatures,
    lm,
    adapters,
    programs,
    evaluate,
    optimize,
    gepa,
    streaming,
    examples
  )
  .settings(commonSettings)
  .settings(
    name           := "dspy4s",
    publish / skip := true
  )

// Generic algebraic and categorical structures. This module is intentionally
// dependency-free so every higher layer can share the same law vocabulary
// without pulling in runtime, schema, or program machinery.
lazy val algebra = (project in file("modules/algebra"))
  .settings(commonSettings)
  .settings(name := "dspy4s-algebra")
  .settings(
    libraryDependencies += "org.scalameta" %% "munit" % munitVersion % Test
  )

// `core` owns the contract layer that everything else builds on. We pull in
// zio-blocks-schema here because `DynamicValue` is the spine type carried
// through `Example.values`, `ProgramCall.input`, `RawPrediction.values`,
// and `ParsedOutput.values` — the codec intermediate shared by adapters,
// programs, evaluate, and the signatures API.
lazy val core = (project in file("modules/core"))
  .dependsOn(algebra)
  .settings(commonSettings)
  .settings(name := "dspy4s-core")
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio"            %% "zio-blocks-schema" % zioBlocksVersion,
      "io.github.iltotore" %% "iron"              % ironVersion,
      "org.scalameta"      %% "munit"             % munitVersion           % Test,
      "org.scalameta"      %% "munit-scalacheck"  % munitScalacheckVersion % Test
    ),
    Test / parallelExecution := false
  )

// Static signatures layer. zio-blocks-schema is the structured codec backend
// behind `Shape` derivation (see ZioSchemaCodec) and provides the Schema
// typeclass that `Signature.derived` / `Shape.derived` summon.
lazy val signatures = (project in file("modules/signatures"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(name := "dspy4s-signatures")
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio"       %% "zio-blocks-schema" % zioBlocksVersion,
      "org.scalameta" %% "munit"             % munitVersion % Test
    )
  )

lazy val lm = (project in file("modules/lm"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(name := "dspy4s-lm")
  .settings(
    libraryDependencies ++= Seq(
      "io.github.iltotore" %% "iron"        % ironVersion,
      "org.scalameta"      %% "munit"       % munitVersion  % Test,
      "com.lihaoyi"        %% "ujson"       % ujsonVersion,
      "io.github.cdimascio" % "dotenv-java" % dotenvVersion % Test
    ),
    Test / fork := true,
    Test / javaOptions += "-Dfile.encoding=UTF-8"
  )

lazy val adapters = (project in file("modules/adapters"))
  .dependsOn(core, lm)
  .settings(commonSettings)
  .settings(name := "dspy4s-adapters")
  .settings(
    libraryDependencies ++= Seq(
      "org.scalameta"          %% "munit"     % munitVersion % Test,
      "com.lihaoyi"            %% "ujson"     % ujsonVersion,
      "org.scala-lang.modules" %% "scala-xml" % scalaXmlVersion
    )
  )

lazy val programs = (project in file("modules/programs"))
  .dependsOn(core, lm, adapters, signatures)
  .settings(commonSettings)
  .settings(name := "dspy4s-programs")
  .settings(
    libraryDependencies ++= Seq(
      "io.github.iltotore" %% "iron"  % ironVersion,
      "dev.zio"            %% "zio"   % zioVersion,
      "org.scalameta"      %% "munit" % munitVersion % Test
    ),
    // Focus mutation testing on the syntax, interpreter, and stable parameter store.
    strykerMutate := Seq(
      "src/main/scala/dspy4s/programs/Program.scala",
      "src/main/scala/dspy4s/programs/ProgramRunner.scala",
      "src/main/scala/dspy4s/programs/ParameterStore.scala"
    )
  )

lazy val evaluate = (project in file("modules/evaluate"))
  .dependsOn(core, programs)
  .settings(commonSettings)
  .settings(name := "dspy4s-evaluate")
  .settings(
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % munitVersion % Test,
      "com.lihaoyi"   %% "ujson" % ujsonVersion
    )
  )

lazy val optimize = (project in file("modules/optimize"))
  .dependsOn(core, programs, evaluate)
  .settings(commonSettings)
  .settings(name := "dspy4s-optimize")
  .settings(
    libraryDependencies ++= Seq(
      "io.github.iltotore" %% "iron"  % ironVersion,
      "org.scalameta"      %% "munit" % munitVersion % Test
    )
  )

// GEPA — Genetic-Pareto reflective prompt optimizer (PORT_GAPS G-12). A self-contained port of the external
// Genetic-Pareto prompt optimization over functional record programs and explicit interpreter events.
lazy val gepa = (project in file("modules/gepa"))
  .dependsOn(core, programs, evaluate, optimize)
  .settings(commonSettings)
  .settings(name := "dspy4s-gepa")
  .settings(
    libraryDependencies ++= Seq(
      "io.github.iltotore" %% "iron"  % ironVersion,
      "org.scalameta"      %% "munit" % munitVersion % Test
    )
  )

lazy val streaming = (project in file("modules/streaming"))
  .dependsOn(core, lm, adapters, programs)
  .settings(commonSettings)
  .settings(name := "dspy4s-streaming")
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio"       %% "zio-streams" % zioVersion,
      "org.scalameta" %% "munit"       % munitVersion % Test
    ),
    Test / fork := true,
    Test / javaOptions += "-Dfile.encoding=UTF-8"
  )

// Small compiler-checked examples for the functional API.
lazy val examples = (project in file("modules/examples"))
  .dependsOn(core, lm, adapters, programs, signatures, evaluate, optimize, gepa, streaming)
  .settings(commonSettings)
  .settings(name := "dspy4s-examples")
  .settings(
    publish / skip := true
  )

// JMH stays outside the root aggregate. Normal compile and test runs do not pay for benchmark generation.
lazy val benchmarks = (project in file("benchmarks"))
  .enablePlugins(JmhPlugin)
  .dependsOn(algebra, programs)
  .settings(commonSettings)
  .settings(
    name           := "dspy4s-benchmarks",
    publish / skip := true
  )
