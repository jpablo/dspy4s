package dspy4s.adapters

import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import munit.FunSuite
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

/** G-7 v1: JSONAdapter emits an OpenAI `response_format` into `FormattedPrompt.requestOptions` when the ambient
  * LM declares `supportsResponseSchema` and a JSON Schema is available. */
class JSONAdapterResponseFormatSuite extends FunSuite:

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)

  /** Navigate into a nested `DynamicValue.Record` by field name. */
  private def field(dv: DynamicValue, name: String): Option[DynamicValue] = dv match
    case r: DynamicValue.Record => DynamicValues.recordGet(r, name)
    case _                      => None

  private def stringField(dv: DynamicValue, name: String): Option[String] =
    field(dv, name).collect { case DynamicValue.Primitive(PrimitiveValue.String(s)) => s }

  /** Minimal stub LM whose only meaningful trait is its capability flag. */
  private final class StubLm(override val supportsResponseSchema: Boolean) extends LanguageModel:
    override val id: String   = "stub"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector.empty))

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  private val schemaString =
    """{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"]}"""

  private def invocationWithSchema(schema: Option[String]): AdapterInvocation =
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    AdapterInvocation(
      layout           = signature,
      demos            = Vector.empty,
      inputs           = Example(values = rec("question" := "x"), inputKeys = Set("question")),
      request          = LmRequest(model = "openai/test", mode = LmMode.Chat),
      outputJsonSchema = schema
    )

  test("response_format is emitted when the LM supports response schema and a schema is present") {
    given RuntimeContext = RuntimeContext(lm = Some(new StubLm(supportsResponseSchema = true)))
    val formatted = JSONAdapter().format(invocationWithSchema(Some(schemaString)))
    assert(formatted.isRight, s"expected Right, got $formatted")
    val opts = formatted.toOption.get.requestOptions
    val rf = DynamicValues.recordGet(opts, "response_format")
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
    // strict must be false
    assertEquals(
      field(js.get, "strict").collect { case DynamicValue.Primitive(PrimitiveValue.Boolean(b)) => b },
      Some(false)
    )
  }

  test("response_format embeds the sanitized signature name") {
    given RuntimeContext = RuntimeContext(lm = Some(new StubLm(supportsResponseSchema = true)))
    val formatted = JSONAdapter().format(invocationWithSchema(Some(schemaString)))
    val opts = formatted.toOption.get.requestOptions
    val name = DynamicValues.recordGet(opts, "response_format")
      .flatMap(field(_, "json_schema"))
      .flatMap(stringField(_, "name"))
    assert(name.isDefined, "expected a name field")
    assert(
      name.exists(_.matches("^[a-zA-Z0-9_-]+$")),
      s"expected sanitized name matching ^[a-zA-Z0-9_-]+$$, got: $name"
    )
  }

  test("no response_format when the LM does not support response schema") {
    given RuntimeContext = RuntimeContext(lm = Some(new StubLm(supportsResponseSchema = false)))
    val formatted = JSONAdapter().format(invocationWithSchema(Some(schemaString)))
    assertEquals(DynamicValues.recordEntries(formatted.toOption.get.requestOptions), Vector.empty)
  }

  test("no response_format when no JSON schema is present (even if supported)") {
    given RuntimeContext = RuntimeContext(lm = Some(new StubLm(supportsResponseSchema = true)))
    val formatted = JSONAdapter().format(invocationWithSchema(None))
    assertEquals(DynamicValues.recordEntries(formatted.toOption.get.requestOptions), Vector.empty)
  }

  test("no response_format when there is no ambient LM") {
    given RuntimeContext = RuntimeContext()
    val formatted = JSONAdapter().format(invocationWithSchema(Some(schemaString)))
    assertEquals(DynamicValues.recordEntries(formatted.toOption.get.requestOptions), Vector.empty)
  }

  test("malformed schema string yields no response_format but format still succeeds with prose") {
    given RuntimeContext = RuntimeContext(lm = Some(new StubLm(supportsResponseSchema = true)))
    val formatted = JSONAdapter().format(invocationWithSchema(Some("{not valid json")))
    assert(formatted.isRight, s"format must not fail over a malformed schema, got: $formatted")
    assertEquals(DynamicValues.recordEntries(formatted.toOption.get.requestOptions), Vector.empty)
    // prose fallback: the malformed schema string is still inlined into the system message
    assert(
      formatted.toOption.get.messages.head.text.exists(_.contains("{not valid json")),
      "expected the (malformed) schema still inlined as prose"
    )
  }
