package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{DspyError, RuntimeContext}
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
