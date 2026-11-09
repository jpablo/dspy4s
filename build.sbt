ThisBuild / organization := "io.github.jpablo"
ThisBuild / version      := "0.1.0-SNAPSHOT"
// A Scala 3 nightly, the same as atc: capture checking and safe mode need it. Nightlies come from their own
// repository.
ThisBuild / scalaVersion := "3.10.2-RC1-bin-20261015-88be2f5-NIGHTLY"
ThisBuild / resolvers += "Scala nightlies" at "https://repo.scala-lang.org/artifactory/maven-nightlies/"

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
lazy val munitScalacheckVersion = "1.3.0"  // own line; munit-scalacheck has no 1.3.1 (patch-compatible with munit 1.3.1)
lazy val zioBlocksVersion       = "0.0.56" // 0.0.41 fails on Scala 3.10: its TypeId macro gives a TypeId[Any]
lazy val ujsonVersion           = "4.4.3"
lazy val dotenvVersion          = "3.2.0"
lazy val scalaXmlVersion        = "2.4.0"
lazy val ironVersion            = "3.3.2"
lazy val mcpSdkVersion          = "2.1.0"

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
    mcp,
    examples,
    harnessGuest,
    harnessKernel,
    harnessRepl
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
// zio-blocks-schema here because `DynamicValue` is the interchange format carried
// through `Example.values`, `ProgramCall.input`, `RawPrediction.values`,
// and `ParsedOutput.values` — the codec intermediate shared by adapters,
// programs, evaluate, and the signatures API. ujson reads a JSON text with the
// text of each number, so `PythonText.parseJson` keeps the number rule of Python.
lazy val core = (project in file("modules/core"))
  .dependsOn(algebra)
  .settings(commonSettings)
  .settings(name := "dspy4s-core")
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio"            %% "zio-blocks-schema" % zioBlocksVersion,
      "io.github.iltotore" %% "iron"              % ironVersion,
      "com.lihaoyi"        %% "ujson"             % ujsonVersion,
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
      "org.scalameta"      %% "munit" % munitVersion % Test
    ),
    // Keep mutation testing on the explicit-heap implementation. Mutating all program sources hits
    // a current stryker4s Scala 3 parser defect on top-level extension methods before any mutant can run.
    strykerMutate := Seq(
      "src/main/scala/dspy4s/programs/compose/AndThen.scala",
      "src/main/scala/dspy4s/programs/optimization/StackSafeOptimizableStructure.scala"
    )
  )

lazy val evaluate = (project in file("modules/evaluate"))
  .dependsOn(core, programs)
  .settings(commonSettings)
  .settings(name := "dspy4s-evaluate")
  .settings(
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % munitVersion % Test
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
// Python `gepa` engine (a stdlib-only genetic-Pareto algorithm) plus the dspy4s adapter that bridges programs
// into it. Depends on `optimize` for optimizer contracts and shares the programs-layer `OptimizableStructure` / `ProgramRunner`
// capabilities with COPRO/MIPROv2; the dependencies below make that whole substrate explicit.
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
    libraryDependencies += "org.scalameta" %% "munit" % munitVersion % Test,
    // Fork so `.env`-backed envVars (loaded by DotEnvLoader) reach the test
    // JVM — required by StreamingLiveSuite to read OPENAI_API_KEY.
    Test / fork := true,
    Test / javaOptions += "-Dfile.encoding=UTF-8"
  )

// MCP tools (upgrade item I10, PORT_GAPS G-47). The official MCP Java SDK is the one new dependency of the port. It
// stays in this module, so a program that uses no MCP server does not load it.
lazy val mcp = (project in file("modules/mcp"))
  .dependsOn(core, adapters, programs)
  .settings(commonSettings)
  .settings(name := "dspy4s-mcp")
  .settings(
    libraryDependencies ++= Seq(
      "io.modelcontextprotocol.sdk" % "mcp"   % mcpSdkVersion,
      "org.scalameta"              %% "munit" % munitVersion % Test
    ),
    // The end-to-end test starts an MCP server in a child JVM with the class path of the tests.
    Test / fork := true,
    Test / javaOptions += "-Dfile.encoding=UTF-8"
  )

// Scaffold + translations of every code snippet that appears in the upstream
// Python DSPy documentation under docs/docs/. Each .scala file mirrors a .md
// file 1:1 and links back to the source. Scaffolds are generated by
// modules/examples/tools/scaffold.py.
lazy val examples = (project in file("modules/examples"))
  .dependsOn(core, lm, adapters, programs, signatures, evaluate, optimize, gepa, streaming, mcp)
  .settings(commonSettings)
  .settings(name := "dspy4s-examples")
  .settings(
    publish / skip                         := true,
    libraryDependencies += "org.scalameta" %% "munit" % munitVersion % Test,
    // The MCP SDK logs with SLF4J. With no provider, each JVM of an MCP example prints a warning on stderr.
    libraryDependencies += "org.slf4j" % "slf4j-nop" % "2.0.17" % Runtime,
    // The MCP examples start their servers with the class path of their JVM. The class path of the sbt JVM is not the
    // class path of the examples, so `run` and `runMain` fork. The output of the fork has no sbt prefix.
    Compile / run / fork           := true,
    Compile / run / outputStrategy := Some(StdoutOutput),
    // The MCP tests start the servers of the examples in a child JVM with the class path of the tests.
    Test / fork := true,
    Test / javaOptions += "-Dfile.encoding=UTF-8"
  )

// The code harness (docs/code-harness/CODE_HARNESS_DESIGN.md, 10.5). The two modules hold phase 1, the kernel, and the
// modes of phase 2, with the laws of each as tests. docs/code-harness/CODE_HARNESS_BLUEPRINT.html shows the state of
// each part of the plan.

// The guest API: what model code calls, and the modes that limit it (5.1, 5.2).
lazy val harnessGuest = (project in file("modules/harness-guest"))
  .settings(commonSettings)
  .settings(name := "dspy4s-harness-guest")
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio"       %% "zio-blocks-schema" % zioBlocksVersion,
      "org.scalameta" %% "munit"             % munitVersion           % Test,
      "org.scalameta" %% "munit-scalacheck"  % munitScalacheckVersion % Test
    )
  )

// The kernel: the journal, the state as a fold of the journal, and the pure functions that decide each step (4).
lazy val harnessKernel = (project in file("modules/harness-kernel"))
  .dependsOn(harnessGuest)
  .settings(commonSettings)
  .settings(name := "dspy4s-harness-kernel")
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio"       %% "zio-blocks-schema" % zioBlocksVersion,
      "org.scalameta" %% "munit"             % munitVersion           % Test,
      "org.scalameta" %% "munit-scalacheck"  % munitScalacheckVersion % Test
    )
  )

// The REPL (6): ReplDriver with capture checking and safe mode. Only this module depends on the compiler. It uses the
// kernel: a cell gives a CellOutcome, and the note of lost names folds over the journal.
lazy val harnessRepl = (project in file("modules/harness-repl"))
  .dependsOn(harnessGuest, harnessKernel)
  .settings(commonSettings)
  .settings(name := "dspy4s-harness-repl")
  .settings(
    libraryDependencies ++= Seq(
      "org.scala-lang" %% "scala3-repl" % scalaVersion.value,
      "org.scalameta"  %% "munit"       % munitVersion % Test
    ),
    // The checks of section 12 used the REPL of this nightly with no scaladoc, and the REPL does not need it.
    excludeDependencies += ExclusionRule("org.scala-lang", "scaladoc_3")
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
