/** DSPy Optimizers (formerly Teleprompters)
  *
  * Source: docs/docs/learn/optimization/optimizers.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/optimization/optimizers.md Status: translated
  * (BootstrapFewShotWithRandomSearch.compile, snippet 1; save/load, snippets 2/3).
  *
  * The functional API optimizes a `RecordProgram`. The program syntax stays immutable. The optimizer returns a new
  * value with updated immutable parameters.
  */
package dspy4s.examples.learn.optimization

import dspy4s.core.contracts.{DspyError, :=}
import dspy4s.core.data.Example
import dspy4s.evaluate.Metric
import dspy4s.evaluate.metrics.ExactMatch
import dspy4s.examples.Demo
import dspy4s.optimize.{
  BootstrapRandomSearch, BootstrapRandomSearchConfig, DemoCount, ProgramPersistence, SearchCandidateCount
}
import dspy4s.programs.{PredictionBackend, Program, RecordProgram}
import dspy4s.signatures.Signature
import zio.blocks.schema.Schema

final case class OptimizerQuestion(question: String) derives Schema
final case class OptimizerAnswer(answer: String) derives Schema

object Optimizers:

  val signature = Signature.derived[OptimizerQuestion, OptimizerAnswer](
    "OptimizerQA",
    "Answer the question briefly."
  )

  def student(): RecordProgram[OptimizerQuestion, OptimizerAnswer] =
    Program.predict(signature).fromRecords(signature.inputShape)

  // ── Snippet 1 (lines 95–104) ────────────────────
  // | config = dict(max_bootstrapped_demos=4, max_labeled_demos=4, num_candidate_programs=10, num_threads=4)
  // | teleprompter = BootstrapFewShotWithRandomSearch(metric=YOUR_METRIC_HERE, **config)
  // | optimized_program = teleprompter.compile(YOUR_PROGRAM_HERE, trainset=YOUR_TRAINSET_HERE)
  // --8<-- [start:optimize-bootstrap]
  def optimize(
      metric  : Metric,
      program : RecordProgram[OptimizerQuestion, OptimizerAnswer],
      trainset: Vector[Example]
  )(using PredictionBackend): Either[DspyError, RecordProgram[OptimizerQuestion, OptimizerAnswer]] =
    Demo.runEffect(BootstrapRandomSearch(
      student = program,
      trainset = trainset,
      config = BootstrapRandomSearchConfig(
        metric = metric,
        maxBootstrappedDemos = DemoCount(4),
        maxLabeledDemos = DemoCount(4),
        numCandidates = SearchCandidateCount(10)
      )
    )).map(_.bestProgram)
  // --8<-- [end:optimize-bootstrap]

  // ── Snippets 2 + 3 (lines 213–225) — save / load an optimized program ──
  // | optimized_program.save(YOUR_SAVE_PATH)
  // | loaded_program = YOUR_PROGRAM_CLASS(); loaded_program.load(path=YOUR_SAVE_PATH)
  // State is keyed by stable parameter IDs. Loading applies it to a fresh program with the same IDs.
  // --8<-- [start:save-load]
  def save(program: RecordProgram[OptimizerQuestion, OptimizerAnswer], path: String) =
    ProgramPersistence.save(program, path)

  def load(fresh: RecordProgram[OptimizerQuestion, OptimizerAnswer], path: String) =
    ProgramPersistence.load(fresh, path)
  // --8<-- [end:save-load]

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.learn.optimization.optimizersMain"
@main def optimizersMain(): Unit =
  Demo.withLm {
    val trainset = Vector(
      Example("question" := "What is 1+1?", "answer" := "2").withInputs(Set("question")),
      Example("question" := "What is 2+2?", "answer" := "4").withInputs(Set("question"))
    )
    val result = Optimizers.optimize(new ExactMatch(), Optimizers.student(), trainset)
    println("Optimized parameter IDs: " + result.map(_.program.parameters.all.map(_.id.value)))
  }
