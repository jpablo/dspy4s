package dspy4s.signatures

import dspy4s.core.contracts.{:=, DynamicValues, NotFoundError, ToolCall, TypeRef, ValidationError}
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}

// Top-level fixtures (Mirror derivation needs them outside the suite).
enum ZsSentiment:
  case sadness, joy, love
object ZsSentiment:
  given Schema[ZsSentiment] = Schema.derived

case class ZsClassifyOutput(sentiment: ZsSentiment, confidence: Double)
object ZsClassifyOutput:
  given Schema[ZsClassifyOutput] = Schema.derived

case class ZsCommentInput(comment: String, lang: String)
object ZsCommentInput:
  given Schema[ZsCommentInput] = Schema.derived

// A plain (parameterless-case) enum vs. an Option field, both of which are `Reflect.Variant` under the hood.
enum ZsKind:
  case alpha, beta
object ZsKind:
  given Schema[ZsKind] = Schema.derived

case class ZsMixed(kind: ZsKind, amount: Option[Double])
object ZsMixed:
  given Schema[ZsMixed] = Schema.derived

case class ZsOptionProbe(amount: Option[Double], note: String)
object ZsOptionProbe:
  given Schema[ZsOptionProbe] = Schema.derived

// Three levels of nesting: a record, a sequence of records, and a record of that sequence.
final case class ZsStep(action: String, comment: Option[String]) derives Schema
final case class ZsStage(label: String, steps: Vector[ZsStep], owner: Option[String]) derives Schema
final case class ZsPipeline(stage: ZsStage, summary: Option[String]) derives Schema

final case class ZsToolPlan(thought: String, toolCalls: Vector[ToolCall], last: Option[ToolCall]) derives Schema

// Recursive types: through a list, through an `Option`, and through a sealed trait.
final case class ZsNode(label: String, children: List[ZsNode]) derives Schema
final case class ZsChain(label: String, next: Option[ZsChain]) derives Schema
sealed trait ZsExpr derives Schema
final case class ZsNum(value: Int)                  extends ZsExpr derives Schema
final case class ZsAdd(left: ZsExpr, right: ZsExpr) extends ZsExpr derives Schema
final case class ZsHolder(node: ZsNode, chain: Option[ZsChain], expr: ZsExpr) derives Schema

/** The interchange format is now `DynamicValue.Record` end-to-end -- there is no `Map[String, Any]` intermediate -- so
  * the historical tests that exercised the `dynamicToAny` / `anyToDynamic` converters are gone (those helpers were
  * deleted). What remains exercises `fieldSpecsFromReflect`, `normalize`, and the native `encode` / `decode` round-trip
  * through `DynamicValue.Record`.
  */
class ZioSchemaCodecSuite extends FunSuite:

  test("fieldSpecsFromReflect produces role-free FieldSpecs with the right names and typeRefs") {
    import ZsCommentInput.given
    val specs = ZioSchemaCodec.fieldSpecsFromReflect(summon[Schema[ZsCommentInput]].reflect)
    assertEquals(specs.map(_.name), Vector("comment", "lang"))
    assertEquals(specs.map(_.typeRef), Vector(TypeRef.string, TypeRef.string))
  }

  test("enumValuesOf surfaces a plain enum's cases but not an Option's Some/None") {
    import ZsMixed.given
    val specs = ZioSchemaCodec.fieldSpecsFromReflect(summon[Schema[ZsMixed]].reflect)
    assertEquals(specs.find(_.name == "kind").get.enumValues, Vector("alpha", "beta"))
    assertEquals(
      specs.find(_.name == "amount").get.enumValues,
      Vector.empty,
      "Option is a Reflect.Variant but not an enum; its Some/None cases must not leak as allowed values"
    )
  }

  test("normalize wraps a bare value into Some and Null into None for an Option field") {
    import ZsOptionProbe.given
    val shape = ZioSchemaCodec.derivedFromZioSchema[ZsOptionProbe]
    assertEquals(
      shape.decode(DynamicValues.recordFromEntries(Seq("amount" := 2399.0, "note" := "x"))),
      Right(ZsOptionProbe(Some(2399.0), "x"))
    )
    // LM-shaped string coerces through the Some payload too.
    assertEquals(
      shape.decode(DynamicValues.recordFromEntries(Seq("amount" := "2399.0", "note" := "x"))),
      Right(ZsOptionProbe(Some(2399.0), "x"))
    )
    assertEquals(
      shape.decode(DynamicValues.recordFromEntries(Seq("amount" -> DynamicValue.Null, "note" := "y"))),
      Right(ZsOptionProbe(None, "y"))
    )
  }

  test("normalize coerces currency- and comma-formatted numbers into Int/Double") {
    case class Money(amount: Double, count: Int)
    object Money:
      given Schema[Money] = Schema.derived
    import Money.given
    val shape = ZioSchemaCodec.derivedFromZioSchema[Money]
    assertEquals(
      shape.decode(DynamicValues.recordFromEntries(Seq("amount" := "$2,399.00", "count" := "1,234"))),
      Right(Money(amount = 2399.0, count = 1234)),
      "LM-shaped numbers carry currency symbols / thousands separators; they must coerce to the numeric type"
    )
  }

  test("normalize coerces a currency-formatted number inside an Option[Double]") {
    import ZsOptionProbe.given
    val shape = ZioSchemaCodec.derivedFromZioSchema[ZsOptionProbe]
    assertEquals(
      shape.decode(DynamicValues.recordFromEntries(Seq("amount" := "$2,399.00", "note" := "x"))),
      Right(ZsOptionProbe(Some(2399.0), "x"))
    )
  }

  test("Variant fields surface as TypeRef.string at the wire boundary") {
    import ZsClassifyOutput.given
    val specs     = ZioSchemaCodec.fieldSpecsFromReflect(summon[Schema[ZsClassifyOutput]].reflect)
    val sentiment = specs.find(_.name == "sentiment").get
    assertEquals(sentiment.typeRef, TypeRef.string)
  }

  test("derivedFromZioSchema encode produces a DynamicValue.Record with each output field present") {
    import ZsClassifyOutput.given
    val shape   = ZioSchemaCodec.derivedFromZioSchema[ZsClassifyOutput]
    val encoded = shape.encode(ZsClassifyOutput(sentiment = ZsSentiment.joy, confidence = 0.95))
    assertEquals(DynamicValues.recordKeys(encoded).toSet, Set("sentiment", "confidence"))
    // confidence is a primitive Double -- read it back through the helper.
    assertEquals(
      DynamicValues.recordGet(encoded, "confidence"),
      Some(DynamicValue.Primitive(PrimitiveValue.Double(0.95)))
    )
  }

  test("derivedFromZioSchema decode accepts a DynamicValue.Record with the enum as a flat string") {
    import ZsClassifyOutput.given
    val shape = ZioSchemaCodec.derivedFromZioSchema[ZsClassifyOutput]
    val raw   = DynamicValues.recordFromEntries(Seq("sentiment" := "joy", "confidence" := 0.95))
    val out   = shape.decode(raw)
    assertEquals(out, Right(ZsClassifyOutput(sentiment = ZsSentiment.joy, confidence = 0.95)))
  }

  test("normalize coerces 'true' / '42' / '0.9' string primitives into the declared type") {
    case class Probe(flag: Boolean, count: Int, score: Double)
    object Probe:
      given Schema[Probe] = Schema.derived
    import Probe.given
    val shape = ZioSchemaCodec.derivedFromZioSchema[Probe]
    val raw   = DynamicValues.recordFromEntries(Seq("flag" := "true", "count" := "42", "score" := "0.9"))
    val out   = shape.decode(raw)
    assertEquals(out, Right(Probe(flag = true, count = 42, score = 0.9)))
  }

  test("encode / decode round-trip via DynamicValue.Record (no Map intermediate)") {
    import ZsClassifyOutput.given
    val shape = ZioSchemaCodec.derivedFromZioSchema[ZsClassifyOutput]
    val value = ZsClassifyOutput(sentiment = ZsSentiment.joy, confidence = 0.42)

    val encoded = shape.encode(value)
    // Native: the record holds a Variant for the enum (not a flat string).
    val sentiment = DynamicValues.recordGet(encoded, "sentiment")
    assert(
      sentiment.exists(_.isInstanceOf[DynamicValue.Variant]),
      s"expected Variant for sentiment, got: $sentiment"
    )

    val decoded = shape.decode(encoded)
    assertEquals(decoded, Right(value))
  }

  test("encode/decode round-trip for a case-class input") {
    import ZsCommentInput.given
    val shape   = ZioSchemaCodec.derivedFromZioSchema[ZsCommentInput]
    val value   = ZsCommentInput(comment = "Best movie ever!", lang = "en")
    val encoded = shape.encode(value)
    val decoded = shape.decode(encoded)
    assertEquals(decoded, Right(value))
  }

  // ── Absent fields (PORT_GAPS G-28) ──────────────────────────────────────────────────────────────────────────

  private def step(entries: (String, DynamicValue)*): DynamicValue = DynamicValues.recordFromEntries(entries)

  private def stage(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.record("stage" -> DynamicValues.recordFromEntries(entries))

  test("decode gives None for an optional field that is absent, at each level of nesting") {
    val shape  = Shape.derived[ZsPipeline]
    val record = stage(
      "label" := "build",
      "steps" ->
        DynamicValue.Sequence(Chunk(step("action" := "compile"), step("action" := "test", "comment" := "slow")))
    )
    assertEquals(
      shape.decode(record),
      Right(ZsPipeline(
        ZsStage("build", Vector(ZsStep("compile", None), ZsStep("test", Some("slow"))), owner = None),
        summary = None
      ))
    )
  }

  test("decode fails for a required field that is absent at the top level") {
    val shape = Shape.derived[ZsPipeline]
    shape.decode(DynamicValues.record("summary" := "done")) match
      case Left(error: NotFoundError) => assert(error.message.contains("stage"), error.message)
      case other                      => fail(s"expected NotFoundError, got: $other")
  }

  test("decode fails for a required field that is absent from a nested record") {
    val shape = Shape.derived[ZsPipeline]
    // The stage has no `label`.
    val noLabel = stage("steps" -> DynamicValue.Sequence(Chunk(step("action" := "compile"))))
    // One step of the sequence has no `action`.
    val noAction = stage("label" := "build", "steps" -> DynamicValue.Sequence(Chunk(step("comment" := "slow"))))
    Vector("label" -> noLabel, "action" -> noAction).foreach { (field, record) =>
      shape.decode(record) match
        case Left(error: ValidationError) => assert(error.message.contains(field), error.message)
        case other                        => fail(s"expected ValidationError for '$field', got: $other")
    }
  }

  // ── JSON schema of a tool call (PORT_GAPS G-28) ─────────────────────────────────────────────────────────────

  private def get(record: DynamicValue.Record, path: String*): Option[DynamicValue] =
    path.foldLeft(Option[DynamicValue](record)) {
      case (Some(current: DynamicValue.Record), key) => DynamicValues.recordGet(current, key)
      case _                                         => None
    }

  private def keys(value: Option[DynamicValue]): Set[String] =
    value match
      case Some(record: DynamicValue.Record) => DynamicValues.recordKeys(record).toSet
      case other                             => fail(s"expected a record, got: $other")

  test("the JSON schema of an output type does not ask for the id of a tool call") {
    val schema = Shape.derived[ZsToolPlan].jsonSchemaString.flatMap(DynamicValues.parseJsonRecord).get
    assertEquals(keys(get(schema, "properties")), Set("thought", "toolCalls", "last"))
    assertEquals(keys(get(schema, "properties", "toolCalls", "items", "properties")), Set("name", "args"))
    assertEquals(keys(get(schema, "properties", "last", "properties")), Set("name", "args"))
    // The canonical derivation of `Signature.derived` gives the same schema.
    assertEquals(Shape.canonicalDerived[ZsToolPlan].jsonSchemaString, Shape.derived[ZsToolPlan].jsonSchemaString)
  }

  test("the JSON schema of a type with no tool call is the JSON schema of its Schema") {
    assertEquals(
      Shape.derived[ZsPipeline].jsonSchemaString,
      Some(summon[Schema[ZsPipeline]].toJsonSchema.toJson.toString)
    )
    import ZsClassifyOutput.given
    assertEquals(
      ZioSchemaCodec.derivedFromZioSchema[ZsClassifyOutput].jsonSchemaString,
      Some(summon[Schema[ZsClassifyOutput]].toJsonSchema.toJson.toString)
    )
  }

  test("a tool call with an id still encodes and decodes with that id") {
    val shape = Shape.derived[ZsToolPlan]
    val call  = ToolCall("search", DynamicValues.record("query" := "cats"), id = Some("call_1"))
    val plan  = ZsToolPlan("I search.", Vector(call), last = Some(call.copy(id = None)))
    assertEquals(shape.decode(shape.encode(plan)), Right(plan))
  }

  // ── JSON schema of a recursive type ─────────────────────────────────────────────────────────────────────────

  /** Runs `body` on a different thread. The test fails if `body` does not return in `limit`, so a hang becomes a
    * failure.
    */
  private def withinTimeLimit[A](limit: FiniteDuration)(body: => A): A =
    try Await.result(Future(body)(using ExecutionContext.global), limit)
    catch case _: TimeoutException => fail(s"the call did not return in $limit")

  private def jsonSchemaOf[A](using schema: Schema[A]): DynamicValue.Record =
    val json = withinTimeLimit(10.seconds)(ZioSchemaCodec.jsonSchemaForModel(schema))
    DynamicValues.parseJsonRecord(json).getOrElse(fail(s"not a JSON object: $json"))

  private def refTo(name: String): DynamicValue = DynamicValues.record("$ref" := s"#/$$defs/$name")

  private def elements(value: Option[DynamicValue]): Vector[DynamicValue] =
    value match
      case Some(sequence: DynamicValue.Sequence) => sequence.elements.toVector
      case other                                 => fail(s"expected a sequence, got: $other")

  test("the JSON schema of a recursive type returns and refers to the type by $ref") {
    val schema = jsonSchemaOf[ZsNode]
    // The root is a `$ref` to the definition of the type, as in upstream pydantic.
    assertEquals(get(schema, "$ref"), Some(DynamicValue.Primitive(PrimitiveValue.String("#/$defs/ZsNode"))))
    assertEquals(keys(get(schema, "$defs")), Set("ZsNode"))
    assertEquals(keys(get(schema, "$defs", "ZsNode", "properties")), Set("label", "children"))
    assertEquals(get(schema, "$defs", "ZsNode", "properties", "children", "items"), Some(refTo("ZsNode")))
  }

  test("each recursive type in an output type has a definition, through an Option and through a sealed trait") {
    val schema = jsonSchemaOf[ZsHolder]
    // The output type is not recursive, so its schema is the root.
    assertEquals(get(schema, "$ref"), None)
    assertEquals(keys(get(schema, "$defs")), Set("ZsNode", "ZsChain", "ZsExpr"))
    assertEquals(get(schema, "properties", "node"), Some(refTo("ZsNode")))
    assertEquals(get(schema, "properties", "expr"), Some(refTo("ZsExpr")))
    // The definition is for the type in the `Option`, not for the `Option`.
    assert(elements(get(schema, "properties", "chain", "anyOf")).contains(refTo("ZsChain")))
    assert(elements(get(schema, "$defs", "ZsChain", "properties", "next", "anyOf")).contains(refTo("ZsChain")))
    // A case of the sealed trait refers to the sealed trait.
    val add = elements(get(schema, "$defs", "ZsExpr", "oneOf")).collectFirst {
      case option: DynamicValue.Record if get(option, "properties", "ZsAdd").isDefined => option
    }.getOrElse(fail("no ZsAdd case in the definition of ZsExpr"))
    assertEquals(keys(get(add, "properties", "ZsAdd", "properties")), Set("left", "right"))
    assertEquals(get(add, "properties", "ZsAdd", "properties", "left"), Some(refTo("ZsExpr")))
  }
