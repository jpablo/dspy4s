/** Tracking DSPy Optimizers
  *
  * Source: docs/docs/tutorials/optimizer_tracking/index.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/optimizer_tracking/index.md Status: translated.
  *
  * Python uses MLflow autologging. The functional Scala version decorates the explicit `PredictionBackend`. This makes
  * tracking local to one optimization run and avoids global callbacks.
  */
package dspy4s.examples.tutorials.optimizer_tracking

import dspy4s.core.contracts.{DspyError, :=}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.evaluate.Metric
import dspy4s.evaluate.metrics.ExactMatch
import dspy4s.examples.Demo
import dspy4s.optimize.{CandidateCount, DemoCount, MIPROv2, MIPROv2Config, ProgramPersistence, TrialCount}
import dspy4s.programs.{PredictionBackend, PredictionChunk, PredictionRequest, Program, RecordProgram}
import dspy4s.signatures.Signature
import zio.{IO, UIO}
import zio.blocks.schema.Schema

import java.util.concurrent.atomic.AtomicInteger

final case class TrackingQuestion(question: String) derives Schema
final case class TrackingAnswer(answer: String) derives Schema

object OptimizerTracking:

  // ── Snippet 1 — enable tracking ──
  // | mlflow.dspy.autolog(log_compiles=True, log_evals=True, log_traces_from_compile=True)
  // | mlflow.set_tracking_uri("http://localhost:5000"); mlflow.set_experiment("DSPy-Optimization")
  // --8<-- [start:tracking-callback]
  final class CountingBackend(delegate: PredictionBackend) extends PredictionBackend:
    private val count = new AtomicInteger(0)
    def calls: Int    = count.get()

    def generate(request: PredictionRequest): IO[DspyError, RawPrediction] =
      val _ = count.incrementAndGet()
      delegate.generate(request)

    override def generateStreaming(
        request: PredictionRequest,
        emit   : PredictionChunk => UIO[Unit]
    ): IO[DspyError, RawPrediction] =
      val _ = count.incrementAndGet()
      delegate.generateStreaming(request, emit)
  // --8<-- [end:tracking-callback]

  val signature      = Signature.derived[TrackingQuestion, TrackingAnswer]("TrackingQA", "Answer the math question.")
  val metric: Metric = new ExactMatch("answer")

  def example(question: String, answer: String): Example =
    Example("question" := question, "answer" := answer).withInputs(Set("question"))

  val trainset: Vector[Example] = Vector(
    example("What is 2+3?", "5"),
    example("What is 10-4?", "6"),
    example("What is 6*7?", "42"),
    example("What is 20/5?", "4")
  )

  def student(): RecordProgram[TrackingQuestion, TrackingAnswer] =
    Program.predict(signature).fromRecords(signature.inputShape)

  // ── Snippet 2 — run MIPROv2 with tracking ──
  // | program = dspy.ChainOfThought("question -> answer")
  // | teleprompter = dspy.teleprompt.MIPROv2(metric=gsm8k_metric, auto="light")
  // | optimized_program = teleprompter.compile(program, trainset=trainset)
  // --8<-- [start:optimize-with-tracking]
  def optimizeWithTracking(program: RecordProgram[TrackingQuestion, TrackingAnswer])(using
      backend: PredictionBackend
  ): Either[DspyError, (RecordProgram[TrackingQuestion, TrackingAnswer], Int)] =
    val tracked  = new CountingBackend(backend)
    val proposer = Program.lift[MIPROv2.ProposalInput, MIPROv2.Proposal] { input =>
      val current = input.currentInstruction.getOrElse("Answer the question.")
      MIPROv2.Proposal(s"$current Be concise and verify arithmetic.")
    }
    val effect = MIPROv2(
      student = program,
      trainset = trainset,
      proposer = proposer,
      valset = Some(trainset),
      config = MIPROv2Config(
        metric = metric,
        numCandidates = CandidateCount(3),
        numTrials = TrialCount(4),
        maxBootstrappedDemos = DemoCount(2),
        maxLabeledDemos = DemoCount(2)
      )
    )
    Demo.runEffect(effect)(using tracked).map(report => report.bestProgram -> tracked.calls)
  // --8<-- [end:optimize-with-tracking]

  // ── Snippet 3 — reload optimized state ──
  // | program.load(model_path)
  // --8<-- [start:reload]
  def reload(fresh: RecordProgram[TrackingQuestion, TrackingAnswer], path: String) =
    ProgramPersistence.load(fresh, path)
  // --8<-- [end:reload]

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.optimizer_tracking.optimizerTrackingMain"
@main def optimizerTrackingMain(): Unit =
  Demo.withLm {
    OptimizerTracking.optimizeWithTracking(OptimizerTracking.student()) match
      case Right((optimized, calls)) =>
        println(s"optimized parameters: ${optimized.program.parameters.all.map(_.id.value)}")
        println(s"tracked $calls LM calls during optimization")
      case Left(error) => println(s"optimization failed: ${error.message}")
  }
