package dspy4s.programs

import dspy4s.adapters.JSONAdapter
import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, FormattedPrompt, ParsedOutput}
import dspy4s.core.contracts.{DspyError, RuntimeContext, SignatureLayout}
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, Message, MessageRole}
import dspy4s.programs.contracts.ProgramCall
import munit.FunSuite
import zio.blocks.schema.DynamicValue

/** G-7 v1: the engine merges `FormattedPrompt.requestOptions` UNDER `invocation.request.options` (per-call /
  * module options win on collision) before calling the LM. */
class RequestOptionsMergeSuite extends FunSuite:

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)

  /** Adapter that contributes a fixed `requestOptions` and a value that collides with a per-call option. */
  private final class OptsAdapter(opts: DynamicValue.Record) extends Adapter:
    override val name: String = "opts"
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("hi"))), requestOptions = opts))
    override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
      Right(ParsedOutput(values = rec("answer" := output.text)))

  private final class CapturingLm(val sink: scala.collection.mutable.ArrayBuffer[LmRequest]) extends LanguageModel:
    override val id: String   = "capturing-lm"
    override val mode: LmMode = LmMode.Chat
    override val supportsResponseSchema: Boolean = true
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      sink += request
      Right(LmResponse(outputs = Vector(LmOutput(text = "Paris"))))

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit  = RuntimeEnvironment.resetForTests()

  private def capture(adapter: Adapter)(body: RuntimeContext ?=> Unit): Vector[LmRequest] =
    val sink = scala.collection.mutable.ArrayBuffer.empty[LmRequest]
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(CapturingLm(sink)), adapter = Some(adapter))) {
      given RuntimeContext = RuntimeEnvironment.current
      body
    }
    sink.toVector

  test("engine merges adapter requestOptions into the LM request options") {
    val layout = SignatureDsl.parse("question -> answer").toOption.get
    val adapterOpts = rec("response_format" := "json_object", "from_adapter" := true)
    val reqs = capture(new OptsAdapter(adapterOpts)) {
      val _ = DynamicPredict(layout).apply(ProgramCall(inputs = rec("question" := "x")))
    }
    assertEquals(reqs.size, 1)
    val opts = DynamicValues.recordToMap(reqs.head.options)
    assertEquals(opts.get("response_format"), Some("json_object": Any))
    assertEquals(opts.get("from_adapter"),    Some(true: Any))
  }

  test("per-call/module options WIN over adapter requestOptions on key collision") {
    val layout = SignatureDsl.parse("question -> answer").toOption.get
    val adapterOpts = rec("temperature" := 0.0, "from_adapter" := true)
    val module = DynamicPredict(layout, config = DynamicValues.record("temperature" := 0.9))
    val reqs = capture(new OptsAdapter(adapterOpts)) {
      val _ = module.apply(ProgramCall(inputs = rec("question" := "x")))
    }
    assertEquals(reqs.size, 1)
    val opts = DynamicValues.recordToMap(reqs.head.options)
    assertEquals(opts.get("temperature"),  Some(0.9: Any))  // module config wins over adapter's
    assertEquals(opts.get("from_adapter"), Some(true: Any)) // non-colliding adapter option preserved
  }

  test("end-to-end: typed Predict + JSONAdapter + capable LM puts response_format in the LM request") {
    // The typed Predict path supplies outputJsonSchema (rendered from Schema[O]); JSONAdapter emits a
    // response_format that the engine merges into request.options. The capturing LM declares
    // supportsResponseSchema = true.
    val sig = dspy4s.typed.Signature.derived[MCQAInput, MCQAOutput]("QA")
    val reqs = capture(JSONAdapter()) {
      val _ = Predict(sig).apply(MCQAInput("x"))
    }
    assertEquals(reqs.size, 1)
    val rfType = DynamicValues.recordGet(reqs.head.options, "response_format").collect {
      case r: DynamicValue.Record => r
    }.flatMap(DynamicValues.recordGet(_, "type")).collect {
      case DynamicValue.Primitive(zio.blocks.schema.PrimitiveValue.String(s)) => s
    }
    assertEquals(rfType, Some("json_schema"))
  }
