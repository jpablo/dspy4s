/** Async DSPy Programming
  *
  * Source: docs/docs/tutorials/async/index.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/async/index.md Status: translated (the async
  * program path, snippets 1/4). Python's `await predict.acall(...)` becomes dspy4s's `Module.applyAsync` /
  * `ContextPropagation.future`, which run the (synchronous) program on a context-propagating `ExecutionContext` and
  * return a `scala.concurrent.Future`. The async-TOOL snippets (2/3 — `dspy.Tool(async_fn)`, `tool.acall`,
  * `allow_tool_async_sync_conversion`) are not portable: `ToolFunction.invoke` is synchronous and dspy4s has no async
  * tool path.
  *
  * Note `ContextPropagation.future` (not a bare `Future`): it captures the active `RuntimeContext` (LM, adapter,
  * callbacks) so the program still resolves its model off-thread — a plain `Future { ... }` would lose it.
  */
package dspy4s.examples.tutorials.async

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.core.runtime.ContextPropagation
import dspy4s.examples.Demo
import dspy4s.programs.*
import dspy4s.programs.contracts.ProgramCall
import dspy4s.typed.Signature

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

object Async:

  // ── Snippet 1 (lines 55–72) — run a single Predict asynchronously ──
  // | predict = dspy.Predict("question->answer")
  // | output = await predict.acall(question="why did a chicken cross the kitchen?")
  // --8<-- [start:ask-async]
  def askAsync(question: String)(using RuntimeContext, ExecutionContext): Future[Either[DspyError, String]] =
    ContextPropagation.future(
      Predict(Signature.fromString("question -> answer"))((question = question)).map(_.output.answer)
    )
  // --8<-- [end:ask-async]

  // ── Snippet 4 (lines 137–163) — a module whose `aforward` chains two predictors asynchronously ──
  // | class MyModule(dspy.Module):
  // |     self.predict1 = dspy.ChainOfThought("question->answer")
  // |     self.predict2 = dspy.ChainOfThought("answer->simplified_answer")
  // |     async def aforward(self, question):
  // |         answer = await self.predict1.acall(question=question)
  // |         return await self.predict2.acall(answer=answer)
  // --8<-- [start:simplifier-module]
  final class SimplifierModule:
    private val predict1 = ChainOfThought(Signature.fromString("question -> answer"))
    private val predict2 = ChainOfThought(Signature.fromString("answer -> simplified_answer"))
    private val pipeline = (predict1.mapOutput(result => (answer = result.answer)) >>> predict2)
      .mapOutput(_.simplified_answer)

    /** Sequential-but-asynchronous, like Python's `aforward`: `>>>` builds the typed two-stage program, which runs
      * inside one context-propagating off-thread computation and returns a `Future`.
      */
    def aforward(question: String)(using RuntimeContext, ExecutionContext): Future[Either[DspyError, String]] =
      ContextPropagation.future(pipeline(ProgramCall((question = question))).map(_.output))
  // --8<-- [end:simplifier-module]

  // ── Snippets 2 / 3 — async tools (`dspy.Tool(async_fn)`, `tool.acall`, async→sync conversion) ──
  // Not portable: `ToolFunction.invoke` is synchronous; dspy4s has no async tool path (so there is also no
  // `allow_tool_async_sync_conversion` knob). Tools run synchronously inside the (optionally async) program.

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.async.asyncMain"
@main def asyncMain(): Unit = Demo.withLm {
  given ExecutionContext = ExecutionContext.global
  val q                  = "Why did a chicken cross the kitchen?"
  println("Async predict: " + Await.result(Async.askAsync(q), 60.seconds))
  println("Async module:  " + Await.result(new Async.SimplifierModule().aforward(q), 60.seconds))
}
