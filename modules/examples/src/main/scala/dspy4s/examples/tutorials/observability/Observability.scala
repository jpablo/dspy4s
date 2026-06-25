/**
 * Tutorial: Debugging and Observability in DSPy
 *
 * Source:   docs/docs/tutorials/observability/index.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/observability/index.md
 * Status:   translated (the ReAct agent + the custom-callback observability hook, snippets 1/2/6). The
 *           `inspect_history` snippet (3) is demonstrated in learn/programming/Adapters (dspy4s exposes
 *           `RuntimeEnvironment.inspectHistory`); here the richer `CallbackHandler` stream is the focus.
 *           Blocked bits: MLflow autolog (snippets 4/5 — no MLflow integration), and the retrieval backends
 *           (ColBERTv2 / Tavily) which have no dspy4s equivalent, so the `retrieve` tool returns a static stub.
 *
 * Python's `from dspy.utils.callback import BaseCallback` + `on_module_end(call_id, outputs, exception)`
 * maps onto dspy4s's `CallbackHandler.onEvent`, which receives a sealed `CallbackEvent` stream;
 * `on_module_end` is the `ModuleEndEvent` case. Install handlers with `RuntimeEnvironment.withCallbacks`
 * (the analogue of `dspy.configure(callbacks=[...])`).
 */
package dspy4s.examples.tutorials.observability

import dspy4s.core.contracts.{DspyError, ModuleEndEvent, RuntimeContext}
import dspy4s.core.contracts.{CallbackEvent, CallbackHandler}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.examples.Demo
import dspy4s.programs.ReAct
import dspy4s.programs.contracts.{ToolFunction, description}
import dspy4s.typed.Signature

object Observability:

  // ── Snippet 1 (lines 15–33) — a ReAct agent over a retrieval tool ──
  // | def retrieve(query: str): """Retrieve top 3 relevant information from ColBert""" ...
  // | agent = dspy.ReAct("question -> answer", tools=[retrieve], max_iters=3)
  // ColBERTv2 (and Tavily, snippet 5) have no dspy4s equivalent, so `retrieve` returns a static stub.
  @description("Retrieve the top relevant passages for a query.")
  def retrieve(query: String): List[String] =
    List(s"(stubbed retrieval result for: $query)")

  def agent = ReAct(
    baseSignature = Signature.fromString("question -> answer"),
    tools         = Vector(ToolFunction.fromMethod(retrieve)),
    maxIterations = 3
  )

  // ── Snippet 6 (lines 211–231) — a custom logging callback ──
  // | class AgentLoggingCallback(BaseCallback):
  // |     def on_module_end(self, call_id, outputs, exception):
  // |         step = "Reasoning" if self._is_reasoning_output(outputs) else "Acting"
  // |         print(f"== {step} Step ==="); for k, v in outputs.items(): print(f"  {k}: {v}")
  // dspy4s delivers one typed `CallbackEvent` stream; `on_module_end` is the `ModuleEndEvent` case. (dspy4s's
  // output is the program's typed result rather than a string-keyed dict, so the Reasoning/Acting key-prefix
  // heuristic isn't 1:1 — we log the module name + outcome instead.)
  // --8<-- [start:callback]
  final class AgentLoggingCallback extends CallbackHandler:
    override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
      event match
        case e: ModuleEndEvent =>
          val outcome = e.output.fold(err => s"error: ${err.message}", out => out.toString)
          println(s"== ${e.moduleName} step ended ==\n  $outcome\n")
        case _ => () // other scopes (LM / adapter / tool start+end) are ignored by this handler
  // --8<-- [end:callback]

  /** Run the agent with the logging callback installed — the analogue of
    * `dspy.configure(callbacks=[AgentLoggingCallback()])` followed by `agent(question=...)`. */
  // --8<-- [start:callback-run]
  def runWithLogging(question: String)(using ctx: RuntimeContext): Either[DspyError, String] =
    RuntimeEnvironment.withCallbacks(ctx.callbacks :+ new AgentLoggingCallback) {
      given RuntimeContext = RuntimeEnvironment.current
      agent.apply((question = question)).map(_.output.answer)
    }
  // --8<-- [end:callback-run]

  // ── Snippet 3 — `dspy.inspect_history(n=5)` ──
  // dspy4s's analogue is `RuntimeEnvironment.inspectHistory(n)` / `printHistory(n)`, reading the per-thread
  // `RuntimeContext.history` (populated by a `ManagedLanguageModel`) — see learn/programming/Adapters. The
  // `CallbackHandler` (above) is the richer observability seam, and the one this tutorial focuses on.
  //
  // ── Snippets 4 / 5 — MLflow autolog (`mlflow.dspy.autolog()`, tracking server, Tavily search) ──
  // Not portable: dspy4s has no MLflow integration. The callback stream is the observability seam.

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.observability.observabilityMain"
@main def observabilityMain(): Unit = Demo.withLm {
  println("Answer: " + Observability.runWithLogging("Which baseball team does Shohei Ohtani play for?"))
}
