package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.core.contracts.{DspyError, DynamicValues, Example, RuntimeContext, :=}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.typed.Signature
import zio.blocks.schema.Schema
import munit.FunSuite

// Top-level fixtures (Mirror + Schema derivation).
case class TagInput(text: String) derives Schema
case class TagItem(name: String, score: Double) derives Schema
case class TagOutput(tags: List[String], items: List[TagItem], amount: Option[Double]) derives Schema

/** Reproduces the second email_extraction failure: structured output fields — `List[String]`,
  * `List[case class]`, and `Option[Double]` — must decode under the default `ChatAdapter`.
  *
  * Today `ChatAdapter.coerce` leaves every non-scalar (`json`) field as a raw `String` primitive instead of
  * JSON-parsing it, so list/record fields fail with "Expected a sequence", and `Option` fields have no
  * normalize support at all (zio-blocks needs a `Some`/`None` Variant shape), failing with
  * "Missing field 'value' at: .amount.when[Some]". */
class StructuredOutputSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  /** A scripted LM returning ChatAdapter marker-framed output: a JSON array for each list field, a JSON
    * object array for the record list, and a bare number for the optional field. */
  private final class ScriptedLm(text: String) extends LanguageModel:
    override val id: String   = "scripted"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = text))))

  private val completion =
    """[[ ## tags ## ]]
      |["urgent", "billing"]
      |
      |[[ ## items ## ]]
      |[{"name": "MacBook Pro", "score": 0.92}]
      |
      |[[ ## amount ## ]]
      |2399.0
      |
      |[[ ## completed ## ]]""".stripMargin

  private val signature = Signature.derived[TagInput, TagOutput](name = "Tag")

  test("ChatAdapter decodes List, List[record], and Option output fields") {
    RuntimeEnvironment.withSettings(
      RuntimeContext(lm = Some(new ScriptedLm(completion)), adapter = Some(ChatAdapter()))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val out = Predict(signature).apply(TagInput("classify this")).map(_.output)
      assertEquals(out.map(_.tags),   Right(List("urgent", "billing")))
      assertEquals(out.map(_.items),  Right(List(TagItem("MacBook Pro", 0.92))))
      assertEquals(out.map(_.amount), Right(Some(2399.0)))
    }
  }

  test("ChatAdapter conveys the nested object structure of a List[record] output field") {
    // Without the nested schema, the LM doesn't know `items` is a list of {name, score} objects and emits
    // a list of strings → "Expected a record at: .items.each". The prompt must surface the nested field names.
    val invocation = AdapterInvocation(
      layout           = signature.layout,
      demos            = Vector.empty,
      inputs           = Example(values = DynamicValues.recordFromEntries(Seq("text" := "x")), inputKeys = Set("text")),
      request          = LmRequest(model = "openai/test", mode = LmMode.Chat),
      outputJsonSchema = signature.outputShape.jsonSchemaString
    )
    given RuntimeContext = RuntimeEnvironment.current
    val system = ChatAdapter().format(invocation).toOption.get.messages.head.text.get
    assert(
      system.contains("score"),
      s"expected the nested field name 'score' (from items: List[TagItem]) in the ChatAdapter system prompt " +
        s"so the LM emits objects, not strings. System prompt was:\n$system"
    )
  }

  test("ChainOfThought conveys the nested output schema to the LM under ChatAdapter") {
    // The email_extraction example builds every step with ChainOfThought. CoT's augmented output Shape must
    // still carry the base output JSON schema, or the nested structure (items: List[TagItem]) never reaches
    // the LM and it emits a list of strings → "Expected a record at: .items.each".
    val captured = scala.collection.mutable.ArrayBuffer.empty[String]
    val capturingLm = new LanguageModel:
      override val id: String   = "capturing"
      override val mode: LmMode = LmMode.Chat
      override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
        request.messages.foreach(m => m.text.foreach(captured += _))
        Right(LmResponse(outputs = Vector(LmOutput(text = ""))))

    RuntimeEnvironment.withSettings(
      RuntimeContext(lm = Some(capturingLm), adapter = Some(ChatAdapter()))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val cot = ChainOfThought(signature)
      val _   = cot.apply(TagInput("classify this")) // result ignored; we assert on the captured prompt
    }
    val prompt = captured.mkString("\n")
    assert(
      prompt.contains("score"),
      s"ChainOfThought's inner predict must convey the nested schema (items: List[TagItem]) to the LM; " +
        s"'score' is missing from the prompt:\n$prompt"
    )
  }

  test("ChatAdapter decodes an absent Option output field as None") {
    val noneCompletion =
      """[[ ## tags ## ]]
        |[]
        |
        |[[ ## items ## ]]
        |[]
        |
        |[[ ## amount ## ]]
        |null
        |
        |[[ ## completed ## ]]""".stripMargin
    RuntimeEnvironment.withSettings(
      RuntimeContext(lm = Some(new ScriptedLm(noneCompletion)), adapter = Some(ChatAdapter()))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val out = Predict(signature).apply(TagInput("nothing here")).map(_.output)
      assertEquals(out.map(_.amount), Right(None))
      assertEquals(out.map(_.tags),   Right(Nil))
    }
  }
