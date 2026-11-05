package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.PythonText
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.data.Example
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import munit.FunSuite
import zio.blocks.schema.DynamicValue

/** The type text of a field outside the scope of the golden prompts (decision D3, the boundary rule). The adapters
  * compute the type text from the data of the port only:
  *   - the type name is `str`, `int`, `float`, `bool`, `dict` (a [[TypeRef.json]] field), `list` (a [[TypeRef.list]]
  *     field), `History`, or `ToolCalls`; an other [[TypeRef]] shows its `repr`;
  *   - a field with enum values shows `str` and the note `must exactly match (no extra characters) one of: a; b`;
  *   - an optional field shows the text of its type;
  *   - a structured field (`dict`, `list`, or a `repr` that starts with `list[`) has the note `must adhere to the JSON
  *     schema: <schema>`. The schema is the property of the field in the output JSON schema of the invocation. When the
  *     property uses `$defs` entries of the output schema, a `$defs` key at the end holds them, and the entries that
  *     they use, in the order of the output schema. With no such property, it is `{"type": "array", "items": {}}` for a
  *     list and `{"type": "object", "additionalProperties": true}` for a dict. The JSON text has the separators `", "`
  *     and `": "`, and the keys stay in the order of the schema;
  *   - an other type has no note.
  *
  * The output reminder has the hint `(must be formatted as a valid Python <name>)` for each type name except `str`.
  */
class PortTypeTextSuite extends FunSuite:

  private final class StubLm extends LanguageModel:
    override val id: String                                                                    = "stub"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("stub", "unused in adapter tests"))

  private def parse(dsl: String): SignatureLayout = SignatureDsl.parse(dsl).fold(error => fail(error.message), identity)

  private def layoutOf(outputs: FieldSpec*): SignatureLayout =
    SignatureLayout
      .create(name = "S", inputFields = Vector(FieldSpec("question")), outputFields = outputs.toVector)
      .fold(error => fail(error.message), identity)

  /** The system text and the text of the current request. */
  private def texts(
      adapter         : Adapter,
      layout          : SignatureLayout,
      outputJsonSchema: Option[String] = None
  ): (String, String) =
    given RuntimeContext = RuntimeContext(lm = Some(StubLm()))
    val invocation       = AdapterInvocation(
      layout = layout,
      demos = Vector.empty,
      inputs = Example(values = DynamicValues.record("question" := "Q?"), inputKeys = Set("question")),
      request = LmRequest(model = "stub"),
      outputJsonSchema = outputJsonSchema.map(text =>
        PythonText.parseJson(text) match
          case Right(record: DynamicValue.Record) => record
          case other                              => fail(s"not a JSON object: $other")
      )
    )
    adapter.format(invocation) match
      case Right(prompt) => (prompt.messages.head.text.getOrElse(""), prompt.messages.last.text.getOrElse(""))
      case Left(error)   => fail(s"format failed: $error")

  private val listNote  = """must adhere to the JSON schema: {"type": "array", "items": {}}"""
  private val note      = "        # note: the value you produce "
  private val exactlyAB = "must exactly match (no extra characters) one of: a; b"

  test("a list type with an item type shows its token and the schema of a list") {
    val (system, user) = texts(ChatAdapter(), parse("question -> items: list[str]"))
    assert(system.contains("Your output fields are:\n1. `items` (list[str]):\nAll interactions"), system)
    assert(system.contains(s"[[ ## items ## ]]\n{items}$note$listNote\n\n[[ ## completed ## ]]"), system)
    assert(user.contains("`[[ ## items ## ]]` (must be formatted as a valid Python list[str])"), user)
  }

  test("a field with enum values shows str and the note of a Literal") {
    val (system, user) = texts(ChatAdapter(), layoutOf(FieldSpec("kind", enumValues = Vector("a", "b"))))
    assert(system.contains("Your output fields are:\n1. `kind` (str):\nAll interactions"), system)
    assert(system.contains(s"[[ ## kind ## ]]\n{kind}$note$exactlyAB\n\n[[ ## completed ## ]]"), system)
    assert(user.contains("starting with the field `[[ ## kind ## ]]`, and then ending with the marker"), user)
  }

  test("a Literal type of a string signature gives a string field with the enum values") {
    val layout =
      parse("""question -> kind: Literal['a', 'b'], other: Literal["x", "y"], maybe: Optional[Literal['a', 'b']]""")
    assertEquals(
      layout.outputFields.map(field => (field.name, field.typeRef, field.enumValues, field.optional)),
      Vector(
        ("kind", TypeRef.string, Vector("a", "b"), false),
        ("other", TypeRef.string, Vector("x", "y"), false),
        ("maybe", TypeRef.string, Vector("a", "b"), true)
      )
    )
    val (system, _) = texts(ChatAdapter(), layout)
    assert(system.contains(s"{kind}$note$exactlyAB"), system)
  }

  test("an optional field shows the text of its type") {
    val (system, user) = texts(ChatAdapter(), parse("question -> n: Optional[int]"))
    assert(system.contains("1. `n` (int):\nAll interactions"), system)
    assert(system.contains(s"{n}${note}must be a single int value"), system)
    assert(user.contains("`[[ ## n ## ]]` (must be formatted as a valid Python int)"), user)
  }

  test("a type that the port does not know shows its token and no note") {
    val (system, _) = texts(ChatAdapter(), parse("question -> answer: Person"))
    assert(system.contains("1. `answer` (person):\nAll interactions"), system)
    assert(system.contains("[[ ## answer ## ]]\n{answer}\n\n[[ ## completed ## ]]"), system)
  }

  test("a structured field takes the schema of its property in the output schema") {
    val schema =
      """{"type": "object", "properties": {"address": {"type": "object", "properties": {"street": {"type": "string"}}, "title": "Address"}, "tags": {"type": "array", "items": {"type": "string"}}}}"""
    val layout =
      layoutOf(FieldSpec("address", TypeRef.json), FieldSpec("tags", TypeRef.list), FieldSpec("free", TypeRef.json))
    val (system, _) = texts(ChatAdapter(), layout, Some(schema))
    assert(
      system.contains(
        s"""{address}${note}must adhere to the JSON schema: {"type": "object", "properties": {"street": {"type": "string"}}, "title": "Address"}"""
      ),
      system
    )
    assert(
      system.contains(
        s"""{tags}${note}must adhere to the JSON schema: {"type": "array", "items": {"type": "string"}}"""
      ),
      system
    )
    assert(
      system.contains(
        s"""{free}${note}must adhere to the JSON schema: {"type": "object", "additionalProperties": true}"""
      ),
      system
    )
    assert(!system.contains("conform to this JSON schema"), system)
  }

  test("the schema of a field has the $defs entries that it uses, in the order of the output schema") {
    val schema =
      """{"$defs": {"A": {"type": "object", "properties": {"b": {"$ref": "#/$defs/B"}}}, "B": {"type": "string", "title": "é"}, "C": {"type": "integer"}}, "type": "object", "properties": {"a": {"$ref": "#/$defs/A"}}}"""
    val (system, _) = texts(ChatAdapter(), layoutOf(FieldSpec("a", TypeRef.json)), Some(schema))
    assert(
      system.contains(
        s"""{a}${note}must adhere to the JSON schema: {"$$ref": "#/$$defs/A", "$$defs": {"A": {"type": "object", "properties": {"b": {"$$ref": "#/$$defs/B"}}}, "B": {"type": "string", "title": "é"}}}"""
      ),
      system
    )
  }

  test("the JSON adapter puts the same notes in its output structure") {
    val (system, _) = texts(JSONAdapter(), parse("question -> items: list[str]"))
    assert(
      system.contains(
        """"items": "{items}        # note: the value you produce must adhere to the JSON schema: {\"type\": \"array\", \"items\": {}}""""
      ),
      system
    )
  }

  test("the XML adapter asks for a list output as JSON text inside the tag (decision A6)") {
    val (system, user) = texts(XMLAdapter(), parse("question -> items: list[str]"))
    assert(system.contains(s"<items>\n{items}$note$listNote\n</items>"), system)
    assertEquals(
      user,
      "<question>\nQ?\n</question>\n\nRespond with the corresponding output fields wrapped in XML tags `<items>`."
    )
  }
