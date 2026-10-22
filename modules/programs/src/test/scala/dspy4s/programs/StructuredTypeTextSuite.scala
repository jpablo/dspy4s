package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.core.contracts.{:=, DspyError, DynamicValues, RuntimeContext, RuntimeError}
import dspy4s.core.data.Example
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmRequest, LmResponse}
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.Schema

enum StColor derives Schema:
  case red, green

final case class StAddress(street: String, zip_code: Int) derives Schema
final case class StQuestion(question: String) derives Schema
final case class StAnswer(
    address: StAddress,
    words  : List[String],
    color  : StColor,
    note   : Option[String],
    count  : Option[Int],
    maybe  : Option[StAddress]
) derives Schema

/** The type text of the structured fields of a typed signature (decision D3, the boundary rule). Each structured field
  * shows the type name of the port and the schema of its property in the output schema of the signature. The rules are
  * in `PortTypeTextSuite`.
  */
class StructuredTypeTextSuite extends FunSuite:

  private final class StubLm extends LanguageModel:
    override val id: String                                                                    = "stub"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("stub", "unused in adapter tests"))

  private val signature = Signature.derived[StQuestion, StAnswer]("Structured")

  private val (system, user) =
    given RuntimeContext = RuntimeContext(lm = Some(StubLm()))
    val invocation       = AdapterInvocation(
      layout = signature.layout,
      demos = Vector.empty,
      inputs = Example(values = DynamicValues.record("question" := "Q?"), inputKeys = Set("question")),
      request = LmRequest(model = "stub"),
      outputJsonSchema = signature.outputShape.jsonSchemaString
    )
    ChatAdapter().format(invocation) match
      case Right(prompt) => (prompt.messages.head.text.getOrElse(""), prompt.messages.last.text.getOrElse(""))
      case Left(error)   => fail(s"format failed: $error")

  private val note    = "        # note: the value you produce "
  private val address =
    """{"properties": {"street": {"type": "string"}, "zip_code": {"type": "integer", "maximum": 2147483647, "minimum": -2147483648}}, "type": "object", "required": ["street", "zip_code"], "title": "StAddress"}"""

  test("each output field shows the type name of the port") {
    assert(
      system.contains(
        "Your output fields are:\n1. `address` (dict): \n2. `words` (list): \n3. `color` (str): \n4. `note` (str): \n" +
          "5. `count` (int): \n6. `maybe` (dict):\nAll interactions"
      ),
      system
    )
  }

  test("a record field and a list field take the schema of their property") {
    assert(system.contains(s"{address}${note}must adhere to the JSON schema: $address\n"), system)
    assert(
      system.contains(
        s"""{words}${note}must adhere to the JSON schema: {"items": {"type": "string"}, "type": ["array", "null"], "title": "List[String]"}\n"""
      ),
      system
    )
  }

  test("an enum field has the note of a Literal") {
    assert(system.contains(s"{color}${note}must exactly match (no extra characters) one of: red; green\n"), system)
  }

  test("an Option field shows the text of its element type") {
    assert(system.contains("[[ ## note ## ]]\n{note}\n\n"), system)
    assert(system.contains(s"{count}${note}must be a single int value\n"), system)
    assert(system.contains(s"{maybe}${note}must adhere to the JSON schema: {\"properties\": "), system)
    assert(user.contains("`[[ ## count ## ]]` (must be formatted as a valid Python int)"), user)
  }

  test("the system message has no block for the whole output schema") {
    assert(!system.contains("conform to this JSON schema"), system)
  }
