package dspy4s.programs

import zio.blocks.schema.Schema

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, FormattedPrompt, ParsedOutput}
import dspy4s.core.contracts.{
  :=, DspyError, DynamicValues, NotFoundError, RuntimeContext, SignatureLayout,
  ValidationError
}
import zio.blocks.schema.DynamicValue
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{
  LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, LmUsage,
  Message, MessageRole
}
import dspy4s.typed.{InputField, OutputField, Spec, Signature}
import scala.NamedTuple
import munit.FunSuite

// Top-level spec traits (Mirror derivation requires top-level types).
trait TcotSummarizeSpec extends Spec:
  def document: InputField[String]
  def summary:  OutputField[String]

trait TcotMultiOutputSpec extends Spec:
  def question: InputField[String]
  def answer:   OutputField[String]
  def score:    OutputField[Double]

// Output that ALREADY declares `reasoning` -- exercises the idempotent path
// (no second reasoning field added at the layout or the type level).
trait TcotHasReasoningSpec extends Spec:
  def question:  InputField[String]
  def reasoning: OutputField[String]
  def answer:    OutputField[String]

// Case-class I/O fixtures for the negative-path test that exercises
// the case-class-output rejection in ChainOfThought.
case class TcotCaseInput(document: String) derives Schema
case class TcotCaseOutput(summary: String) derives Schema

class ChainOfThoughtSuite extends FunSuite:

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)

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

    override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext)
        : Either[DspyError, ParsedOutput] =
      Right(ParsedOutput(values = DynamicValues.recordFromEntries(
        (baseValues + ("reasoning" -> reasoning)).toSeq.map((k, v) => k -> DynamicValues.fromAny(v))
      )))

  /** Adapter that emits the base fields but FORGETS to emit `reasoning`.
    * Used to verify ChainOfThought surfaces a missing-reasoning
    * error rather than silently dropping the field. */
  private class NoReasoningAdapter(baseValues: Map[String, Any]) extends Adapter:
    override val name: String = "no-reasoning"
    override def format(invocation: AdapterInvocation)(using RuntimeContext)
        : Either[DspyError, FormattedPrompt] =
      Right(FormattedPrompt(messages = Vector(
        Message(role = MessageRole.User, text = Some("hi"))
      )))
    override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext)
        : Either[DspyError, ParsedOutput] =
      Right(ParsedOutput(values = DynamicValues.recordFromEntries(baseValues.toSeq.map((k, v) => k -> DynamicValues.fromAny(v)))))

  private object FixedLm extends LanguageModel:
    override val id: String   = "fixed-lm"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(
        outputs = Vector(LmOutput(text = "lm output", metadata = DynamicValues.record())),
        usage   = Some(LmUsage(totalTokens = 10, promptTokens = 4, completionTokens = 6))
      ))

  private def settings(adapter: Adapter): RuntimeContext = RuntimeContext(
    lm = Some(FixedLm),
    adapter = Some(adapter)
  )

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  // ── Happy path ──────────────────────────────────────────────────────────

  test("ChainOfThought.augmentLayout prepends reasoning to the runtime output signature") {
    val sig = Signature.of[TcotSummarizeSpec]
    val layout = ChainOfThought.augmentLayout(sig.layout).toOption.get

    assertEquals(layout.outputFields.map(_.name), Vector("reasoning", "summary"))
  }

  test("ChainOfThought.run returns a typed prediction with reasoning prepended") {
    val sig = Signature.of[TcotSummarizeSpec]
    val adapter = new ScriptedAdapter(
      reasoning  = "The text is about football transfers; condense the key facts.",
      baseValues = Map("summary" -> "Lee signed a new contract with Barnsley.")
    )
    RuntimeEnvironment.withSettings(settings(adapter)) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = ChainOfThought(sig).apply((document = "..."))
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

  test("ChainOfThought preserves declaration order and types for multi-output signatures") {
    val sig = Signature.of[TcotMultiOutputSpec]
    val adapter = new ScriptedAdapter(
      reasoning  = "Paris is the capital because of historical and political factors.",
      baseValues = Map("answer" -> "Paris", "score" -> 0.95)
    )
    RuntimeEnvironment.withSettings(settings(adapter)) {
      given RuntimeContext = RuntimeEnvironment.current
      val tp = ChainOfThought(sig).apply((question = "Capital of France?")).toOption.get
      val r: String = tp.output.reasoning
      val a: String = tp.output.answer
      val s: Double = tp.output.score
      assertEquals(r, "Paris is the capital because of historical and political factors.")
      assertEquals(a, "Paris")
      assertEquals(s, 0.95)
    }
  }

  test("ChainOfThought is idempotent: an output that already declares `reasoning` gets no second one") {
    val sig = Signature.of[TcotHasReasoningSpec]
    // layout-level: reasoning is not duplicated
    val layout = ChainOfThought.augmentLayout(sig.layout).toOption.get
    assertEquals(layout.outputFields.map(_.name), Vector("reasoning", "answer"))

    // type/value-level: the output named tuple has exactly the two declared fields (reasoning once),
    // not (reasoning, reasoning, answer)
    val adapter = new ScriptedAdapter(
      reasoning  = "let us reason",
      baseValues = Map("answer" -> "Paris")
    )
    RuntimeEnvironment.withSettings(settings(adapter)) {
      given RuntimeContext = RuntimeEnvironment.current
      val tp = ChainOfThought(sig).apply((question = "Capital of France?")).toOption.get
      assertEquals(NamedTuple.toTuple(tp.output).size, 2)
      assertEquals(tp.output.reasoning, "let us reason")
      assertEquals(tp.output.answer,    "Paris")
    }
  }

  // ── Raw prediction is preserved ─────────────────────────────────────────

  test("ChainOfThought preserves the raw DynamicPrediction (including lmUsage)") {
    val sig = Signature.of[TcotSummarizeSpec]
    val adapter = new ScriptedAdapter(
      reasoning  = "short reasoning",
      baseValues = Map("summary" -> "short summary")
    )
    RuntimeEnvironment.withSettings(settings(adapter)) {
      given RuntimeContext = RuntimeEnvironment.current
      val tp = ChainOfThought(sig).apply((document = "...")).toOption.get
      assertEquals(tp.raw.lmUsage.flatMap(_.get("total_tokens")), Some(10L))
      assertEquals(tp.raw.asString("reasoning"), Right("short reasoning"))
      assertEquals(tp.raw.asString("summary"),   Right("short summary"))
    }
  }

  // ── Failure modes ───────────────────────────────────────────────────────

  test("ChainOfThought surfaces missing reasoning as a structured DspyError") {
    val sig = Signature.of[TcotSummarizeSpec]
    val adapter = new NoReasoningAdapter(Map("summary" -> "summary without reasoning"))
    RuntimeEnvironment.withSettings(settings(adapter)) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = ChainOfThought(sig).apply((document = "..."))
      result match
        case Left(_: NotFoundError) => ()  // expected
        case Left(other)            => fail(s"expected NotFoundError, got: $other")
        case Right(tp)              => fail(s"expected failure, got: $tp")
    }
  }

  test("ChainOfThought surfaces non-string reasoning as a ValidationError") {
    val sig = Signature.of[TcotSummarizeSpec]
    // Adapter emits reasoning as an Int -- decoding should reject it.
    val brokenAdapter = new Adapter:
      val name = "broken"
      def format(invocation: AdapterInvocation)(using RuntimeContext) =
        Right(FormattedPrompt(messages = Vector(
          Message(role = MessageRole.User, text = Some("hi"))
        )))
      def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext) =
        Right(ParsedOutput(values = rec(
          "reasoning" := 42,   // wrong type
          "summary"   := "ok"
        )))
    RuntimeEnvironment.withSettings(settings(brokenAdapter)) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = ChainOfThought(sig).apply((document = "..."))
      assert(result.isLeft, s"expected failure, got: $result")
      result match
        case Left(_: ValidationError) => ()
        case other => fail(s"expected ValidationError, got: $other")
    }
  }

  test("ChainOfThought supports case-class outputs, returning a named tuple with reasoning prepended") {
    // `Signature.derived` produces a case-class output. CoT normalizes it to its named-tuple view
    // (NamedTuple.From) and prepends `reasoning`, so the result is `(reasoning, summary)` -- a named tuple,
    // not the original `TcotCaseOutput` nominal type (that type can't be synthesized with an extra field).
    val sig = Signature.derived[TcotCaseInput, TcotCaseOutput]("CaseClassCot")
    val adapter = new ScriptedAdapter(
      reasoning  = "the summary condenses the document",
      baseValues = Map("summary" -> "a concise summary")
    )
    RuntimeEnvironment.withSettings(settings(adapter)) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = ChainOfThought(sig).apply(TcotCaseInput("..."))
      result match
        case Right(tp) =>
          val reasoning: String = tp.output.reasoning
          val summary:   String = tp.output.summary
          assertEquals(reasoning, "the summary condenses the document")
          assertEquals(summary,   "a concise summary")
        case Left(err) => fail(s"expected success, got: $err")
    }
  }

  // Missing-input defensive check is shared with Predict and exercised
  // in TypedPredictSuite -- not duplicated here.
