/**
 * DSPy Optimizers (formerly Teleprompters)
 *
 * Source:   docs/docs/learn/optimization/optimizers.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/optimization/optimizers.md
 * Status:   translated (BootstrapFewShotWithRandomSearch.compile, snippet 1; save/load, snippets 2/3).
 *
 * dspy4s optimizers operate on the untyped `DynamicPredict` (which has a `Predictors` instance);
 * `compile(student, trainset)` returns an `OptimizationReport` whose `bestProgram` is the result.
 * Program state is persisted with `dspy4s.optimize.ProgramPersistence` (PORT_GAPS G-4).
 */
package dspy4s.examples.learn.optimization

import dspy4s.core.contracts.{:=, DspyError, Example, RuntimeContext}
import dspy4s.evaluate.contracts.Metric
import dspy4s.evaluate.metrics.ExactMatch
import dspy4s.examples.Demo
import dspy4s.optimize.{BootstrapFewShotWithRandomSearch, ProgramPersistence, RandomSearchConfig}
import dspy4s.programs.DynamicPredict
import dspy4s.typed.Signature

object Optimizers:

  // ── Snippet 1 (lines 95–104) ────────────────────
  // | config = dict(max_bootstrapped_demos=4, max_labeled_demos=4, num_candidate_programs=10, num_threads=4)
  // | teleprompter = BootstrapFewShotWithRandomSearch(metric=YOUR_METRIC_HERE, **config)
  // | optimized_program = teleprompter.compile(YOUR_PROGRAM_HERE, trainset=YOUR_TRAINSET_HERE)
  def optimize(
      metric: Metric,
      program: DynamicPredict,
      trainset: Vector[Example]
  )(using RuntimeContext): Either[DspyError, DynamicPredict] =
    val teleprompter = BootstrapFewShotWithRandomSearch[DynamicPredict](RandomSearchConfig(
      metric               = metric,
      maxBootstrappedDemos = 4,
      maxLabeledDemos      = 4,
      numCandidates        = 10,        // Python's num_candidate_programs
      numThreads           = Some(4)
    ))
    teleprompter.compile(program, trainset).map(_.bestProgram)

  // ── Snippets 2 + 3 (lines 213–225) — save / load an optimized program ──
  // | optimized_program.save(YOUR_SAVE_PATH)
  // | loaded_program = YOUR_PROGRAM_CLASS(); loaded_program.load(path=YOUR_SAVE_PATH)
  // Ported (PORT_GAPS G-4): `ProgramPersistence` writes/reads a program's learnable state (per-predictor
  // signature + demos + config) as JSON. Like Python, `load` takes a freshly-recreated program of the same
  // shape and returns it with the saved state written back. See tutorials/saving for the full treatment.
  def save(program: DynamicPredict, path: String): Either[DspyError, Unit] =
    ProgramPersistence.save(program, path)

  def load(fresh: DynamicPredict, path: String): Either[DspyError, DynamicPredict] =
    ProgramPersistence.load(fresh, path)

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.learn.optimization.optimizersMain"
// (Runs a small bootstrap+random-search over an LM — makes several LM calls.)
@main def optimizersMain(): Unit = Demo.withLm {
  val program  = DynamicPredict(layout = Signature.fromString("question -> answer").layout)
  val trainset = Vector(
    Example("question" := "What is 1+1?", "answer" := "2").withInputs(Set("question")),
    Example("question" := "What is 2+2?", "answer" := "4").withInputs(Set("question"))
  )
  println("Optimized program: " + Optimizers.optimize(new ExactMatch(), program, trainset).map(_.moduleName))
}
