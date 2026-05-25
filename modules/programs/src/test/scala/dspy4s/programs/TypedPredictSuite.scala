package dspy4s.programs

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, FormattedPrompt, ParsedOutput}
import dspy4s.core.contracts.{
  DspyError, RuntimeContext, SettingKeys, SettingsData, SignatureSchema
}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{
  LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, LmUsage,
  Message, MessageRole
}
import dspy4s.typed.{InputField, OutputField, Shape, Spec, TypedSignature}
import munit.FunSuite

// Top-level fixtures (Mirror derivation requires top-level types).
case class P4QAInput(question: String)
case class P4QAOutput(answer: String, score: Double)

case class P4StrictOutput(answer: String, score: Int)  // forces decode failure: LM returns Double

trait P4QASpec extends Spec:
  def question: InputField[String]
  def answer:   OutputField[String]
  def score:    OutputField[Double]

trait P4QAMissingInputSpec extends Spec:
  def question: InputField[String]
  def context:  InputField[String]
  def answer:   OutputField[String]
  def score:    OutputField[Double]

def p4QaMethod(question: String): (answer: String, score: Double) = ???

class TypedPredictSuite extends FunSuite:

  // ── Test doubles ────────────────────────────────────────────────────────

  private object EchoQuestionAdapter extends Adapter:
    override val name: String = "echo-question"

    override def format(invocation: AdapterInvocation)(using RuntimeContext)
        : Either[DspyError, FormattedPrompt] =
      val q = invocation.inputs.values.get("question").map(_.toString).getOrElse("")
      Right(FormattedPrompt(messages = Vector(
        Message(role = MessageRole.User, text = Some(q))
      )))

    override def parse(signature: SignatureSchema, output: LmOutput)(using RuntimeContext)
        : Either[DspyError, ParsedOutput] =
      Right(ParsedOutput(values = Map(
        "answer" -> output.text,
        "score"  -> output.metadata.getOrElse("score", 0.0)
      )))

  private object FixedLm extends LanguageModel:
    override val id: String   = "fixed-lm"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(
        outputs = Vector(
          LmOutput(text = "Paris",  metadata = Map("score" -> 0.95)),
          LmOutput(text = "London", metadata = Map("score" -> 0.33))
        ),
        usage = Some(LmUsage(totalTokens = 12, promptTokens = 7, completionTokens = 5))
      ))

  private val defaultSettings: SettingsData = SettingsData(Map(
    SettingKeys.languageModel.name -> FixedLm,
    SettingKeys.adapter.name       -> EchoQuestionAdapter
  ))

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  // ── Happy path ──────────────────────────────────────────────────────────

  test("TypedPredict.run returns a TypedPrediction with the decoded output case class") {
    val sig = TypedSignature.derived[P4QAInput, P4QAOutput]("QA")
    RuntimeEnvironment.withSettings(defaultSettings) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = TypedPredict(sig).run(P4QAInput("Capital of France?"))
      result match
        case Right(tp) =>
          assertEquals(tp.output, P4QAOutput("Paris", 0.95))
        case Left(err) => fail(s"expected success, got: $err")
    }
  }

  test("TypedPredict.run supports spec-derived named-tuple input and typed output dot-access") {
    val sig = TypedSignature.of[P4QASpec]
    RuntimeEnvironment.withSettings(defaultSettings) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = TypedPredict(sig).run((question = "Capital of France?"))
      result match
        case Right(tp) =>
          val answer: String = tp.output.answer
          val score:  Double = tp.output.score
          assertEquals(answer, "Paris")
          assertEquals(score, 0.95)
        case Left(err) => fail(s"expected success, got: $err")
    }
  }

  test("TypedPredict.run supports method-derived named-tuple input and output") {
    val sig = TypedSignature.from(p4QaMethod)
    RuntimeEnvironment.withSettings(defaultSettings) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = TypedPredict(sig).run((question = "Capital of France?"))
      result match
        case Right(tp) =>
          val answer: String = tp.output.answer
          val score:  Double = tp.output.score
          assertEquals(answer, "Paris")
          assertEquals(score, 0.95)
        case Left(err) => fail(s"expected success, got: $err")
    }
  }

  // ── Input encoding reaches the adapter unchanged ────────────────────────

  test("TypedPredict encodes inputs through the typed shape before reaching the adapter") {
    // The EchoQuestionAdapter echoes invocation.inputs.values("question")
    // back through the LM request -- if encoding ever dropped or renamed
    // the field, the assertion below would still pass (the LM is fixed),
    // so we instrument with a capturing adapter.
    val capturedInputs = scala.collection.mutable.ArrayBuffer.empty[Map[String, Any]]
    val capturingAdapter = new Adapter:
      override val name = "capturing"
      override def format(invocation: AdapterInvocation)(using RuntimeContext) =
        capturedInputs += invocation.inputs.values
        Right(FormattedPrompt(messages = Vector(
          Message(role = MessageRole.User, text = Some("hi"))
        )))
      override def parse(signature: SignatureSchema, output: LmOutput)(using RuntimeContext) =
        Right(ParsedOutput(values = Map(
          "answer" -> "x",
          "score"  -> 0.5
        )))

    val sig = TypedSignature.derived[P4QAInput, P4QAOutput]("QA")
    RuntimeEnvironment.withSettings(SettingsData(Map(
      SettingKeys.languageModel.name -> FixedLm,
      SettingKeys.adapter.name       -> capturingAdapter
    ))) {
      given RuntimeContext = RuntimeEnvironment.current
      val _ = TypedPredict(sig).run(P4QAInput("Capital of France?"))
    }

    assertEquals(capturedInputs.size, 1)
    assertEquals(capturedInputs.head, Map[String, Any]("question" -> "Capital of France?"))
  }

  // ── Raw prediction is preserved ─────────────────────────────────────────

  test("TypedPredict preserves completions and lmUsage on the raw prediction") {
    val sig = TypedSignature.derived[P4QAInput, P4QAOutput]("QA")
    RuntimeEnvironment.withSettings(defaultSettings) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = TypedPredict(sig).run(P4QAInput("Capital of France?")).toOption.get
      assertEquals(result.raw.completions.map(_.size), Some(2))
      assertEquals(result.raw.lmUsage.flatMap(_.get("total_tokens")), Some(12L))
    }
  }

  // ── Decode-failure path ─────────────────────────────────────────────────

  test("TypedPredict surfaces output decode failures as Left(DspyError)") {
    val sig = TypedSignature.derived[P4QAInput, P4StrictOutput]("QA-strict")
    // LM returns a Double for `score`; P4StrictOutput expects Int.
    RuntimeEnvironment.withSettings(defaultSettings) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = TypedPredict(sig).run(P4QAInput("Capital of France?"))
      assert(result.isLeft, s"expected decode failure but got: $result")
    }
  }

  // ── Per-call runtime knobs (config + traceEnabled) ──────────────────────

  test("TypedPredict.run forwards `config` into ProgramCall.config (then LmRequest.options)") {
    val capturedRequests = scala.collection.mutable.ArrayBuffer.empty[LmRequest]
    val capturingLm = new LanguageModel:
      val id   = "capturing-lm"
      val mode = LmMode.Chat
      def call(req: LmRequest)(using RuntimeContext) =
        capturedRequests += req
        Right(LmResponse(
          outputs = Vector(LmOutput(text = "Paris", metadata = Map("score" -> 0.5))),
          usage   = Some(LmUsage(totalTokens = 1, promptTokens = 1, completionTokens = 0))
        ))

    val sig = TypedSignature.derived[P4QAInput, P4QAOutput]("QA")
    RuntimeEnvironment.withSettings(SettingsData(Map(
      SettingKeys.languageModel.name -> capturingLm,
      SettingKeys.adapter.name       -> EchoQuestionAdapter
    ))) {
      given RuntimeContext = RuntimeEnvironment.current
      val _ = TypedPredict(sig).run(
        P4QAInput("hi"),
        config = Map("temperature" -> 0.7, "max_tokens" -> 50)
      )
    }
    assertEquals(capturedRequests.size, 1)
    assertEquals(capturedRequests.head.options.get("temperature"), Some(0.7))
    assertEquals(capturedRequests.head.options.get("max_tokens"),  Some(50))
  }

  test("TypedPredict.run with traceEnabled=false suppresses the trace entry") {
    val sig = TypedSignature.derived[P4QAInput, P4QAOutput]("QA")
    RuntimeEnvironment.withSettings(defaultSettings) {
      given RuntimeContext = RuntimeEnvironment.current
      val _ = TypedPredict(sig).run(P4QAInput("Capital of France?"), traceEnabled = false)
      assertEquals(RuntimeEnvironment.current.trace.size, 0)
    }
  }

  // ── Missing-required-input rejection ────────────────────────────────────

  test("TypedPredict.run rejects spec-derived inputs missing a required field") {
    // Spec-derived signatures use Map[String, Any] for inputs, so a caller
    // could silently omit a declared input. The defensive check in
    // TypedPredict.run catches this before any LM call is dispatched.
    val specSig = TypedSignature.of[P4QAMissingInputSpec]
    val sig = TypedSignature(
      name = specSig.name,
      untyped = specSig.untyped,
      inputShape = new Shape.MapShape(specSig.untyped.inputFields),
      outputShape = specSig.outputShape
    )
    var lmCalled = false
    val sentinelLm = new LanguageModel:
      val id   = "sentinel"
      val mode = LmMode.Chat
      def call(req: LmRequest)(using RuntimeContext) =
        lmCalled = true
        Right(LmResponse(outputs = Vector(LmOutput(text = ""))))

    RuntimeEnvironment.withSettings(SettingsData(Map(
      SettingKeys.languageModel.name -> sentinelLm,
      SettingKeys.adapter.name       -> EchoQuestionAdapter
    ))) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = TypedPredict(sig).run(Map[String, Any]("question" -> "Capital of France?"))
      // Missing 'context' input -> Left, LM never invoked.
      assert(result.isLeft, s"expected missing-input failure, got: $result")
      assert(!lmCalled, "expected LM not to be called when required inputs are missing")
    }
  }

  // ── Decode-failure / trace divergence (Phase 4 known limitation) ────────

  test("decode failures: inner DynamicPredict still records trace + history (known limitation)") {
    // Characterizes current behavior so a future "wrap typed boundary in its
    // own scope" change is intentional. Today: TypedPredict.run returns
    // Left(decode failure), but the inner DynamicPredict already emitted its
    // module-end event and appended to trace/history. Asserted so a future
    // Phase 5+ change that consolidates the typed boundary's tracing has
    // to update this test deliberately.
    val sig = TypedSignature.derived[P4QAInput, P4StrictOutput]("QA-strict")
    RuntimeEnvironment.withSettings(defaultSettings) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = TypedPredict(sig).run(P4QAInput("Capital of France?"))
      assert(result.isLeft, s"expected decode failure but got: $result")
      // Inner DynamicPredict succeeded -> trace/history entries are present despite
      // the typed boundary reporting failure.
      assertEquals(RuntimeEnvironment.current.trace.size, 1)
      assertEquals(RuntimeEnvironment.current.history.size, 1)
    }
  }

  // ── No regression in the underlying DynamicPredict path ────────────────────────

  test("the inner DynamicPredict path still works directly (no PredictSuite regression)") {
    import dspy4s.core.signatures.SignatureDsl
    import dspy4s.programs.contracts.ProgramCall
    val sig = SignatureDsl.parse("question -> answer, score").toOption.get
    RuntimeEnvironment.withSettings(defaultSettings) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = DynamicPredict(sig).run(ProgramCall(inputs = Map("question" -> "x")))
      assert(result.isRight)
      assertEquals(result.toOption.get.values("answer"), "Paris")
    }
  }
