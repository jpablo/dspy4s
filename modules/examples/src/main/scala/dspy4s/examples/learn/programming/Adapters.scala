/**
 * Understanding DSPy Adapters
 *
 * Source:   docs/docs/learn/programming/adapters.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/programming/adapters.md
 * Status:   translated (snippets 1–6, incl. `inspect_history`). dspy4s's analogue of
 *           `dspy.inspect_history(n)` is `RuntimeEnvironment.inspectHistory(n)` / `printHistory(n)`, which
 *           renders the per-thread `RuntimeContext.history`. There is no global per-LM buffer: history is
 *           populated by a `ManagedLanguageModel` (per-LM composition), so wrap the ambient LM in one to
 *           record calls (see `askThenInspect`). Everything else (the Predict calls, the explicit
 *           ChatAdapter/JSONAdapter selection, the `adapter.format(...)` / system-message inspection) ports directly.
 *
 * Python's `adapter.format(signature, demos, inputs)` becomes `adapter.format(AdapterInvocation(layout,
 * demos, inputs, request))`, which returns a `FormattedPrompt`; the "system message" is just its first
 * message. The adapter is selected via the ambient `RuntimeContext` (here swapped with `withAdapter`),
 * mirroring `dspy.configure(adapter=...)`. Pydantic `BaseModel` outputs become `Schema`-deriving case classes.
 */
package dspy4s.examples.learn.programming

import dspy4s.adapters.{ChatAdapter, JSONAdapter}
import dspy4s.adapters.contracts.{Adapter, AdapterInvocation}
import dspy4s.core.contracts.{DspyError, DynamicValues, Example, RuntimeContext, RuntimeError, :=}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.examples.Demo
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmRequest}
import dspy4s.lm.runtime.ManagedLanguageModel
import dspy4s.programs.Predict
import dspy4s.typed.{InputField, OutputField, Signature, Spec}
import zio.blocks.schema.{DynamicValue, Schema}

// ── Snippet 5/6 — a Pydantic model + a multi-field signature with a structured-list output ──
// | class ScienceNews(pydantic.BaseModel): text: str; scientists_involved: list[str]
case class ScienceNews(text: String, scientists_involved: List[String]) derives Schema

// | class NewsQA(dspy.Signature): """Get news about the given science field"""
trait NewsQA extends Spec:
  def science_field:  InputField[String]
  def year:           InputField[Int]
  def num_of_outputs: InputField[Int]
  def news: OutputField[List[ScienceNews]]

object Adapters:

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)

  // ── Snippets 1 & 2 — a basic Predict (default adapter, then an explicit ChatAdapter) ──
  // | predict = dspy.Predict("question -> answer"); result = predict(question="What is the capital of France?")
  // The adapter is the one in the ambient RuntimeContext; `dspy.ChatAdapter()` is the default (Demo installs it).
  def ask(question: String)(using RuntimeContext): Either[DspyError, String] =
    Predict(Signature.fromString("question -> answer")).apply((question = question)).map(_.output.answer)

  // ── Snippet 3 — inspect what an adapter sends to the LM ──
  // | signature = dspy.Signature("question -> answer"); inputs = {...}; demos = [{...}]
  // | adapter = dspy.ChatAdapter(); print(adapter.format(signature, demos, inputs))
  def formattedPrompt(using RuntimeContext): Either[DspyError, String] =
    val invocation = AdapterInvocation(
      layout = Signature.fromString("question -> answer").layout,
      demos  = Vector(Example(values = rec("question" := "What is 1+1?", "answer" := "2"), inputKeys = Set("question"))),
      inputs = Example(values = rec("question" := "What is 2+2?"), inputKeys = Set("question")),
      request = LmRequest(model = "openai/demo", mode = LmMode.Chat)
    )
    ChatAdapter().format(invocation).map { prompt =>
      prompt.messages.map(m => s"[${m.role}] ${m.text.getOrElse("")}").mkString("\n\n")
    }

  // ── Snippet 4 — the system message an adapter builds for a signature ──
  // | system_message = dspy.ChatAdapter().format_system_message(signature); print(system_message)
  // dspy4s has no separate `format_system_message`; the system message is the formatted prompt's first message.
  def systemMessage(using RuntimeContext): Either[DspyError, String] =
    val invocation = AdapterInvocation(
      layout  = Signature.fromString("question -> answer").layout,
      demos   = Vector.empty,
      inputs  = Example(values = rec("question" := ""), inputKeys = Set("question")),
      request = LmRequest(model = "openai/demo", mode = LmMode.Chat)
    )
    ChatAdapter().format(invocation).map(_.messages.headOption.flatMap(_.text).getOrElse(""))

  /** Run `body` with `adapter` installed in the RuntimeContext — the dspy4s analogue of swapping
    * `dspy.configure(adapter=...)` for snippets 5 (ChatAdapter) and 6 (JSONAdapter). */
  private def withAdapter[A](adapter: Adapter)(body: RuntimeContext ?=> A)(using ctx: RuntimeContext): A =
    RuntimeEnvironment.withSettings(ctx.copy(adapter = Some(adapter))) {
      body(using RuntimeEnvironment.current)
    }

  // ── Snippets 5 & 6 — a structured-output Predict under ChatAdapter, then JSONAdapter ──
  // | predict = dspy.Predict(NewsQA); predict(science_field="Computer Theory", year=2022, num_of_outputs=1)
  private def runNews(using RuntimeContext): Either[DspyError, List[ScienceNews]] =
    Predict(Signature.of[NewsQA])
      .apply((science_field = "Computer Theory", year = 2022, num_of_outputs = 1))
      .map(_.output.news)

  def newsWithChatAdapter(using RuntimeContext): Either[DspyError, List[ScienceNews]] =
    withAdapter(ChatAdapter())(runNews)

  def newsWithJsonAdapter(using RuntimeContext): Either[DspyError, List[ScienceNews]] =
    withAdapter(JSONAdapter())(runNews)

  // ── Snippets 5/6 tail — `dspy.inspect_history()` ──
  // dspy4s's analogue is `RuntimeEnvironment.inspectHistory(n)` / `printHistory(n)`, rendering the per-thread
  // `RuntimeContext.history`. That history is recorded by a `ManagedLanguageModel` (per-LM composition), so we
  // wrap the ambient LM in one for this call, then ask + render the last entry. Returns (answer, history-render).
  def askThenInspect(question: String)(using ctx: RuntimeContext): Either[DspyError, (String, String)] =
    ctx.lm match
      case Some(lm: LanguageModel) =>
        RuntimeEnvironment.withSettings(ctx.copy(lm = Some(ManagedLanguageModel(lm)))) {
          given RuntimeContext = RuntimeEnvironment.current
          Predict(Signature.fromString("question -> answer"))
            .apply((question = question))
            .map(p => (p.output.answer, RuntimeEnvironment.inspectHistory(1))) // dspy.inspect_history(n=1)
        }
      case _ => Left(RuntimeError("no_lm", "no ambient LanguageModel to record history"))

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.learn.programming.adaptersMain"
@main def adaptersMain(): Unit = Demo.withLm {
  println("=== ChatAdapter system message ===")
  println(Adapters.systemMessage)
  println("\n=== ask ===")
  println(Adapters.ask("What is the capital of France?"))
  println("\n=== ask + inspect_history ===")
  Adapters.askThenInspect("What is the capital of France?") match
    case Right((answer, history)) => println(s"answer: $answer\n$history")
    case Left(err)                => println(s"error: ${err.message}")
}
