package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, MessageRole}
import dspy4s.programs.contracts.{ToolFunction, description}
import dspy4s.core.runtime.LocalPythonInterpreter
import dspy4s.programs.strategies.{ChainOfThought, RLM, ReAct}
import dspy4s.signatures.Signature
import munit.FunSuite

import scala.io.Source
import scala.util.Using

/** The tools of the ReAct case of `scripts/golden/program_prompts.py`. */
object ProgramPromptTools:
  @description("Search the web for a query.")
  def search(query: String): String = s"3 results for $query"

  def add(a: Int, b: Int): Int = a + b

/** The LM calls of ChainOfThought, ReAct, and RLM are equal to the upstream calls (decision D3, PORT_GAPS G-30, G-37,
  * and G-38). Each case of the golden data holds the calls of an upstream run with a scripted model: the messages, and
  * the text that the model gave back. The suite runs the same program of the port with a model that gives back the same
  * texts, and compares the messages, character for character. Run `scripts/golden/program_prompts.py` to write the data
  * again.
  *
  * The ReAct data is in the port form of the boundary rule of D3: the arguments of a tool are JSON text, and the type
  * text of `next_tool_name` and `next_tool_args` is the text of the port (see the generator). The RLM data has no
  * sub-agent text, because the port has no sandbox `dspy` facade (P8). Its action prompt has the execution text of the
  * interpreter (I7).
  */
class ProgramPromptParitySuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val golden: ujson.Value =
    ujson.read(Using.resource(Source.fromResource("golden/program-prompts.json", getClass.getClassLoader))(_.mkString))

  private def caseOf(id: String): ujson.Value =
    golden("cases").arr.find(_("id").str == id).getOrElse(fail(s"no case $id"))

  /** A model that gives back the texts of the golden calls, in order, and keeps each request. */
  private final class Replay(outputs: Vector[String]) extends LanguageModel:
    @volatile var requests: Vector[LmRequest]                                                  = Vector.empty
    override val id: String                                                                    = "replay"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val index = requests.size
      requests = requests :+ request
      Right(LmResponse(outputs = Vector(LmOutput(text = outputs.lift(index).getOrElse("")))))

  private def roleName(role: MessageRole): String =
    role match
      case MessageRole.System    => "system"
      case MessageRole.User      => "user"
      case MessageRole.Assistant => "assistant"
      case MessageRole.Tool      => "tool"

  /** Run `body` with a replay of the case `id`, and compare each request with the golden messages. */
  private def assertCalls[A](id: String)(body: RuntimeContext ?=> A): A =
    val calls  = caseOf(id)("calls").arr.toVector
    val replay = Replay(calls.map(_("output").str))
    val result = RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(replay), adapter = Some(ChatAdapter()))) {
      body(using RuntimeEnvironment.current)
    }
    val actual   = replay.requests.map(_.messages.map(message => roleName(message.role) -> message.text.getOrElse("")))
    val expected = calls.map(_("messages").arr.toVector.map(message => message("role").str -> message("content").str))
    assertEquals(actual.size, expected.size, s"the number of LM calls of $id")
    actual.zip(expected).zipWithIndex.foreach { case ((got, want), index) =>
      assertEquals(got, want, s"the call $index of $id")
    }
    result

  test("ChainOfThought sends the upstream messages, with the instructions of the base signature") {
    assertCalls("cot_basic") {
      val result = ChainOfThought(Signature.fromString("question -> answer"))((question = "What is 2+2?"))
      assertEquals(result.map(_.output.answer), Right("4"))
    }
  }

  test("ChainOfThought keeps the custom instructions of the signature") {
    val signature = Signature.fromString("question -> answer").withInstructions("Answer in one word.")
    assertCalls("cot_instructions") {
      val result = ChainOfThought(signature)((question = "What color is the sky?"))
      assertEquals(result.map(_.output.answer), Right("Blue"))
    }
  }

  test("ReAct sends the upstream messages for each step and for the extraction") {
    val tools = Vector(
      ToolFunction.fromMethod(ProgramPromptTools.search),
      ToolFunction.fromMethod(ProgramPromptTools.add)
    )
    val react = ReAct(
      baseSignature = Signature.fromString("question -> answer"),
      tools = tools,
      maxIterations = IterationLimit(5)
    )
    assertCalls("react") {
      val result = react((question = "How many cats?"))
      assertEquals(result.map(_.output.answer), Right(caseOf("react")("answer").str))
    }
  }

  test("RLM sends the upstream messages for each action, with the execution text of the local interpreter") {
    val rlm = RLM(
      baseSignature = Signature.fromString("context, query -> answer"),
      maxIterations = IterationLimit(5),
      interpreterFactory = Some(LocalPythonInterpreter.factory())
    )
    assertCalls("rlm_submit") {
      val result = rlm((context = "hello world", query = "How long is the context?"))
      assertEquals(result.map(_.output.answer), Right(caseOf("rlm_submit")("answer").str))
    }
  }

  test("RLM sends the upstream messages of the extraction after the last iteration") {
    val rlm = RLM(
      baseSignature = Signature.fromString("context, query -> answer"),
      maxIterations = IterationLimit(1),
      interpreterFactory = Some(LocalPythonInterpreter.factory())
    )
    assertCalls("rlm_extract") {
      val result = rlm((context = "hello world", query = "What is the context in upper case?"))
      assertEquals(result.map(_.output.answer), Right(caseOf("rlm_extract")("answer").str))
    }
  }

  test("RLM takes the execution text of the factory of the runtime context at call time") {
    val rlm     = RLM(baseSignature = Signature.fromString("context, query -> answer"), maxIterations = IterationLimit(5))
    val calls   = caseOf("rlm_submit")("calls").arr.toVector
    val replay  = Replay(calls.map(_("output").str))
    val context = RuntimeContext(
      lm = Some(replay),
      adapter = Some(ChatAdapter()),
      interpreterFactory = Some(LocalPythonInterpreter.factory())
    )
    val result = RuntimeEnvironment.withSettings(context) {
      rlm((context = "hello world", query = "How long is the context?"))(using RuntimeEnvironment.current)
    }
    assertEquals(result.map(_.output.answer), Right("eleven"))
    val system = replay.requests.headOption.flatMap(_.messages.headOption).flatMap(_.text).getOrElse("")
    assert(
      system.contains("Execution environment:\n        Code runs as Python in a persistent local subprocess."),
      system
    )
    assert(!system.contains("Pyodide"), system)
  }

  test("the Deno sandbox factory has the upstream text of the Pyodide interpreter") {
    assertEquals(
      RLM.defaultInterpreterFactory.executionInstructions,
      "Python runs in Pyodide/WebAssembly. State persists across executions, but subprocesses and native " +
        "extensions are unavailable. Python standard libraries such as re, json, collections, and math are available. " +
        "Host filesystem, environment, and network access require explicit permission."
    )
  }
