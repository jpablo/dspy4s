/** Understanding DSPy Adapters
  *
  * Source: docs/docs/learn/programming/adapters.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/programming/adapters.md Status: translated (snippets
  * 1–6, incl. `inspect_history`). The functional interpreter returns an explicit `ProgramEvent` journal instead of a
  * global history buffer. Everything else (the Predict calls, the explicit ChatAdapter/JSONAdapter selection, the
  * `adapter.format(...)` / system-message inspection) ports directly.
  *
  * Python's `adapter.format(signature, demos, inputs)` becomes `adapter.format(AdapterInvocation(layout, demos, inputs,
  * request))`, which returns a `FormattedPrompt`; the "system message" is just its first message. The adapter is
  * selected via the ambient `RuntimeContext` (here swapped with `withAdapter`), mirroring
  * `dspy.configure(adapter=...)`. Pydantic `BaseModel` outputs become `Schema`-deriving case classes.
  */
package dspy4s.examples.learn.programming

import dspy4s.adapters.{ChatAdapter, JSONAdapter}
import dspy4s.adapters.contracts.{Adapter, AdapterInvocation}
import dspy4s.core.contracts.{ConfigurationError, DspyError, DynamicValues, RuntimeContext, :=}
import dspy4s.core.data.Example
import dspy4s.examples.Demo
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmRequest}
import dspy4s.programs.{LivePredictionBackend, PredictionBackend, Program, ProgramRunner}
import dspy4s.signatures.{InputField, OutputField, Signature, Spec}
import zio.blocks.schema.{DynamicValue, Schema}

// ── Snippet 5/6 — a Pydantic model + a multi-field signature with a structured-list output ──
// | class ScienceNews(pydantic.BaseModel): text: str; scientists_involved: list[str]
case class ScienceNews(text: String, scientists_involved: List[String]) derives Schema

// | class NewsQA(dspy.Signature): """Get news about the given science field"""
trait NewsQA extends Spec:
  def science_field: InputField[String]
  def year: InputField[Int]
  def num_of_outputs: InputField[Int]
  def news: OutputField[List[ScienceNews]]

object Adapters:

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)

  private val askProgram = Program.predict(
    Signature.fromString("question -> answer")
  )

  // ── Snippets 1 & 2 — a basic Predict (default adapter, then an explicit ChatAdapter) ──
  // | predict = dspy.Predict("question -> answer"); result = predict(question="What is the capital of France?")
  // The adapter is the one in the ambient RuntimeContext; `dspy.ChatAdapter()` is the default (Demo installs it).
  def ask(question: String)(using PredictionBackend): Either[DspyError, String] =
    Demo.run(askProgram, (question = question)).map(_.output.answer)

  // ── Snippet 3 — inspect what an adapter sends to the LM ──
  // | signature = dspy.Signature("question -> answer"); inputs = {...}; demos = [{...}]
  // | adapter = dspy.ChatAdapter(); print(adapter.format(signature, demos, inputs))
  def formattedPrompt(using RuntimeContext): Either[DspyError, String] =
    val invocation = AdapterInvocation(
      layout = Signature.fromString("question -> answer").layout,
      demos = Vector(Example(values = rec("question" := "What is 1+1?", "answer" := "2"), inputKeys = Set("question"))),
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
      layout = Signature.fromString("question -> answer").layout,
      demos = Vector.empty,
      inputs = Example(values = rec("question" := ""), inputKeys = Set("question")),
      request = LmRequest(model = "openai/demo", mode = LmMode.Chat)
    )
    ChatAdapter().format(invocation).map(_.messages.headOption.flatMap(_.text).getOrElse(""))

  // ── Snippets 5 & 6 — a structured-output Predict under ChatAdapter, then JSONAdapter ──
  // | predict = dspy.Predict(NewsQA); predict(science_field="Computer Theory", year=2022, num_of_outputs=1)
  private val newsProgram = Program.predict(Signature.of[NewsQA])

  private def runNews(adapter: Adapter)(using ctx: RuntimeContext): Either[DspyError, List[ScienceNews]] =
    ctx.lm match
      case Some(model: LanguageModel) =>
        val backend = new LivePredictionBackend(model, adapter, ctx.copy(adapter = Some(adapter)))
        Demo.run(newsProgram, (science_field = "Computer Theory", year = 2022, num_of_outputs = 1))(using backend)
          .map(_.output.news)
      case _ => Left(ConfigurationError("No language model is available"))

  // --8<-- [start:adapter-select]
  def newsWithChatAdapter(using RuntimeContext): Either[DspyError, List[ScienceNews]] =
    runNews(ChatAdapter())

  def newsWithJsonAdapter(using RuntimeContext): Either[DspyError, List[ScienceNews]] =
    runNews(JSONAdapter())
  // --8<-- [end:adapter-select]

  // ── Snippets 5/6 tail — `dspy.inspect_history()` ──
  // dspy4s returns the run-local event journal. It records the prediction component, inputs, output, and parameter ID.
  // --8<-- [start:inspect-history]
  def askThenInspect(question: String)(using PredictionBackend): Either[DspyError, (String, String)] =
    Demo.runEffect(ProgramRunner.runJournaled(askProgram, (question = question))) match
      case Left(impossible) => impossible
      case Right(execution) => execution.outcome.map { prediction =>
          prediction.output.answer -> execution.events.mkString("\n")
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
