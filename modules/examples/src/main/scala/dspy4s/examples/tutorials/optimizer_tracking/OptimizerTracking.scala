/**
 * Tracking DSPy Optimizers
 *
 * Source:   docs/docs/tutorials/optimizer_tracking/index.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/optimizer_tracking/index.md
 * Status:   translated (the optimizer run + its observability). MLflow autolog (snippet 1) has no dspy4s
 *           equivalent; its role — observing the optimization process — maps onto a `CallbackHandler`
 *           registered in the `RuntimeContext` (the general dspy4s observability seam; see
 *           tutorials/observability). MIPROv2 (snippet 2) is ported (`dspy4s.optimize.MIPROv2`); the GSM8K
 *           dataset is swapped for a small hand-built trainset (no `dspy.datasets`, PORT_GAPS G-21). The
 *           MLflow artifact reload (snippet 3) maps onto `ProgramPersistence.load` (PORT_GAPS G-4).
 *
 *           Delta from Python: dspy4s MIPROv2 takes explicit knobs (no `auto="light"` run mode), and dspy4s
 *           has no experiment-tracking server — the callback stream is where you wire logging / metrics export.
 */
package dspy4s.examples.tutorials.optimizer_tracking

import dspy4s.core.contracts.{:=, CallbackEvent, CallbackHandler, DspyError, Example, LmEndEvent, RuntimeContext}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.evaluate.contracts.Metric
import dspy4s.evaluate.metrics.ExactMatch
import dspy4s.examples.Demo
import dspy4s.optimize.{MIPROv2, MIPROv2Config, ProgramPersistence}
import dspy4s.programs.DynamicPredict
import dspy4s.typed.Signature

import java.util.concurrent.atomic.AtomicInteger

object OptimizerTracking:

  // ── Snippet 1 — enable tracking ──
  // | mlflow.dspy.autolog(log_compiles=True, log_evals=True, log_traces_from_compile=True)
  // | mlflow.set_tracking_uri("http://localhost:5000"); mlflow.set_experiment("DSPy-Optimization")
  // dspy4s has no MLflow integration; the observability seam is a `CallbackHandler` in the RuntimeContext (the
  // analogue of `dspy.configure(callbacks=[...])`). This one counts LM calls during the otherwise-silent
  // optimizer run — swap it for a logger / tracer / metrics exporter to "track" however you need.
  final class CompileTrackingCallback extends CallbackHandler:
    private val lmCalls = new AtomicInteger(0)
    def calls: Int = lmCalls.get()
    override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = event match
      case _: LmEndEvent => val _ = lmCalls.incrementAndGet()
      case _             => ()

  val metric: Metric = new ExactMatch(answerField = "answer")

  def example(question: String, answer: String): Example =
    Example("question" := question, "answer" := answer).withInputs(Set("question"))

  // A tiny hand-built trainset (GSM8K is not ported, PORT_GAPS G-21).
  val trainset: Vector[Example] = Vector(
    example("What is 2+3?", "5"),
    example("What is 10-4?", "6"),
    example("What is 6*7?", "42"),
    example("What is 20/5?", "4")
  )

  def student(): DynamicPredict =
    DynamicPredict(Signature.fromString("question -> answer").layout)

  // ── Snippet 2 — run the optimizer with tracking ──
  // | program = dspy.ChainOfThought("question -> answer")
  // | teleprompter = dspy.teleprompt.MIPROv2(metric=gsm8k_metric, auto="light")
  // | optimized_program = teleprompter.compile(program, trainset=trainset)
  // The tracking callback is installed in the RuntimeContext, so it observes every LM call the optimizer makes.
  def optimizeWithTracking(student: DynamicPredict)(using ctx: RuntimeContext)
      : Either[DspyError, (DynamicPredict, Int)] =
    val tracker = new CompileTrackingCallback
    RuntimeEnvironment.withCallbacks(ctx.callbacks :+ tracker) {
      given RuntimeContext = RuntimeEnvironment.current
      new MIPROv2[DynamicPredict](MIPROv2Config(
        metric = metric, numCandidates = 3, numTrials = 4, maxBootstrappedDemos = 2, maxLabeledDemos = 2
      ))
        .compile(student, trainset, teacher = Some(student), valset = Some(trainset))
        .map(report => (report.bestProgram, tracker.calls))
    }

  // ── Snippet 3 — reload the optimized program ──
  // | model_path = mlflow.artifacts.download_artifacts("mlflow-artifacts:/path/to/best_model.json")
  // | program.load(model_path)
  // dspy4s has no artifact store; persist the optimized state with `ProgramPersistence.save` and reload it with
  // `ProgramPersistence.load` (PORT_GAPS G-4) — recreate the program, then load the state back into it.
  def reload(fresh: DynamicPredict, path: String): Either[DspyError, DynamicPredict] =
    ProgramPersistence.load(fresh, path)

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.optimizer_tracking.optimizerTrackingMain"
@main def optimizerTrackingMain(): Unit = Demo.withLm {
  OptimizerTracking.optimizeWithTracking(OptimizerTracking.student()) match
    case Right((optimized, calls)) =>
      println(s"optimized program: ${optimized.moduleName}")
      println(s"tracked $calls LM calls during optimization")
    case Left(err) => println(s"optimization failed: ${err.message}")
}
