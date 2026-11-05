package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.contracts.NativeTools
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.contracts.CodeResult
import dspy4s.core.contracts.ConfigurationError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ReplCodeInterpreter
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.ToolCall
import dspy4s.core.contracts.:=
import dspy4s.core.data.RawPrediction
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.strategies.MultiChainComparison
import dspy4s.programs.strategies.RLM
import dspy4s.programs.strategies.ReAct
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer

/** A program with a `Vector[ToolCall]` output and native function calling on. Upstream has the tools in an input field
  * of the signature, so each inner predictor of a program has them. In the port the tools are not in the signature, so
  * each program gives them to its predictor.
  */
class ProgramToolOutputsSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private final class Lm(replies: LmOutput*) extends LanguageModel:
    val requests: ArrayBuffer[LmRequest]                                                       = ArrayBuffer.empty
    override val id: String                                                                    = "scripted"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsFunctionCalling: Boolean                                              = true
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      requests += request
      replies.lift(requests.size - 1) match
        case Some(reply) => Right(LmResponse(outputs = Vector(reply)))
        case None        => Left(RuntimeError("scripted", s"no reply for call ${requests.size}"))

  private final class Repl extends ReplCodeInterpreter:
    override def execute(code: String): Either[DspyError, CodeResult]                                       = execute(code, Map.empty)
    override def execute(code: String, variables: Map[String, DynamicValue]): Either[DspyError, CodeResult] =
      Right(CodeResult("", "", 0))
    override def close(): Unit = ()

  private val call: ToolCall = ToolCall("search", DynamicValues.record("query" := "belgium"))

  private val searchSpec: ToolSpec = ToolSpec(
    "search",
    Some("Search."),
    DynamicValues.record(
      "type"       := "object",
      "properties" -> DynamicValues.record("query" -> DynamicValues.record("type" := "string")),
      "required"   := List("query")
    )
  )

  private val searchTool: ToolFunction = ToolFunction("search", "Search.")(_ => Right(ToolFunction.result("found")))

  private val signature = Signature.derived[Ask, Plan]("QA")
  private val plain     = Signature.fromString("question -> answer")

  private def withModel[A](model: LanguageModel)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(
      RuntimeContext(lm = Some(model), adapter = Some(ChatAdapter(native = NativeTools(enabled = true))))
    ) {
      body(using RuntimeEnvironment.current)
    }

  /** The names of the provider tools of `request`. */
  private def toolNames(request: LmRequest): Vector[String] =
    DynamicValues.recordGet(request.options, "tools").toVector.flatMap(DynamicValues.asSequence).flatMap { tool =>
      DynamicValues.asRecord(tool)
        .flatMap(DynamicValues.recordGet(_, "function"))
        .flatMap(DynamicValues.asRecord)
        .flatMap(DynamicValues.recordGet(_, "name"))
        .flatMap(DynamicValues.asString)
    }

  private def reactStep(toolName: String): LmOutput =
    LmOutput(text =
      s"[[ ## next_thought ## ]]\nthink\n\n[[ ## next_tool_name ## ]]\n$toolName\n\n" +
        "[[ ## next_tool_args ## ]]\n{}\n\n[[ ## completed ## ]]"
    )

  test("MultiChainComparison gives its tools to the comparison predictor") {
    val program = MultiChainComparison(baseSignature = signature, m = AttemptCount(1), tools = Vector(searchSpec))
    val attempt = RawPrediction(DynamicValues.record("rationale" := "try", "toolCalls" := "none"))
    val model   = Lm(LmOutput(text = "[[ ## rationale ## ]]\nr\n\n[[ ## completed ## ]]", toolCalls = Vector(call)))

    val result = withModel(model)(program.compare(Ask("q"), Vector(attempt)))

    assertEquals(result.map(_.output.toolCalls.map(_.name)), Right(Vector("search")))
    assertEquals(toolNames(model.requests.head), Vector("search"))
  }

  test("MultiChainComparison with no tools is the error of upstream, before the model call") {
    val program = MultiChainComparison(baseSignature = signature, m = AttemptCount(1))
    val attempt = RawPrediction(DynamicValues.record("rationale" := "try", "toolCalls" := "none"))
    val model   = Lm()

    val result = withModel(model)(program.compare(Ask("q"), Vector(attempt)))

    assert(result.left.exists(_.isInstanceOf[ConfigurationError]), result.toString)
    assertEquals(model.requests.size, 0)
  }

  test("the extractor of ReAct gets the tools of the program, with no finish tool") {
    val program = ReAct(baseSignature = signature, tools = Vector(searchTool), maxIterations = IterationLimit(3))
    val model   = Lm(
      reactStep("finish"),
      LmOutput(text = "[[ ## reasoning ## ]]\nr\n\n[[ ## completed ## ]]", toolCalls = Vector(call))
    )

    val result = withModel(model)(program(Ask("q")))

    assertEquals(result.map(_.output.toolCalls.map(_.name)), Right(Vector("search")))
    assertEquals(model.requests.size, 2)
    assertEquals(toolNames(model.requests(1)), Vector("search"))
    assertEquals(toolNames(model.requests(0)), Vector.empty[String], "the react step keeps the text protocol")
  }

  test("the extractor of ReAct has no user tool with the name finish, and one provider tool for each name") {
    val userFinish: ToolFunction = ToolFunction("finish", "Mine.")(_ => Right(ToolFunction.result("done")))
    val second: ToolFunction     = ToolFunction("search", "Second.")(_ => Right(ToolFunction.result("found")))
    val program                  = ReAct(
      baseSignature = signature,
      tools = Vector(searchTool, userFinish, second),
      maxIterations = IterationLimit(3)
    )

    assertEquals(
      program.extractorPredict.tools.map(tool => (tool.name, tool.description)),
      Vector(("search", Some("Second.")))
    )
  }

  test("the extractor of ReAct with a plain output has no provider tools, and no tools in its prompt") {
    val program = ReAct(baseSignature = plain, tools = Vector(searchTool), maxIterations = IterationLimit(3))
    val model   = Lm(
      reactStep("finish"),
      LmOutput(text = "[[ ## reasoning ## ]]\nr\n\n[[ ## answer ## ]]\nBrussels\n\n[[ ## completed ## ]]")
    )

    val result = withModel(model)(program((question = "q")))

    assert(result.isRight, result.toString)
    assertEquals(toolNames(model.requests(1)), Vector.empty[String])
    assert(
      !model.requests(1).messages.flatMap(_.text).mkString("\n").contains("`tools`"),
      model.requests(1).messages.flatMap(_.text).mkString("\n")
    )
  }

  test("the extract step of RLM gets the tools of the program") {
    val program = RLM(
      baseSignature = signature,
      tools = Vector(searchTool),
      maxIterations = IterationLimit(1),
      interpreterFactory = Some((_, _) => new Repl)
    )
    val model = Lm(
      LmOutput(text = "[[ ## reasoning ## ]]\nr\n\n[[ ## code ## ]]\nprint(1)\n\n[[ ## completed ## ]]"),
      LmOutput(text = "", toolCalls = Vector(call))
    )

    val result = withModel(model)(program(Ask("q")))

    assertEquals(result.map(_.output.toolCalls.map(_.name)), Right(Vector("search")))
    assertEquals(model.requests.size, 2)
    assertEquals(toolNames(model.requests(1)), Vector("search"))
    assertEquals(toolNames(model.requests(0)), Vector.empty[String], "the action step has no tool calls output")
  }
