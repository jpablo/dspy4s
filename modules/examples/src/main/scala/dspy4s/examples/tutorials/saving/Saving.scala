/** Tutorial: Saving and Loading your DSPy program
  *
  * Source: docs/docs/tutorials/saving/index.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/saving/index.md Status: translated.
  *
  * `ProgramPersistence` saves optimizer-writable state: instructions, demonstrations, and configuration. The Scala
  * program structure stays in code. Loading returns a new immutable program value.
  */
package dspy4s.examples.tutorials.saving

import dspy4s.core.contracts.{DspyError, :=}
import dspy4s.core.data.Example
import dspy4s.evaluate.Metric
import dspy4s.examples.Demo
import dspy4s.optimize.{BootstrapFewShot, BootstrapFewShotConfig, DemoCount, ProgramPersistence, RoundCount}
import dspy4s.programs.{ParameterId, PredictionBackend, Program, RecordProgram}
import dspy4s.signatures.Signature
import zio.blocks.schema.Schema

import java.nio.file.Files

final case class SavingQuestion(question: String) derives Schema
final case class SavingAnswer(answer: String) derives Schema

object Saving:

  val answerId = ParameterId("saving/answer")
  val signature = Signature.derived[SavingQuestion, SavingAnswer]("SavingQA", "Answer the question.")

  // --8<-- [start:program]
  def program(): RecordProgram[SavingQuestion, SavingAnswer] =
    Program.predict(answerId, signature).fromRecords(signature.inputShape)
  // --8<-- [end:program]

  // ── Snippet 1 — compile a program with BootstrapFewShot ──
  // | gsm8k = GSM8K(); gsm8k_trainset = gsm8k.train[:10]
  // | dspy_program = dspy.ChainOfThought("question -> answer")
  // | optimizer = dspy.BootstrapFewShot(metric=gsm8k_metric, max_bootstrapped_demos=4, max_labeled_demos=4, max_rounds=5)
  // | compiled_dspy_program = optimizer.compile(dspy_program, trainset=gsm8k_trainset)
  // --8<-- [start:compile]
  def compile(
      metric  : Metric,
      student : RecordProgram[SavingQuestion, SavingAnswer],
      trainset: Vector[Example]
  )(using PredictionBackend): Either[DspyError, RecordProgram[SavingQuestion, SavingAnswer]] =
    Demo.runEffect(BootstrapFewShot(
      student,
      trainset,
      config = BootstrapFewShotConfig(
        metric = Some(metric),
        maxBootstrappedDemos = DemoCount(4),
        maxLabeledDemos = DemoCount(4),
        maxRounds = RoundCount(5)
      )
    )).map(_.bestProgram)
  // --8<-- [end:compile]

  // ── Snippets 2/3 — save state ──
  // | compiled_dspy_program.save("./dspy_program/program.json", save_program=False)
  // | compiled_dspy_program.save("./dspy_program/program.pkl", save_program=False)
  // dspy4s uses one JSON state format. It does not use pickle.
  // --8<-- [start:save]
  def save(program: RecordProgram[SavingQuestion, SavingAnswer], path: String): Either[DspyError, Unit] =
    ProgramPersistence.save(program, path)
  // --8<-- [end:save]

  // ── Snippets 4/5 — recreate the structure and load state ──
  // | loaded_dspy_program = dspy.ChainOfThought("question -> answer")
  // | loaded_dspy_program.load("./dspy_program/program.json")
  // --8<-- [start:load]
  def load(
      fresh: RecordProgram[SavingQuestion, SavingAnswer],
      path : String
  ): Either[DspyError, RecordProgram[SavingQuestion, SavingAnswer]] =
    ProgramPersistence.load(fresh, path)
  // --8<-- [end:load]

  // Python's `save_program=True` serializes architecture and code. dspy4s does not do this. Recreate the immutable
  // program structure in Scala, then load its parameter state.

// Offline round-trip. Run with: sbt "examples/runMain dspy4s.examples.tutorials.saving.savingMain"
@main def savingMain(): Unit =
  val demos = Vector(
    Example("question" := "What is 1+1?", "answer" := "2").withInputs(Set("question")),
    Example("question" := "What is 2+2?", "answer" := "4").withInputs(Set("question"))
  )
  val compiled = Saving.program().modifyParameter(Saving.answerId)(_.copy(demos = demos))
    .fold(error => sys.error(error.message), identity)

  val path = Files.createTempFile("dspy4s_program", ".json").toString
  val roundTrip = for
    _      <- Saving.save(compiled, path)
    loaded <- Saving.load(Saving.program(), path)
  yield loaded

  roundTrip match
    case Left(error) => sys.error(s"save/load failed: ${error.message}")
    case Right(loaded) =>
      val before = compiled.program.parameters.get(Saving.answerId).fold(0)(_.demos.size)
      val after  = loaded.program.parameters.get(Saving.answerId).fold(0)(_.demos.size)
      assert(before == after, "demo count must round-trip")
      println(s"saved program state to: $path")
      println(s"demos before save: $before, after load: $after")
