package dspy4s.adapters

import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
import dspy4s.core.contracts.FieldConstraints
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.PythonText
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import munit.FunSuite
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

/** G-7 v1: JSONAdapter emits an OpenAI `response_format` into `FormattedPrompt.requestOptions` when the ambient LM
  * declares `supportsResponseSchema` and a JSON Schema is available.
  */
class JSONAdapterResponseFormatSuite extends FunSuite:

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)

  /** Navigate into a nested `DynamicValue.Record` by field name. */
  private def field(dv: DynamicValue, name: String): Option[DynamicValue] =
    dv match
      case r: DynamicValue.Record => DynamicValues.recordGet(r, name)
      case _                      => None

  private def stringField(dv: DynamicValue, name: String): Option[String] =
    field(dv, name).collect { case DynamicValue.Primitive(PrimitiveValue.String(s)) => s }

  /** Minimal stub LM whose only meaningful trait is its capability flag. */
  private final class StubLm(override val supportsResponseSchema: Boolean) extends LanguageModel:
    override val id: String                                                                    = "stub"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector.empty))

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** The JSON object of `text`, as the output JSON schema of an invocation. */
  private def jsonObject(text: String): DynamicValue.Record =
    PythonText.parseJson(text) match
      case Right(record: DynamicValue.Record) => record
      case other                              => fail(s"not a JSON object: $other")

  private val schemaString = """{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"]}"""

  private def invocationWithSchema(schema: Option[String]): AdapterInvocation =
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    AdapterInvocation(
      layout = signature,
      demos = Vector.empty,
      inputs = Example(values = rec("question" := "x"), inputKeys = Set("question")),
      request = LmRequest(model = "openai/test"),
      outputJsonSchema = schema.map(jsonObject)
    )

  test("response_format is emitted when the LM supports response schema and a schema is present") {
    given RuntimeContext = RuntimeContext(lm = Some(new StubLm(supportsResponseSchema = true)))
    val formatted        = JSONAdapter().format(invocationWithSchema(Some(schemaString)))
    assert(formatted.isRight, s"expected Right, got $formatted")
    val opts = formatted.toOption.get.requestOptions
    val rf   = DynamicValues.recordGet(opts, "response_format")
    assert(rf.isDefined, s"expected response_format in requestOptions, got: $opts")
    val rfv = rf.get
    assertEquals(stringField(rfv, "type"), Some("json_schema"))
    val js = field(rfv, "json_schema")
    assert(js.isDefined, s"expected json_schema object, got: $rfv")
    assert(stringField(js.get, "name").isDefined, s"expected a name, got: ${js.get}")
    val embeddedSchema = field(js.get, "schema")
    assert(embeddedSchema.isDefined, s"expected embedded schema object, got: ${js.get}")
    assertEquals(stringField(embeddedSchema.get, "type"), Some("object"))
    assert(field(embeddedSchema.get, "properties").isDefined, s"expected properties in embedded schema")
    // Strict mode, as upstream (L4).
    assertEquals(
      field(js.get, "strict").collect { case DynamicValue.Primitive(PrimitiveValue.Boolean(b)) => b },
      Some(true)
    )
  }

  test("response_format embeds field constraints as JSON-Schema keywords on the matching property") {
    given RuntimeContext = RuntimeContext(lm = Some(new StubLm(supportsResponseSchema = true)))
    val layout           = SignatureLayout.create(
      name = "Scored",
      inputFields = Vector(FieldSpec("question")),
      outputFields = Vector(
        FieldSpec(
          name = "score",
          typeRef = TypeRef.int,
          constraints = Vector(FieldConstraints.gt(0), FieldConstraints.maxLength(10))
        )
      )
    ).toOption.get
    val schema     = """{"type":"object","properties":{"score":{"type":"integer"}},"required":["score"]}"""
    val invocation = AdapterInvocation(
      layout = layout,
      demos = Vector.empty,
      inputs = Example(values = rec("question" := "x"), inputKeys = Set("question")),
      request = LmRequest(model = "openai/test"),
      outputJsonSchema = Some(jsonObject(schema))
    )

    val opts      = JSONAdapter().format(invocation).toOption.get.requestOptions
    val scoreProp = DynamicValues.recordGet(opts, "response_format")
      .flatMap(field(_, "json_schema")).flatMap(field(_, "schema"))
      .flatMap(field(_, "properties")).flatMap(field(_, "score"))
    assert(scoreProp.isDefined, s"expected a `score` property, got opts: $opts")
    assertEquals(
      field(scoreProp.get, "exclusiveMinimum").collect { case DynamicValue.Primitive(PrimitiveValue.Long(n)) => n },
      Some(0L)
    )
    assertEquals(
      field(scoreProp.get, "maxLength").collect { case DynamicValue.Primitive(PrimitiveValue.Int(n)) => n },
      Some(10)
    )
    // The original property keyword is preserved alongside the injected constraints.
    assertEquals(stringField(scoreProp.get, "type"), Some("integer"))
  }

  test("response_format has the upstream schema name") {
    given RuntimeContext = RuntimeContext(lm = Some(new StubLm(supportsResponseSchema = true)))
    val formatted        = JSONAdapter().format(invocationWithSchema(Some(schemaString)))
    val opts             = formatted.toOption.get.requestOptions
    val name             = DynamicValues.recordGet(opts, "response_format")
      .flatMap(field(_, "json_schema"))
      .flatMap(stringField(_, "name"))
    assertEquals(name, Some("DSPyProgramOutputs"))
  }

  test("no response_format when the LM does not support response schema") {
    given RuntimeContext = RuntimeContext(lm = Some(new StubLm(supportsResponseSchema = false)))
    val formatted        = JSONAdapter().format(invocationWithSchema(Some(schemaString)))
    assertEquals(DynamicValues.recordEntries(formatted.toOption.get.requestOptions), Vector.empty)
  }

  test("with no typed schema, response_format has a schema built from the field types") {
    given RuntimeContext = RuntimeContext(lm = Some(new StubLm(supportsResponseSchema = true)))
    val formatted        = JSONAdapter().format(invocationWithSchema(None))
    val rf               = DynamicValues.recordGet(formatted.toOption.get.requestOptions, "response_format")
    assertEquals(rf.flatMap(stringField(_, "type")), Some("json_schema"))
    val properties = rf.flatMap(field(_, "json_schema")).flatMap(field(_, "schema")).flatMap(field(_, "properties"))
    assert(properties.flatMap(field(_, "answer")).isDefined, s"expected an `answer` property, got: $rf")
  }

  test("no response_format when there is no ambient LM") {
    given RuntimeContext = RuntimeContext()
    val formatted        = JSONAdapter().format(invocationWithSchema(Some(schemaString)))
    assertEquals(DynamicValues.recordEntries(formatted.toOption.get.requestOptions), Vector.empty)
  }
