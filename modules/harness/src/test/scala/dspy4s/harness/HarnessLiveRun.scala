package dspy4s.harness

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.kernel.*
import dspy4s.harness.sandbox.{Backends, HostList}
import dspy4s.lm.contracts.{LanguageModel, LmCapabilities, LmMode, LmRequest, LmResponse}
import dspy4s.lm.providers.{OpenAiClient, OpenAiLanguageModel}
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*

import Models.*

/** A live run of a harness with a real model (CODE_HARNESS_DESIGN.md, 9). Each test is one small task.
  *
  * It runs only with `HARNESS_LIVE=true`. It reads the endpoint from `OPENAI_BASE_URL`, the model from `DSPY_MODEL` and
  * the key from `OPENAI_API_KEY`. It writes the transcript of each task to the folder `HARNESS_LIVE_OUT`. A wrong
  * answer does not fail a test, because the transcript is the result.
  */
class HarnessLiveRun extends FunSuite:

  override val munitTimeout: Duration = 15.minutes

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val live = sys.env.get("HARNESS_LIVE").contains("true")

  private def out: Path =
    Files.createDirectories(Path.of(sys.env.getOrElse("HARNESS_LIVE_OUT", "target/harness-live")))

  /** A model that records each request and each response of another model, with the time of each call. */
  private final class Recording(inner: LanguageModel) extends LanguageModel:
    override val id: String                                                  = inner.id
    override val mode: LmMode                                                = inner.mode
    override def supportsFunctionCalling: Boolean                            = inner.supportsFunctionCalling
    override def supportsResponseSchema: Boolean                             = inner.supportsResponseSchema
    override def supportsReasoning: Boolean                                  = inner.supportsReasoning
    override def defaultOptions: DynamicValue.Record                         = inner.defaultOptions
    val calls: ArrayBuffer[(LmRequest, Either[DspyError, LmResponse], Long)] = ArrayBuffer.empty

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val start    = System.nanoTime()
      val response = inner.call(request)
      calls += ((request, response, (System.nanoTime() - start) / 1_000_000)): Unit
      response

  private def model: LanguageModel =
    val name = sys.env.getOrElse("DSPY_MODEL", "none")
    val base = sys.env.getOrElse("OPENAI_BASE_URL", OpenAiClient.defaultBaseUrl)
    val lm   = OpenAiLanguageModel.fromEnv(name, base).fold(e => throw IllegalStateException(s"no model: $e"), m => m)
    // The port declares no capability for a server that is not OpenAI. A model that reasons gets no reasoning field.
    if sys.env.get("HARNESS_LIVE_NATIVE_REASONING").contains("false") then lm
    else lm.copy(capabilities = Some(LmCapabilities(reasoning = true)))

  private def render(entry: Entry): String =
    entry match
      case Entry.Acted(thought, text, code) => s"### Acted (reasoning: ${thought.length} characters)\n\n$text\n\n" +
          code.fold("(no code)")(c => s"```scala\n$c\n```")
      case Entry.EffectStarted(id, op, args)       => s"- call ${id.n}: `$op` ${args.print.take(400)}"
      case Entry.EffectDone(id, result)            => s"- done ${id.n}: ${result.toString.take(400)}"
      case Entry.Observed(output, outcome, writes) =>
        s"### Observed: ${outcome.toString.take(300)}, writes ${writes.keys.mkString(", ")}\n\n```\n${output.take(4000)}\n```"
      case Entry.StepStarted(step)  => s"## Step $step"
      case Entry.TurnEnded(outcome) => s"## TurnEnded: ${outcome.toString.take(500)}"
      case Entry.StopRequested(r)   => s"## Stop: $r"
      case Entry.Activated(_, _)    => "## Activated"
      case other                    => s"- ${other.toString.take(300)}"

  private def text(request: LmRequest): String =
    request.messages.map(m => s"## ${m.role}\n\n${m.text.getOrElse(m.parts.map(_.payload).mkString("\n"))}").mkString(
      "\n\n"
    )

  /** Runs one task in a new place with `files` in its workspace, and writes its transcript and its first prompt. */
  private def task(name: String, question: String, expected: String, files: Map[String, String] = Map.empty): Unit =
    assume(live, "set HARNESS_LIVE=true for a live run")
    val place = Place()
    try
      files.foreach { (path, content) =>
        val file = place.root.resolve(path)
        Files.createDirectories(file.getParent): Unit
        Files.writeString(file, content): Unit
      }
      var journal = Vector.empty[Logged]
      val lm      = Recording(model)
      val env     = HarnessEnv(
        Some(place.root),
        place.home,
        HostList.empty,
        Backends.detect(),
        Secrets.known(sys.env, Map.empty),
        journals = journal = _
      )
      val start   = System.nanoTime()
      val result  = withModel(lm)(Harness.forTask(signature, env, HarnessLimits(steps = 8))(Question(question)))
      val seconds = (System.nanoTime() - start) / 1_000_000_000
      val answer  = result.map(_.output.answer.trim)
      val correct = answer.exists(_.contains(expected))
      val ended   = journal.collect { case Logged(_, _, Entry.TurnEnded(o)) => o.toString.take(200) }
      val replies = lm.calls.zipWithIndex.map { case ((_, response, millis), k) =>
        val body = response.fold(
          e => s"error: $e",
          r => r.outputs.map(o => s"reasoning: ${o.reasoning.length} characters\n\n```\n${o.text}\n```").mkString("\n")
        )
        s"### Model call $k ($millis ms)\n\n$body"
      }
      val report = Vector(
        s"# $name",
        s"- question: $question",
        s"- expected: $expected",
        s"- result: $answer",
        s"- correct: $correct",
        s"- turn: ${ended.mkString(", ")}",
        s"- model calls: ${lm.calls.size}, ${seconds} s in all",
        s"- sandbox: ${Backends.detect().fold("none")(_.toString)}",
        s"- native reasoning: ${lm.supportsReasoning}",
        "",
        "# The journal",
        journal.map(l => render(l.entry)).mkString("\n\n"),
        "",
        "# The raw replies of the model",
        replies.mkString("\n\n")
      ).mkString("\n")
      Files.writeString(out.resolve(s"$name.md"), report): Unit
      lm.calls.headOption.foreach((request, _, _) =>
        Files.writeString(out.resolve(s"$name.prompt.md"), text(request)): Unit
      )
      lm.calls.lastOption.foreach((request, _, _) =>
        Files.writeString(out.resolve(s"$name.last-prompt.md"), text(request)): Unit
      )
      println(
        s"LIVE $name: correct=$correct result=$answer calls=${lm.calls.size} seconds=$seconds turn=${ended.mkString}"
      )
    finally place.close()

  test("Live: sum of squares") {
    task("sum-squares", "What is the sum of the squares of the integers from 1 to 20? Give only the number.", "2870")
  }

  test("Live: read a CSV file") {
    val csv =
      "id,status,amount\n1,paid,120\n2,failed,35\n3,paid,80\n4,failed,15\n5,refunded,60\n6,failed,50\n7,paid,200\n8,failed,5\n"
    task(
      "csv",
      "The workspace has the file orders.csv. What is the total amount of the orders with the status failed? Give only the number.",
      "105",
      Map("orders.csv" -> csv)
    )
  }

  test("Live: count lines with a shell command") {
    task(
      "exec",
      "Use a shell command to count the lines of all the .txt files in the folder notes together. Give only the number.",
      "8",
      Map("notes/a.txt" -> "one\ntwo\nthree\n", "notes/b.txt" -> "1\n2\n3\n4\n5\n", "notes/c.md" -> "x\ny\n")
    )
  }

  test("Live: write a file and read it back") {
    task(
      "write",
      "Write the file report.txt with the text hello harness and no newline. Then read the file back, and give its length in characters. Give only the number.",
      "13"
    )
  }

  test("Live: a fact with no code") {
    task("capital", "What is the capital of France? Give only the name.", "Paris")
  }
