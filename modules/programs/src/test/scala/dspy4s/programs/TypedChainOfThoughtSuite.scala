package dspy4s.programs

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, FormattedPrompt, ParsedOutput}
import dspy4s.core.contracts.{
  DspyError, NotFoundError, RuntimeContext, SettingKeys, SettingsData, SignatureSchema,
  ValidationError
}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{
  LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, LmUsage,
  Message, MessageRole
}
import dspy4s.typed.{InputField, OutputField, Spec, TypedSignature}
import munit.FunSuite

// Top-level spec traits (Mirror derivation requires top-level types).
trait TcotSummarizeSpec extends Spec:
  def document: InputField[String]
  def summary:  OutputField[String]

trait TcotMultiOutputSpec extends Spec:
  def question: InputField[String]
  def answer:   OutputField[String]
  def score:    OutputField[Double]

class TypedChainOfThoughtSuite extends FunSuite:

  // ── Test doubles ────────────────────────────────────────────────────────

  /** Adapter that produces both `reasoning` and a set of base output
    * fields. The base fields are configurable via constructor so different
    * tests can exercise different output shapes. */
  private class ScriptedAdapter(
      reasoning: String,
      baseValues: Map[String, Any]
  ) extends Adapter:
    override val name: String = "scripted"

    override def format(invocation: AdapterInvocation)(using RuntimeContext)
        : Either[DspyError, FormattedPrompt] =
      Right(FormattedPrompt(messages = Vector(
        Message(role = MessageRole.User, text = Some("scripted prompt"))
      )))

    override def parse(signature: SignatureSchema, output: LmOutput)(using RuntimeContext)
        : Either[DspyError, ParsedOutput] =
      Right(ParsedOutput(values = baseValues + ("reasoning" -> reasoning)))

  /** Adapter that emits the base fields but FORGETS to emit `reasoning`.
    * Used to verify TypedChainOfThought surfaces a missing-reasoning
    * error rather than silently dropping the field. */
  private class NoReasoningAdapter(baseValues: Map[String, Any]) extends Adapter:
    override val name: String = "no-reasoning"
    override def format(invocation: AdapterInvocation)(using RuntimeContext)
        : Either[DspyError, FormattedPrompt] =
      Right(FormattedPrompt(messages = Vector(
        Message(role = MessageRole.User, text = Some("hi"))
      )))
    override def parse(signature: SignatureSchema, output: LmOutput)(using RuntimeContext)
        : Either[DspyError, ParsedOutput] =
      Right(ParsedOutput(values = baseValues))

  private object FixedLm extends LanguageModel:
    override val id: String   = "fixed-lm"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(
        outputs = Vector(LmOutput(text = "lm output", metadata = Map.empty)),
        usage   = Some(LmUsage(totalTokens = 10, promptTokens = 4, completionTokens = 6))
      ))

  private def settings(adapter: Adapter): SettingsData = SettingsData(Map(
    SettingKeys.languageModel.name -> FixedLm,
    SettingKeys.adapter.name       -> adapter
  ))

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  // ── Happy path ──────────────────────────────────────────────────────────

  test("TypedChainOfThought.run returns a typed prediction with reasoning prepended") {
    val sig = TypedSignature.of[TcotSummarizeSpec]
    val adapter = new ScriptedAdapter(
      reasoning  = "The text is about football transfers; condense the key facts.",
      baseValues = Map("summary" -> "Lee signed a new contract with Barnsley.")
    )
    RuntimeEnvironment.withSettings(settings(adapter)) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = TypedChainOfThought(sig).run((document = "..."))
      result match
        case Right(tp) =>
          // Typed dot-access for both reasoning and the base output field.
          val reasoning: String = tp.output.reasoning
          val summary:   String = tp.output.summary
          assertEquals(reasoning, "The text is about football transfers; condense the key facts.")
          assertEquals(summary,   "Lee signed a new contract with Barnsley.")
        case Left(err) => fail(s"expected success, got: $err")
    }
  }

  test("TypedChainOfThought preserves declaration order and types for multi-output signatures") {
    val sig = TypedSignature.of[TcotMultiOutputSpec]
    val adapter = new ScriptedAdapter(
      reasoning  = "Paris is the capital because of historical and political factors.",
      baseValues = Map("answer" -> "Paris", "score" -> 0.95)
    )
    RuntimeEnvironment.withSettings(settings(adapter)) {
      given RuntimeContext = RuntimeEnvironment.current
      val tp = TypedChainOfThought(sig).run((question = "Capital of France?")).toOption.get
      val r: String = tp.output.reasoning
      val a: String = tp.output.answer
      val s: Double = tp.output.score
      assertEquals(r, "Paris is the capital because of historical and political factors.")
      assertEquals(a, "Paris")
      assertEquals(s, 0.95)
    }
  }

  // ── Raw prediction is preserved ─────────────────────────────────────────

  test("TypedChainOfThought preserves the raw DynamicPrediction (including lmUsage)") {
    val sig = TypedSignature.of[TcotSummarizeSpec]
    val adapter = new ScriptedAdapter(
      reasoning  = "short reasoning",
      baseValues = Map("summary" -> "short summary")
    )
    RuntimeEnvironment.withSettings(settings(adapter)) {
      given RuntimeContext = RuntimeEnvironment.current
      val tp = TypedChainOfThought(sig).run((document = "...")).toOption.get
      assertEquals(tp.raw.lmUsage.flatMap(_.get("total_tokens")), Some(10L))
      assertEquals(tp.raw.value("reasoning"), Right("short reasoning"))
      assertEquals(tp.raw.value("summary"),   Right("short summary"))
    }
  }

  // ── Failure modes ───────────────────────────────────────────────────────

  test("TypedChainOfThought surfaces missing reasoning as a structured DspyError") {
    val sig = TypedSignature.of[TcotSummarizeSpec]
    val adapter = new NoReasoningAdapter(Map("summary" -> "summary without reasoning"))
    RuntimeEnvironment.withSettings(settings(adapter)) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = TypedChainOfThought(sig).run((document = "..."))
      result match
        case Left(_: NotFoundError) => ()  // expected
        case Left(other)            => fail(s"expected NotFoundError, got: $other")
        case Right(tp)              => fail(s"expected failure, got: $tp")
    }
  }

  test("TypedChainOfThought surfaces non-string reasoning as a ValidationError") {
    val sig = TypedSignature.of[TcotSummarizeSpec]
    // Adapter emits reasoning as an Int -- decoding should reject it.
    val brokenAdapter = new Adapter:
      val name = "broken"
      def format(invocation: AdapterInvocation)(using RuntimeContext) =
        Right(FormattedPrompt(messages = Vector(
          Message(role = MessageRole.User, text = Some("hi"))
        )))
      def parse(signature: SignatureSchema, output: LmOutput)(using RuntimeContext) =
        Right(ParsedOutput(values = Map(
          "reasoning" -> 42,   // wrong type
          "summary"   -> "ok"
        )))
    RuntimeEnvironment.withSettings(settings(brokenAdapter)) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = TypedChainOfThought(sig).run((document = "..."))
      assert(result.isLeft, s"expected failure, got: $result")
      result match
        case Left(_: ValidationError) => ()
        case other => fail(s"expected ValidationError, got: $other")
    }
  }

  // Missing-input defensive check is shared with TypedPredict and exercised
  // in TypedPredictSuite -- not duplicated here.
