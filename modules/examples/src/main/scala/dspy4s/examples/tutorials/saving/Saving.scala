/**
 * Tutorial: Saving and Loading your DSPy program
 *
 * Source:   docs/docs/tutorials/saving/index.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/saving/index.md
 * Status:   translated — program state `save` / `load` is now ported (PORT_GAPS G-4,
 *           `dspy4s.optimize.ProgramPersistence`). dspy4s serializes each leaf's writable `OptimizableParameters`
 *           (instructions + demos + module config) to clean JSON — the analogue of Python's `save(...,
 *           save_program=False)`. The GSM8K dataset (snippet 1) is swapped for a small hand-built trainset
 *           (no `dspy.datasets`, PORT_GAPS G-21).
 *
 *           Out of scope: the "whole-program" `save_program=True` directory form (snippets 6–8). That
 *           cloudpickles the program's *architecture/code* so it can be reloaded without re-declaring it.
 *           dspy4s programs are immutable Scala values — you recreate the program in code (snippet 4) and
 *           reload its STATE into it. There is no code/pickle serialization, hence no `.pkl` variant
 *           (snippets 3/5) and no `modules_to_serialize` (snippet 8); dspy4s has one clean JSON state format.
 */
package dspy4s.examples.tutorials.saving

import dspy4s.core.contracts.{:=, DspyError, RuntimeContext}
import dspy4s.core.data.Example
import dspy4s.evaluate.contracts.Metric
import dspy4s.optimize.{BootstrapFewShot, BootstrapFewShotConfig, DemoCount, ProgramPersistence, RoundCount}
import dspy4s.programs.DynamicPredict
import dspy4s.programs.predictors.OptimizableTraversal
import dspy4s.typed.Signature

import java.nio.file.Files

object Saving:

  /** A `question -> answer` predictor — the dspy4s analogue of `dspy.ChainOfThought("question -> answer")`.
    * This tutorial uses `DynamicPredict` for a compact runtime-layout example; typed `Predict` and
    * `ChainOfThought` are supported through the same `OptimizableTraversal` / `OptimizableParameters` contract. */
  // --8<-- [start:program]
  def program(): DynamicPredict =
    DynamicPredict(Signature.fromString("question -> answer").layout)
  // --8<-- [end:program]

  // ── Snippet 1 — compile a program with BootstrapFewShot ──
  // | gsm8k = GSM8K(); gsm8k_trainset = gsm8k.train[:10]
  // | dspy_program = dspy.ChainOfThought("question -> answer")
  // | optimizer = dspy.BootstrapFewShot(metric=gsm8k_metric, max_bootstrapped_demos=4, max_labeled_demos=4, max_rounds=5)
  // | compiled_dspy_program = optimizer.compile(dspy_program, trainset=gsm8k_trainset)
  // GSM8K is not ported (PORT_GAPS G-21) — bring your own `Example`s (see deep_dive/data_handling/LoadingCustomData).
  // BootstrapFewShot runs the program over an LM to collect demo traces, so this step needs `OPENAI_API_KEY`.
  // --8<-- [start:compile]
  def compile(metric: Metric, student: DynamicPredict, trainset: Vector[Example])(using RuntimeContext)
      : Either[DspyError, DynamicPredict] =
    new BootstrapFewShot[DynamicPredict](BootstrapFewShotConfig(
      metric = Some(metric),
      maxBootstrappedDemos = DemoCount(4),
      maxLabeledDemos = DemoCount(4),
      maxRounds = RoundCount(5)
    )).compile(student, trainset).map(_.bestProgram)
  // --8<-- [end:compile]

  // ── Snippets 2/3 — save the compiled program's state to disk ──
  // | compiled_dspy_program.save("./dspy_program/program.json", save_program=False)
  // | compiled_dspy_program.save("./dspy_program/program.pkl", save_program=False)   # .pkl variant: N/A in dspy4s
  // `ProgramPersistence.save` writes OptimizableParameters keyed by traversal ordinals (`predictor-0`, ...) as JSON.
  // --8<-- [start:save]
  def save(program: DynamicPredict, path: String): Either[DspyError, Unit] =
    ProgramPersistence.save(program, path)
  // --8<-- [end:save]

  // ── Snippets 4/5 — recreate the same program, then load the state back into it ──
  // | loaded_dspy_program = dspy.ChainOfThought("question -> answer")   # recreate the architecture
  // | loaded_dspy_program.load("./dspy_program/program.json")
  // | assert len(compiled_dspy_program.demos) == len(loaded_dspy_program.demos)
  // dspy4s `load` takes the freshly-recreated program and returns a NEW immutable value with the saved
  // instructions/demos/config written back. Architecture, names, runtime bindings, schemas, LMs, and tools stay fresh.
  // --8<-- [start:load]
  def load(fresh: DynamicPredict, path: String): Either[DspyError, DynamicPredict] =
    ProgramPersistence.load(fresh, path)
  // --8<-- [end:load]

  // ── Snippets 6/7/8 — the "whole program" form (`save_program=True`) + `dspy.load(dir)` + custom modules ──
  // Out of scope: that form cloudpickles the program's architecture/code into a directory so it can be reloaded
  // without re-declaring it. dspy4s programs are immutable Scala values — recreate the program in code
  // (snippet 4) and reload its STATE (above). No code/pickle serialization, hence no `modules_to_serialize`.
  // The former state entries containing a full `signature` layout are unsupported; regenerate those artifacts.

/** Demonstrates the save → recreate → load → compare round-trip OFFLINE (no LM): we hand-attach a couple of
  * demos (standing in for the output of `Saving.compile`, which needs an LM) so the persistence round-trip is
  * self-contained and assertable. Run with: sbt "examples/runMain dspy4s.examples.tutorials.saving.savingMain" */
@main def savingMain(): Unit =
  // A "compiled" program: the same predictor with a few demos attached (what BootstrapFewShot would produce).
  val demos = Vector(
    Example("question" := "What is 1+1?", "answer" := "2").withInputs(Set("question")),
    Example("question" := "What is 2+2?", "answer" := "4").withInputs(Set("question"))
  )
  val compiled = Saving.program().copy(demos = demos)

  val path = Files.createTempFile("dspy4s_program", ".json").toString
  val roundTrip =
    for
      _      <- Saving.save(compiled, path)
      loaded <- Saving.load(Saving.program(), path) // recreate the architecture, then load state into it
    yield loaded

  roundTrip match
    case Left(err) => sys.error(s"save/load failed: ${err.message}")
    case Right(loaded) =>
      val ps     = summon[OptimizableTraversal[DynamicPredict]]
      val before = ps.read(compiled).head.demos.size
      val after  = ps.read(loaded).head.demos.size
      println(s"saved program state to: $path")
      println(s"demos before save: $before, after load: $after")
      assert(before == after, "demo count must round-trip")
      println("✓ program state round-tripped (demos preserved)")
