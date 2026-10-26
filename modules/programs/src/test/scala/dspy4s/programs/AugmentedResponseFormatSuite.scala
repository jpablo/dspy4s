package dspy4s.programs

import dspy4s.adapters.JSONAdapter
import dspy4s.core.contracts.{DspyError, DynamicValues, PythonText, RuntimeContext}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.programs.strategies.ChainOfThought
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.Schema

final case class ArQuestion(question: String) derives Schema
final case class ArAnswer(answer: String, confidence: Double) derives Schema

/** The strict `response_format` of a program that adds an output field must have that field. Before, a typed
  * `ChainOfThought` sent the schema of the base outputs, with no `reasoning` property and
  * `additionalProperties: false`, so a model in strict mode could not write the reasoning that the prompt asks for.
  */
class AugmentedResponseFormatSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** A model that takes a response schema, keeps each request, and gives a JSON reply. */
  private final class SchemaLm extends LanguageModel:
    @volatile var requests: Vector[LmRequest]                                                  = Vector.empty
    override val id: String                                                                    = "schema-lm"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsResponseSchema: Boolean                                               = true
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      requests = requests :+ request
      Right(LmResponse(outputs =
        Vector(LmOutput(text = """{"reasoning": "It is 4.", "answer": "4", "confidence": 0.9}"""))
      ))

  private def schemaText(request: LmRequest): String =
    DynamicValues.recordGet(
      request.options,
      "response_format"
    ).map(PythonText.json(_)).getOrElse(fail("no response_format"))

  test("a typed ChainOfThought sends a response schema with the reasoning field") {
    val lm     = SchemaLm()
    val result = RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(JSONAdapter()))) {
      ChainOfThought(Signature.derived[ArQuestion, ArAnswer]("QA"))(ArQuestion("2+2?"))(using
        RuntimeEnvironment.current
      )
    }
    assertEquals(result.map(_.output.answer), Right("4"))
    val text = schemaText(lm.requests.headOption.getOrElse(fail("no request")))
    assert(text.contains(""""reasoning""""), text)
    assert(text.contains(""""required": ["reasoning", "answer", "confidence"]"""), text)
  }
