package dspy4s.adapters

import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.internal.JsonDynamic
import dspy4s.adapters.internal.StrictJsonSchema
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.data.Example
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import munit.FunSuite
import zio.blocks.schema.DynamicValue

/** The strict response schema of the JSON adapter (upstream `JSONAdapter._prepare_response_format` in
  * `dspy/adapters/json_adapter.py`, and `_strict_json_schema` in `dspy/clients/legacy_requests.py`).
  */
class StrictResponseFormatSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private final class StubLm(functionCalling: Boolean) extends LanguageModel:
    override val id: String                                                                    = "stub"
    override val mode: LmMode                                                                  = LmMode.Chat
    override val supportsResponseSchema: Boolean                                               = true
    override def supportsFunctionCalling: Boolean                                              = functionCalling
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector.empty))

  private def json(text: String): ujson.Value = ujson.read(text)

  private def ujsonOf(value: DynamicValue): ujson.Value = JsonDynamic.toUjson(value)

  private def parsed(text: String): DynamicValue.Record =
    JsonDynamic.parse(text) match
      case Right(record: DynamicValue.Record) => record
      case other                              => fail(s"not a JSON object: $other")

  private def layoutOf(dsl: String): SignatureLayout =
    SignatureDsl.parse(dsl).fold(error => fail(s"bad signature: $error"), identity)

  private def layoutWith(outputs: FieldSpec*): SignatureLayout =
    SignatureLayout.create(name = "Probe", inputFields = Vector(FieldSpec("question")), outputFields = outputs.toVector)
      .fold(error => fail(s"bad layout: $error"), identity)

  /** The `response_format` that the JSON adapter gives for `layout` and `schema`, as JSON. */
  private def responseFormat(
      layout       : SignatureLayout,
      schema       : Option[String] = None,
      native       : Boolean        = false,
      functionCalls: Boolean        = false
  ): ujson.Value =
    given RuntimeContext = RuntimeContext(lm = Some(StubLm(functionCalls)))
    val invocation       = AdapterInvocation(
      layout = layout,
      demos = Vector.empty,
      inputs = Example(values = DynamicValues.record("question" := "x"), inputKeys = Set("question")),
      request = LmRequest(model = "stub", mode = LmMode.Chat),
      outputJsonSchema = schema
    )
    val options = JSONAdapter(useNativeFunctionCalling = native).format(invocation)
      .fold(error => fail(s"format failed: $error"), _.requestOptions)
    DynamicValues.recordGet(options, "response_format").map(ujsonOf).getOrElse(fail(s"no response_format: $options"))

  private def schemaOf(format: ujson.Value): ujson.Value =
    assertEquals(format("type").str, "json_schema", format.toString)
    format("json_schema")("schema")

  private val jsonMode: ujson.Value = json("""{"type": "json_object"}""")

  // Schemas that zio-blocks writes for typed outputs (see `ZioSchemaCodec.jsonSchemaForModel`).
  private val optionSchema: String =
    """{"properties":{"amount":{"type":["null","number"]},"note":{"type":"string"}},"type":"object","required":["note"],"title":"ZsOptionProbe"}"""

  private val nestedSchema: String =
    """{"properties":{"stage":{"properties":{"label":{"type":"string"},"steps":{"items":{"properties":{"action":{"type":"string"},"comment":{"type":["null","string"]}},"type":"object","required":["action"],"title":"ZsStep"},"type":["array","null"],"title":"Vector[ZsStep]"},"owner":{"type":["null","string"]}},"type":"object","required":["label"],"title":"ZsStage"},"summary":{"type":["null","string"]}},"type":"object","required":["stage"],"title":"ZsPipeline"}"""

  private val mapSchema: String =
    """{"properties":{"scores":{"additionalProperties":{"type":"integer"},"type":["object","null"],"title":"Map[String,Int]"},"kind":{"enum":["Bad","Good"],"title":"TmpKind"}},"type":"object","required":["kind"],"title":"TmpMapProbe"}"""

  // A recursive type is in `$defs`, and the root is a `$ref` to it (zio-blocks writes no `$ref`, so
  // `jsonSchemaForModel` cuts the cycle).
  private val recursiveSchema: String =
    """{"$ref":"#/$defs/ZsNode","$defs":{"ZsNode":{"properties":{"label":{"type":"string"},"children":{"items":{"$ref":"#/$defs/ZsNode"},"type":["array","null"],"title":"List[ZsNode]"}},"type":"object","required":["label"],"title":"ZsNode"}}}"""

  // ── Strict mode ─────────────────────────────────────────────────────────────────────────────────────────────

  test("a typed schema goes out strict, with the upstream name, each property required, and each object closed") {
    val format = responseFormat(layoutOf("question -> amount: float, note"), Some(optionSchema))
    assertEquals(format("json_schema")("strict"), ujson.Bool(true))
    assertEquals(format("json_schema")("name").str, StrictJsonSchema.Name)
    assertEquals(StrictJsonSchema.Name, "DSPyProgramOutputs")
    val schema = schemaOf(format)
    assertEquals(schema("required"), json("""["amount", "note"]"""))
    assertEquals(schema("additionalProperties"), ujson.Bool(false))
    // An `Option` field of zio-blocks already permits null.
    assertEquals(schema("properties")("amount"), json("""{"type": ["null", "number"]}"""))
  }

  test("strict shape reaches nested objects, the items of an array, and an object with a type list") {
    val schema = schemaOf(responseFormat(layoutOf("question -> stage: json, summary"), Some(nestedSchema)))
    val stage  = schema("properties")("stage")
    assertEquals(stage("required"), json("""["label", "steps", "owner"]"""))
    assertEquals(stage("additionalProperties"), ujson.Bool(false))
    val step = stage("properties")("steps")("items")
    assertEquals(step("required"), json("""["action", "comment"]"""))
    assertEquals(step("additionalProperties"), ujson.Bool(false))
  }

  test("a recursive typed schema goes out strict: the $ref at the root resolves, and the inner $ref stays") {
    val format = responseFormat(layoutOf("question -> label, children: list"), Some(recursiveSchema))
    assertEquals(format("json_schema")("strict"), ujson.Bool(true))
    val schema = schemaOf(format)
    assert(!schema.obj.contains("$ref"), schema.toString)
    assertEquals(schema("required"), json("""["label", "children"]"""))
    assertEquals(schema("additionalProperties"), ujson.Bool(false))
    assertEquals(schema("properties")("children")("items"), json("""{"$ref": "#/$defs/ZsNode"}"""))
    val definition = schema("$defs")("ZsNode")
    assertEquals(definition("required"), json("""["label", "children"]"""))
    assertEquals(definition("additionalProperties"), ujson.Bool(false))
  }

  // ── A schema from the field types ───────────────────────────────────────────────────────────────────────────

  test("a string signature gets a strict schema from its field types") {
    val layout = layoutOf("question -> answer, score: int, ratio: float, ok: bool, tags: list[str], items: list")
    val schema = schemaOf(responseFormat(layout))
    assertEquals(
      schema,
      json(
        """{
          |  "type": "object",
          |  "properties": {
          |    "answer": {"type": "string"},
          |    "score": {"type": "integer"},
          |    "ratio": {"type": "number"},
          |    "ok": {"type": "boolean"},
          |    "tags": {"type": "array", "items": {"type": "string"}},
          |    "items": {"type": "array", "items": {}}
          |  },
          |  "required": ["answer", "score", "ratio", "ok", "tags", "items"],
          |  "additionalProperties": false
          |}""".stripMargin
      )
    )
  }

  test("an enum field gives a string enum, and a default value stays in the schema") {
    val layout = layoutWith(
      FieldSpec("verdict", enumValues = Vector("yes", "no")),
      FieldSpec("note", defaultValue = Some(DynamicValues.fromAny("none given")))
    )
    val properties = schemaOf(responseFormat(layout))("properties")
    assertEquals(properties("verdict"), json("""{"type": "string", "enum": ["yes", "no"]}"""))
    assertEquals(properties("note"), json("""{"type": "string", "default": "none given"}"""))
  }

  test("an optional field permits null and stays required, and a null default is removed") {
    val layout = layoutWith(
      FieldSpec("answer"),
      FieldSpec("count", typeRef = TypeRef.int, optional = true, defaultValue = Some(DynamicValue.Null))
    )
    val schema = schemaOf(responseFormat(layout))
    assertEquals(schema("required"), json("""["answer", "count"]"""))
    assertEquals(schema("properties")("count"), json("""{"anyOf": [{"type": "integer"}, {"type": "null"}]}"""))
  }

  test("the constraints of a field stay in the strict schema") {
    val layout = layoutWith(
      FieldSpec("score", typeRef = TypeRef.int, constraints = Vector(dspy4s.core.contracts.FieldConstraints.gt(0)))
    )
    val score = schemaOf(responseFormat(layout))("properties")("score")
    assertEquals(score("type").str, "integer")
    assertEquals(score("exclusiveMinimum").num, 0.0)
  }

  // ── Native tool calls ───────────────────────────────────────────────────────────────────────────────────────

  test("with native function calling active, the schema has no tool calls field") {
    val layout = layoutOf("question -> answer, tool_calls: tool_calls")
    val schema = schemaOf(responseFormat(layout, native = true, functionCalls = true))
    assertEquals(schema("required"), json("""["answer"]"""))
    assert(!schema("properties").obj.contains("tool_calls"), schema.toString)
  }

  // ── JSON mode ───────────────────────────────────────────────────────────────────────────────────────────────

  test("a text tool calls field gives JSON mode") {
    val layout = layoutOf("question -> answer, tool_calls: tool_calls")
    assertEquals(responseFormat(layout), jsonMode)
    // Native function calling is on, but the model does not support it: the field is text.
    assertEquals(responseFormat(layout, native = true, functionCalls = false), jsonMode)
  }

  test("an open map gives JSON mode, in a string signature and in a typed schema") {
    assertEquals(responseFormat(layoutOf("question -> answer, data: dict")), jsonMode)
    assertEquals(responseFormat(layoutOf("question -> scores: json, kind"), Some(mapSchema)), jsonMode)
  }

  test("a type with no schema gives JSON mode") {
    assertEquals(responseFormat(layoutOf("question -> answer: Widget")), jsonMode)
  }

  test("a typed schema that does not parse gives JSON mode") {
    assertEquals(responseFormat(layoutOf("question -> answer"), Some("{not valid json")), jsonMode)
  }

  // ── The strict shape, rule by rule ──────────────────────────────────────────────────────────────────────────

  private def shaped(schema: String, optional: Set[String] = Set.empty): ujson.Value =
    StrictJsonSchema.shape(parsed(schema), optional).fold(error => fail(s"shape failed: $error"), ujsonOf)

  test("an allOf with one schema is merged into its parent") {
    val result = shaped("""{"type": "object", "properties": {"a": {"allOf": [{"type": "string"}], "title": "A"}}}""")
    assertEquals(result("properties")("a"), json("""{"type": "string", "title": "A"}"""))
  }

  test("a $ref with other keys resolves, and a $ref alone stays") {
    val result = shaped(
      """{
        |  "type": "object",
        |  "$defs": {"Step": {"type": "object", "properties": {"action": {"type": "string"}}}},
        |  "properties": {
        |    "first": {"$ref": "#/$defs/Step", "description": "the first step"},
        |    "second": {"$ref": "#/$defs/Step"}
        |  }
        |}""".stripMargin
    )
    assertEquals(
      result("properties")("first"),
      json(
        """{"type": "object", "properties": {"action": {"type": "string"}}, "required": ["action"],
          |  "additionalProperties": false, "description": "the first step"}""".stripMargin
      )
    )
    assertEquals(result("properties")("second"), json("""{"$ref": "#/$defs/Step"}"""))
    assertEquals(result("$defs")("Step")("additionalProperties"), ujson.Bool(false))
  }

  test("a null default is removed, and a default that is not null stays") {
    val result = shaped(
      """{"type": "object", "properties": {"a": {"type": "string", "default": null}, "b": {"type": "integer", "default": 3}}}"""
    )
    assertEquals(result("properties")("a"), json("""{"type": "string"}"""))
    assertEquals(result("properties")("b"), json("""{"type": "integer", "default": 3}"""))
  }

  test("an optional top-level property gets a null option only when it permits no null") {
    val result = shaped(
      """{"type": "object", "properties": {"a": {"type": "string"}, "b": {"type": ["null", "string"]}, "c": {"type": "string"}}}""",
      optional = Set("a", "b")
    )
    assertEquals(result("properties")("a"), json("""{"anyOf": [{"type": "string"}, {"type": "null"}]}"""))
    assertEquals(result("properties")("b"), json("""{"type": ["null", "string"]}"""))
    assertEquals(result("properties")("c"), json("""{"type": "string"}"""))
  }

  test("a oneOf goes out as an anyOf, with each schema in strict shape") {
    // zio-blocks writes `oneOf` for a sealed trait whose cases have fields. OpenAI strict mode takes `anyOf` only.
    val result = shaped(
      """{
        |  "type": "object",
        |  "properties": {
        |    "shape": {"oneOf": [
        |      {"type": "object", "properties": {"radius": {"type": "number"}}},
        |      {"type": "object", "properties": {"side": {"type": "number"}}}
        |    ]}
        |  }
        |}""".stripMargin
    )
    assertEquals(
      result("properties")("shape"),
      json(
        """{"anyOf": [
          |  {"type": "object", "properties": {"radius": {"type": "number"}}, "required": ["radius"],
          |   "additionalProperties": false},
          |  {"type": "object", "properties": {"side": {"type": "number"}}, "required": ["side"],
          |   "additionalProperties": false}
          |]}""".stripMargin
      )
    )
  }

  test("an open map at any level, and a $ref that does not resolve, cannot take strict shape") {
    val nestedMap =
      """{"type": "object", "properties": {"a": {"type": "array", "items": {"type": "object", "additionalProperties": true}}}}"""
    assert(StrictJsonSchema.shape(parsed(nestedMap)).isLeft)
    val badRef = """{"type": "object", "properties": {"a": {"$ref": "#/$defs/Missing", "title": "A"}}}"""
    assert(StrictJsonSchema.shape(parsed(badRef)).isLeft)
  }

  // ── A schema from the field types, function by function ─────────────────────────────────────────────────────

  test("fromFields gives no schema for a field type that has none") {
    assertEquals(StrictJsonSchema.fromFields(Vector(FieldSpec("a"), FieldSpec("b", typeRef = TypeRef.json))), None)
    assertEquals(StrictJsonSchema.fromFields(Vector(FieldSpec("a", typeRef = TypeRef.toolCalls))), None)
    assertEquals(StrictJsonSchema.fromFields(Vector(FieldSpec("a", typeRef = TypeRef("Widget")))), None)
    assertEquals(StrictJsonSchema.fromFields(Vector(FieldSpec("a", typeRef = TypeRef("list[dict]")))), None)
  }
