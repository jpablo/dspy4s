/** DSPy Optimizers (formerly Teleprompters)
 *
  * Source: docs/docs/learn/optimization/optimizers.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/optimization/optimizers.md Status: translated
  * (BootstrapFewShotWithRandomSearch.compile, snippet 1; save/load, snippets 2/3).
 *
  * This translation states the optimizer generically over the two capabilities every student needs
  * (`PredictorTraversal` + `ProgramRunner`); the main below drives it with a runtime-string student built through
  * `DynamicSignature` (parse once, mint fresh types, build a typed `Predict` over them). `compile(student,
  * trainset)` returns an `OptimizationReport` whose `bestProgram` is the result. Program state is persisted with
 * `dspy4s.optimize.ProgramPersistence` (PORT_GAPS G-4).
 */
package dspy4s.examples.learn.optimization

import dspy4s.core.contracts.{:=, DspyError, RuntimeContext, ThreadCount}
import dspy4s.core.data.Example
import dspy4s.evaluate.contracts.Metric
import dspy4s.evaluate.metrics.ExactMatch
import dspy4s.examples.Demo
import dspy4s.optimize.{
  BootstrapFewShotWithRandomSearch,
  DemoCount,
  ProgramPersistence,
  RandomSearchConfig,
  SearchCandidateCount
}
import dspy4s.programs.{DynamicPredict, DynamicSignature, ProgramRunner}
import dspy4s.programs.predictors.PredictorTraversal

object Optimizers:

  // ── Snippet 1 (lines 95–104) ────────────────────
  // | config = dict(max_bootstrapped_demos=4, max_labeled_demos=4, num_candidate_programs=10, num_threads=4)
  // | teleprompter = BootstrapFewShotWithRandomSearch(metric=YOUR_METRIC_HERE, **config)
  // | optimized_program = teleprompter.compile(YOUR_PROGRAM_HERE, trainset=YOUR_TRAINSET_HERE)
  // --8<-- [start:optimize-bootstrap]
  def optimize[P: {PredictorTraversal, ProgramRunner}](
      metric: Metric,
      program: P,
      trainset: Vector[Example]
  )(using RuntimeContext): Either[DspyError, P] =
    val teleprompter = BootstrapFewShotWithRandomSearch[P](RandomSearchConfig(
      metric               = metric,
      maxBootstrappedDemos = DemoCount(4),
      maxLabeledDemos      = DemoCount(4),
      numCandidates        = SearchCandidateCount(10),
      numThreads           = Some(ThreadCount(4))
    ))
    teleprompter.compile(program, trainset).map(_.bestProgram)
  // --8<-- [end:optimize-bootstrap]

  // ── Snippets 2 + 3 (lines 213–225) — save / load an optimized program ──
  // | optimized_program.save(YOUR_SAVE_PATH)
  // | loaded_program = YOUR_PROGRAM_CLASS(); loaded_program.load(path=YOUR_SAVE_PATH)
  // Ported (PORT_GAPS G-4): `ProgramPersistence` writes/reads each leaf's `PredictorState`
  // (instructions + demos + module config) as JSON. `load` applies it to a fresh program with the same predictor
  // traversal/order while preserving that program's metadata and execution resources. See tutorials/saving.
  // --8<-- [start:save-load]
  def save(program: DynamicPredict, path: String): Either[DspyError, Unit] =
    ProgramPersistence.save(program, path)

  def load(fresh: DynamicPredict, path: String): Either[DspyError, DynamicPredict] =
    ProgramPersistence.load(fresh, path)
  // --8<-- [end:save-load]

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.learn.optimization.optimizersMain"
// (Runs a small bootstrap+random-search over an LM — makes several LM calls.)
@main def optimizersMain(): Unit = Demo.withLm {
  // The runtime-string path: parse once (minting fresh input/output types with their codec), then build a
  // typed Predict over the bundle. Optimizable through the same capabilities as any typed program.
  val signature = DynamicSignature.parse("question -> answer")
    .fold(error => throw new IllegalArgumentException(error.message), identity)
  val program  = signature.predict()
  val trainset = Vector(
    Example("question" := "What is 1+1?", "answer" := "2").withInputs(Set("question")),
    Example("question" := "What is 2+2?", "answer" := "4").withInputs(Set("question"))
  )
  println("Optimized program: " + Optimizers.optimize(new ExactMatch(), program, trainset).map(_.moduleName))
}
