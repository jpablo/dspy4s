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
lazy val jlineVersion           = "4.4.6"  // the version of the REPL of the nightly, so the class path has one JLine
lazy val slf4jVersion           = "2.0.17" // the slf4j-nop binding of an application: the examples and the CLI

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
    harnessRepl,
    harnessMcp,
    harnessSandbox,
    harness,
    harnessHost,
    harnessCli
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
      "io.github.iltotore" %% "iron"             % ironVersion,
      "org.scalameta"      %% "munit"            % munitVersion           % Test,
      "org.scalameta"      %% "munit-scalacheck" % munitScalacheckVersion % Test,
      "com.lihaoyi"        %% "ujson"            % ujsonVersion,
      "io.github.cdimascio" % "dotenv-java"      % dotenvVersion          % Test
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
    libraryDependencies += "org.slf4j" % "slf4j-nop" % slf4jVersion % Runtime,
    // The MCP examples start their servers with the class path of their JVM. The class path of the sbt JVM is not the
    // class path of the examples, so `run` and `runMain` fork. The output of the fork has no sbt prefix.
    Compile / run / fork           := true,
    Compile / run / outputStrategy := Some(StdoutOutput),
    // The MCP tests start the servers of the examples in a child JVM with the class path of the tests.
    Test / fork := true,
    Test / javaOptions += "-Dfile.encoding=UTF-8"
  )

// The code harness (docs/code-harness/CODE_HARNESS_DESIGN.md, 10.5). The modules hold the phases of section 15, with the
// laws of each as tests. docs/code-harness/CODE_HARNESS_BLUEPRINT.html shows the state of each part of the plan.

// The guest API: what model code calls, and the modes that limit it (5.1, 5.2).
lazy val harnessGuest = (project in file("modules/harness-guest"))
  .settings(commonSettings)
  .settings(name := "dspy4s-harness-guest")
  .settings(
    // The API reference (5.1) comes from the source text of the guest API. `ApiReference` of harness-repl keeps the
    // parts that compile in a mode, and the harness and the MCP server give it.
    Compile / resourceGenerators += Def.task {
      val sources = (Compile / scalaSource).value / "dspy4s" / "harness" / "guest"
      val target  = (Compile / resourceManaged).value / "dspy4s" / "harness" / "guest"
      Seq("Guest.scala", "SessionTypes.scala").map { name =>
        val copy = target / s"$name.txt"
        IO.copyFile(sources / name, copy)
        copy
      }
    }.taskValue,
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
    excludeDependencies += ExclusionRule("org.scala-lang", "scaladoc_3"),
    // The heap guard is one for the JVM, so its test must not stop the cells of another suite. The tests also fork:
    // in the JVM of sbt, the suites of other modules fill the heap, and the guard then stops a cell of these laws.
    Test / fork              := true,
    Test / parallelExecution := false
  )

// The early MCP server (6): the REPL and the guest API for another agent, before the harness has its own loop. It takes
// the secret paths and names of the sandbox (5.6), so a cell cannot read them.
lazy val harnessMcp = (project in file("modules/harness-mcp"))
  .dependsOn(harnessRepl, harnessSandbox)
  .settings(commonSettings)
  .settings(name := "dspy4s-harness-mcp")
  .settings(
    libraryDependencies ++= Seq(
      "io.modelcontextprotocol.sdk" % "mcp"   % mcpSdkVersion,
      "org.scalameta"              %% "munit" % munitVersion % Test
    ),
    excludeDependencies += ExclusionRule("org.scala-lang", "scaladoc_3"),
    // The end-to-end test starts the server in a child JVM with the class path of the tests.
    Test / fork := true,
    Test / javaOptions += "-Dfile.encoding=UTF-8"
  )

// The sandbox (5.4, 5.6): the profile of a child process from the mode, Seatbelt and bubblewrap, the proxy of the host
// list, the allowlist of variables, and the handler of Shell. exec runs only in a sandbox.
lazy val harnessSandbox = (project in file("modules/harness-sandbox"))
  .dependsOn(harnessKernel)
  .settings(commonSettings)
  .settings(name := "dspy4s-harness-sandbox")
  .settings(
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit"            % munitVersion           % Test,
      "org.scalameta" %% "munit-scalacheck" % munitScalacheckVersion % Test
    ),
    // The escape tests look for a variable of the test JVM from a child process. The value is not a real secret.
    Test / fork              := true,
    Test / envVars           := Map("DSPY4S_FAKE_SECRET_TOKEN" -> "fake-value-for-the-escape-tests"),
    Test / parallelExecution := false
  )

// One task (9, 4.6): Harness, drive, the window strategy, the handlers of a call and the mask of secrets. A Harness is
// a dspy4s Module, so the optimizers take it. The tests use GEPA to check that an optimizer takes a harness.
lazy val harness = (project in file("modules/harness"))
  .dependsOn(harnessRepl, harnessSandbox, programs, adapters, streaming, gepa % "test->compile")
  .settings(commonSettings)
  .settings(name := "dspy4s-harness")
  .settings(
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit"            % munitVersion           % Test,
      "org.scalameta" %% "munit-scalacheck" % munitScalacheckVersion % Test
    ),
    excludeDependencies += ExclusionRule("org.scala-lang", "scaladoc_3"),
    // A test of a harness runs a REPL and a sandbox, and the heap guard of the REPL is one for the JVM.
    Test / fork              := true,
    Test / parallelExecution := false
  )

// The session host (10.1 to 10.3): one writer for each session, a SQLite file for each journal, the feeds, the timers,
// the catalog, the path of a hard stop and the jobs. A work session runs its turns with the parts of `harness`.
lazy val harnessHost = (project in file("modules/harness-host"))
  // The tests of the host use the scripted effects, gates and models of the tests of `harness`.
  .dependsOn(harness % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(name := "dspy4s-harness-host")
  .settings(
    libraryDependencies ++= Seq(
      "org.xerial"     % "sqlite-jdbc"      % "3.53.4.0",
      "org.scalameta" %% "munit"            % munitVersion           % Test,
      "org.scalameta" %% "munit-scalacheck" % munitScalacheckVersion % Test
    ),
    excludeDependencies += ExclusionRule("org.scala-lang", "scaladoc_3"),
    // A host runs REPLs and a sandbox, and the crash tests start a host in a child JVM with the class path of the tests.
    Test / fork              := true,
    Test / parallelExecution := false
  )

// The terminal client of a work session (phase 7). It runs on JDK 21, as the other modules (choice 8).
lazy val harnessCli = (project in file("modules/harness-cli"))
  .dependsOn(harnessHost % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(name := "dspy4s-harness-cli")
  .settings(
    libraryDependencies ++= Seq(
      // The line editor of the client in a terminal: line edit, a history and Ctrl-C.
      "org.jline"      % "jline-reader"       % jlineVersion,
      "org.jline"      % "jline-terminal"     % jlineVersion,
      "org.jline"      % "jline-terminal-jni" % jlineVersion,
      "org.scalameta" %% "munit"              % munitVersion % Test
    ),
    // sqlite-jdbc logs through SLF4J when it finds SLF4J on the class path. With no binding, each start of the CLI
    // prints three lines of SLF4J on stderr.
    libraryDependencies += "org.slf4j" % "slf4j-nop" % slf4jVersion % Runtime,
    excludeDependencies += ExclusionRule("org.scala-lang", "scaladoc_3"),
    Compile / run / fork         := true,
    Compile / run / connectInput := true,
    // The check of the start time starts the client in a child JVM with the class path of the tests.
    Test / fork := true
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
