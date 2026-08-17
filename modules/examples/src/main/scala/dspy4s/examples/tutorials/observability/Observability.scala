/** Tutorial: Debugging and Observability in DSPy
  *
  * Source: docs/docs/tutorials/observability/index.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/observability/index.md Status: translated.
  *
  * Python callbacks map to an explicit `ProgramObserver`. The observer receives the run-local event stream. A complete
  * journal is also available from `ProgramRunner.runJournaled`.
  */
package dspy4s.examples.tutorials.observability

import dspy4s.core.contracts.{DspyError, DynamicValues, TypeRef}
import dspy4s.examples.Demo
import dspy4s.programs.contracts.{Tool, ToolCallRequest}
import dspy4s.programs.*
import dspy4s.signatures.Signature
import zio.{Runtime, UIO, Unsafe, ZEnvironment, ZIO}
import zio.blocks.schema.Schema

final case class ObservableQuestion(question: String) derives Schema
final case class ObservableAnswer(answer: String) derives Schema
final case class ObservableExtractInput(question: String, trajectory: String) derives Schema

object Observability:

  // ── Snippet 1 — a ReAct agent over a retrieval tool ──
  // | def retrieve(query: str): """Retrieve top 3 relevant information from ColBert""" ...
  // | agent = dspy.ReAct("question -> answer", tools=[retrieve], max_iters=3)
  val retrieve: Tool = Tool.fromEither(
    "retrieve",
    "Retrieve the top relevant passages for a query.",
    Vector("query" -> TypeRef.string)
  )(args =>
    DynamicValues.requireString(args, "query", "retrieve")
      .map(query => DynamicValues.fromAny(List(s"(stubbed retrieval result for: $query)")))
  )

  private val generator = Program.lift[ReAct.StepInput[ObservableQuestion], ReAct.Step] { input =>
    if input.trajectory.isEmpty then
      val args = DynamicValues.recordFromEntries(Seq("query" -> DynamicValues.fromAny(input.input.question)))
      ReAct.Step("Retrieve supporting information.", ReAct.Action.Invoke(ToolCallRequest("retrieve", args)))
    else ReAct.Step("The retrieval result is sufficient.", ReAct.Action.Finish())
  }

  private val extractor = Program
    .predict(
      ParameterId("observability/answer"),
      Signature.derived[ObservableExtractInput, ObservableAnswer](
        "ObservableAnswer",
        "Answer the question from the trajectory."
      )
    )
    .contramap[ReAct.ExtractInput[ObservableQuestion]](input =>
      ObservableExtractInput(input.input.question, input.trajectory.mkString("\n"))
    )

  val agent = ReAct(generator, Program.invokeTool, extractor, maxIterations = 3)
    .observed("retrieval_agent", Signature.derived[ObservableQuestion, ObservableAnswer]("Observed").inputShape.encode)

  // ── Snippet 6 — a custom logging callback ──
  // | class AgentLoggingCallback(BaseCallback):
  // |     def on_module_end(self, call_id, outputs, exception): ...
  // --8<-- [start:callback]
  final class AgentLoggingObserver extends ProgramObserver:
    def onEvent(event: ProgramEvent): UIO[Unit] = ZIO.succeed(println(s"program event: $event"))
  // --8<-- [end:callback]

  // --8<-- [start:callback-run]
  def runWithLogging(question: String)(using predictionBackend: PredictionBackend): Either[DspyError, String] =
    val toolBackend: ToolBackend = new LiveToolBackend(Vector(retrieve))
    val environment = ZEnvironment[PredictionBackend](predictionBackend) ++ ZEnvironment[ToolBackend](toolBackend)
    val effect = ProgramRunner.runObserved(agent, ObservableQuestion(question), new AgentLoggingObserver)
      .provideEnvironment(environment)
      .map(_.output.answer)
      .either
    Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
  // --8<-- [end:callback-run]

  // `ProgramRunner.runJournaled` replaces global `inspect_history`. MLflow export is not built in; implement an
  // observer that sends `ProgramEvent` values to the required tracing system.

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.observability.observabilityMain"
@main def observabilityMain(): Unit =
  Demo.withLm {
    println("Answer: " + Observability.runWithLogging("Which baseball team does Shohei Ohtani play for?"))
  }
