package dspy4s.programs

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.{ChatAdapter, JSONAdapter, XMLAdapter}
import dspy4s.core.contracts.{DspyError, DynamicValues, PythonText, RuntimeContext}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.programs.strategies.{ChainOfThought, Predict}
import dspy4s.signatures.{Shape, Signature}
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, Modifier, Schema}

import scala.collection.mutable.ArrayBuffer

// Top-level fixtures (Mirror derivation needs them outside the suite).
@Modifier.discriminator("kind")
sealed trait AaShape
object AaShape:
  final case class Circle(radius: Double) extends AaShape
  case object Dot                         extends AaShape
  given Schema[AaShape] = Schema.derived

@Modifier.caseNaming("snake_case")
enum AaLevel:
  case VeryHigh, VeryLow
object AaLevel:
  given Schema[AaLevel] = Schema.derived

final case class AaIn(@Modifier.rename("the_question") question: String)
object AaIn:
  given Schema[AaIn] = Schema.derived

final case class AaOut(
    @Modifier.rename("full_name") name: String,
    @Modifier.rename("n") count       : Int,
    shape                             : AaShape,
    level                             : AaLevel
)
object AaOut:
  given Schema[AaOut] = Schema.derived

@Modifier.fieldNaming("snake_case")
final case class AaSnakeOut(firstName: String, lastSeen: Option[String])
object AaSnakeOut:
  given Schema[AaSnakeOut] = Schema.derived

final case class AaNamed(@Modifier.rename("full_name") name: String)
object AaNamed:
  given Schema[AaNamed] = Schema.derived

/** A typed signature with annotations: the prompt asks for the names of the annotations, the reply gives them, and the
  * typed output has the value after one model call, in each reply format.
  */
class AnnotatedOutputsSuite extends FunSuite:

  private class Script(replies: String*) extends LanguageModel:
    override val id: String              = "scripted"
    override val mode: LmMode            = LmMode.Chat
    val requests: ArrayBuffer[LmRequest] = ArrayBuffer.empty

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      requests += request
      Right(LmResponse(outputs = Vector(LmOutput(text = replies(requests.size - 1)))))

  private def withModel[A](model: LanguageModel, adapter: Adapter)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(model), adapter = Some(adapter))) {
      body(using RuntimeEnvironment.current)
    }

  private def signature: Signature[AaIn, AaOut] =
    Signature.derived[AaIn, AaOut](name = "Annotated", instructions = "Give the values.")

  private def expected: AaOut = AaOut("a", 3, AaShape.Circle(1.5), AaLevel.VeryLow)

  private def promptOf(request: LmRequest): String = request.messages.flatMap(_.text).mkString("\n")

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  test("the chat format gives each value after one call") {
    val model = Script(
      "[[ ## full_name ## ]]\na\n\n[[ ## n ## ]]\n3\n\n" +
        "[[ ## shape ## ]]\n{\"kind\": \"Circle\", \"radius\": 1.5}\n\n[[ ## level ## ]]\nvery_low\n\n[[ ## completed ## ]]"
    )
    assertEquals(withModel(model, ChatAdapter())(Predict(signature)(AaIn("q"))).map(_.output), Right(expected))
    assertEquals(model.requests.size, 1)
  }

  test("the JSON format gives each value after one call") {
    val model = Script(
      """{"full_name": "a", "n": 3, "shape": {"kind": "Circle", "radius": 1.5}, "level": "very_low"}"""
    )
    assertEquals(withModel(model, JSONAdapter())(Predict(signature)(AaIn("q"))).map(_.output), Right(expected))
    assertEquals(model.requests.size, 1)
  }

  test("the XML format gives each value after one call") {
    val model = Script(
      """<full_name>a</full_name><n>3</n><shape>{"kind": "Circle", "radius": 1.5}</shape><level>very_low</level>"""
    )
    assertEquals(withModel(model, XMLAdapter())(Predict(signature)(AaIn("q"))).map(_.output), Right(expected))
    assertEquals(model.requests.size, 1)
  }

  test("the prompt asks for the names of the annotations") {
    val model = Script("{}", "{}")
    val _     = withModel(model, ChatAdapter())(Predict(signature)(AaIn("q")))
    val text  = promptOf(model.requests.head)
    assert(text.contains("[[ ## full_name ## ]]"), text)
    assert(text.contains("[[ ## n ## ]]"), text)
    assert(text.contains("[[ ## the_question ## ]]"), text)
    assert(text.contains("one of: very_high; very_low"), text)
    assert(!text.contains("[[ ## name ## ]]"), text)
    assert(!text.contains("[[ ## question ## ]]"), text)
  }

  test("a naming strategy reaches the prompt and the output through a given schema") {
    val snake = Signature.fromShapes[AaIn, AaSnakeOut](
      "Snake",
      "Give the values.",
      Shape.derived[AaIn],
      Shape.derived[AaSnakeOut]
    )
    val model  = Script("[[ ## first_name ## ]]\na\n\n[[ ## last_seen ## ]]\nb\n\n[[ ## completed ## ]]")
    val result = withModel(model, ChatAdapter())(Predict(snake)(AaIn("q")))
    assertEquals(result.map(_.output), Right(AaSnakeOut("a", Some("b"))))
    assertEquals(model.requests.size, 1)
    assert(promptOf(model.requests.head).contains("[[ ## first_name ## ]]"), promptOf(model.requests.head))
  }

  test("the strict response format has the names of the annotations") {
    val model = new Script("""{"full_name": "a", "n": 3, "shape": {"kind": "Dot"}, "level": "very_high"}"""):
      override def supportsResponseSchema: Boolean = true
    val result = withModel(model, JSONAdapter())(Predict(signature)(AaIn("q")))
    assertEquals(result.map(_.output), Right(AaOut("a", 3, AaShape.Dot, AaLevel.VeryHigh)))
    val format = DynamicValues.recordGet(model.requests.head.options, "response_format")
      .fold("")(PythonText.json(_))
    assert(format.contains("json_schema"), format)
    assert(format.contains("\"full_name\""), format)
    assert(format.contains("\"kind\""), format)
    assert(format.contains("very_low"), format)
    assert(!format.contains("\"name\": {"), format)
  }

  test("a program with an added output field keeps the names of the annotations") {
    val model  = Script("[[ ## reasoning ## ]]\nr\n\n[[ ## full_name ## ]]\na\n\n[[ ## completed ## ]]")
    val base   = Signature.derived[AaIn, AaNamed](name = "Named", instructions = "Give the name.")
    val result = withModel(model, ChatAdapter()) {
      ChainOfThought(base)(AaIn("q")).map(_.output.name)
    }
    assertEquals(result, Right("a"))
    assertEquals(model.requests.size, 1)
  }

  test("a value of a typed demo shows the names of the annotations in the prompt") {
    val shape = Shape.derived[AaOut]
    val text  = PythonText.json(shape.encode(expected))
    assertEquals(
      text,
      """{"full_name": "a", "n": 3, "shape": {"kind": "Circle", "radius": 1.5}, "level": "very_low"}"""
    )
    val record = PythonText.parseJson(text) match
      case Right(found: DynamicValue.Record) => found
      case other                             => fail(s"expected a record, got $other")
    assertEquals(shape.decode(record), Right(expected))
  }
