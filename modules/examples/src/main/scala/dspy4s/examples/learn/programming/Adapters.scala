/** Understanding DSPy Adapters
  *
  * Source: docs/docs/learn/programming/adapters.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/programming/adapters.md Status: translated (snippets
  * 1–6, incl. `inspect_history`). dspy4s's analogue of `dspy.inspect_history(n)` is
  * `RuntimeEnvironment.inspectHistory(n)` / `printHistory(n)`, which renders the per-thread `RuntimeContext.history`.
  * There is no global per-LM buffer: history is populated by a `ManagedLanguageModel` (per-LM composition), so wrap the
  * ambient LM in one to record calls (see `askThenInspect`). Everything else (the Predict calls, the explicit
  * ChatAdapter/JSONAdapter selection, the `adapter.format(...)` / system-message inspection) ports directly.
  *
  * Python's `adapter.format(signature, demos, inputs)` becomes `adapter.format(AdapterInvocation(layout, demos, inputs,
  * request))`, which returns a `FormattedPrompt`; the "system message" is just its first message. The adapter is
  * selected via the `RuntimeContext` of the call (`Module.apply` installs the context that it gets), mirroring
  * `dspy.configure(adapter=...)`. Pydantic `BaseModel` outputs become `Schema`-deriving case classes.
  */
package dspy4s.examples.learn.programming

import dspy4s.adapters.{ChatAdapter, JSONAdapter}
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.core.contracts.{DspyError, RuntimeContext, :=}
import dspy4s.core.data.Example
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.examples.Demo
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.runtime.ManagedLanguageModel
import dspy4s.programs.runtime.SettingsProgramRuntime
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.{InputField, OutputField, Signature, Spec}
import zio.blocks.schema.Schema

// ── Snippet 5/6 — a Pydantic model + a multi-field signature with a structured-list output ──
// | class ScienceNews(pydantic.BaseModel): text: str; scientists_involved: list[str]
case class ScienceNews(text: String, scientists_involved: List[String]) derives Schema

// | class NewsQA(dspy.Signature): """Get news about the given science field"""
// The docstring goes to `instructions`, where `runNews` makes the signature. A `Spec` has no `desc` for a field, so the
// hint `desc="science news"` of `news` has no port.
trait NewsQA extends Spec:
  def science_field: InputField[String]
  def year: InputField[Int]
  def num_of_outputs: InputField[Int]
  def news: OutputField[List[ScienceNews]]

object Adapters:

  // ── Snippets 1 & 2 — a basic Predict (default adapter, then an explicit ChatAdapter) ──
  // | predict = dspy.Predict("question -> answer"); result = predict(question="What is the capital of France?")
  // The adapter is the one in the ambient RuntimeContext; `dspy.ChatAdapter()` is the default (Demo installs it).
  def ask(question: String)(using RuntimeContext): Either[DspyError, String] =
    Predict(Signature.fromString("question -> answer"))((question = question)).map(_.output.answer)

  // ── Snippet 3 — inspect what an adapter sends to the LM ──
  // | signature = dspy.Signature("question -> answer"); inputs = {...}; demos = [{...}]
  // | adapter = dspy.ChatAdapter(); print(adapter.format(signature, demos, inputs))
  def formattedPrompt(using RuntimeContext): Either[DspyError, String] =
    val invocation = AdapterInvocation(
      layout = Signature.fromString("question -> answer").layout,
      demos = Vector(Example("question" := "What is 1+1?", "answer" := "2").withInputs(Set("question"))),
      inputs = Example("question" := "What is 2+2?").withInputs(Set("question")),
      request = LmRequest(model = "openai/demo")
    )
    ChatAdapter().format(invocation).map { prompt =>
      prompt.messages.map(m => s"[${m.role}] ${m.text.getOrElse("")}").mkString("\n\n")
    }

  // ── Snippet 4 — the system message an adapter builds for a signature ──
  // | system_message = dspy.ChatAdapter().format_system_message(signature); print(system_message)
  // dspy4s has no separate `format_system_message`; the system message is the formatted prompt's first message.
  def systemMessage(using RuntimeContext): Either[DspyError, String] =
    val invocation = AdapterInvocation(
      layout = Signature.fromString("question -> answer").layout,
      demos = Vector.empty,
      inputs = Example("question" := "").withInputs(Set("question")),
      request = LmRequest(model = "openai/demo")
    )
    ChatAdapter().format(invocation).map(_.messages.headOption.flatMap(_.text).getOrElse(""))

  // ── Snippets 5 & 6 — a structured-output Predict under ChatAdapter, then JSONAdapter ──
  // | predict = dspy.Predict(NewsQA); predict(science_field="Computer Theory", year=2022, num_of_outputs=1)
  // Each function below gives `runNews` a context with its adapter, as `dspy.configure(adapter=...)` does.
  private def runNews(using RuntimeContext): Either[DspyError, List[ScienceNews]] =
    Predict(Signature.of[NewsQA](instructions = "Get news about the given science field"))(
      (science_field = "Computer Theory", year = 2022, num_of_outputs = 1)
    ).map(_.output.news)

  // --8<-- [start:adapter-select]
  def newsWithChatAdapter(using ctx: RuntimeContext): Either[DspyError, List[ScienceNews]] =
    runNews(using ctx.copy(adapter = Some(ChatAdapter())))

  def newsWithJsonAdapter(using ctx: RuntimeContext): Either[DspyError, List[ScienceNews]] =
    runNews(using ctx.copy(adapter = Some(JSONAdapter())))
  // --8<-- [end:adapter-select]

  // ── Snippets 5/6 tail — `dspy.inspect_history()` ──
  // dspy4s's analogue is `RuntimeEnvironment.inspectHistory(n)` / `printHistory(n)`, rendering the per-thread
  // `RuntimeContext.history`. A `ManagedLanguageModel` (per-LM composition) records that history, so this call gets a
  // context with the LM in one. The history of the call stays after the call. Returns (answer, history-render).
  // --8<-- [start:inspect-history]
  def askThenInspect(question: String)(using ctx: RuntimeContext): Either[DspyError, (String, String)] =
    SettingsProgramRuntime.default.resolveModel.flatMap { lm =>
      Predict(Signature.fromString("question -> answer"))((question = question))(
        using ctx.copy(lm = Some(ManagedLanguageModel(lm)))
      ).map(p => (p.output.answer, RuntimeEnvironment.inspectHistory(1)))
    }
  // --8<-- [end:inspect-history]

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.learn.programming.adaptersMain"
@main def adaptersMain(): Unit =
  Demo.withLm {
    println("=== ChatAdapter system message ===")
    println(Adapters.systemMessage)
    println("\n=== ask ===")
    println(Adapters.ask("What is the capital of France?"))
    println("\n=== ask + inspect_history ===")
    Adapters.askThenInspect("What is the capital of France?") match
      case Right((answer, history)) => println(s"answer: $answer\n$history")
      case Left(err)                => println(s"error: ${err.message}")
  }
