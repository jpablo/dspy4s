package dspy4s.signatures

import dspy4s.core.contracts.{:=, DynamicValues, FieldSpec, Reasoning, TypeRef}
import munit.FunSuite
import zio.blocks.maybe.Maybe
import zio.blocks.schema.{DynamicValue, Schema}

final case class RfOut(reasoning: Reasoning, answer: String)
object RfOut:
  given Schema[RfOut] = Schema.derived

final case class RfOptional(reasoning: Option[Reasoning], answer: String)
object RfOptional:
  given Schema[RfOptional] = Schema.derived

final case class RfMaybe(reasoning: Maybe[Reasoning], answer: String)

final case class RfDefault(answer: String, reasoning: Reasoning = Reasoning("(omitted)"))
object RfDefault:
  given Schema[RfDefault] = Schema.derived

/** A [[Reasoning]] field of a typed signature has the wire type [[TypeRef.reasoning]], and its value is a text. */
class ReasoningFieldSuite extends FunSuite:

  private def field(specs: Vector[FieldSpec], name: String): FieldSpec =
    specs.find(_.name == name).getOrElse(fail(s"no field $name in $specs"))

  test("a field of a case class has the wire type") {
    val spec = field(Shape.derived[RfOut].fieldSpecs, "reasoning")
    assertEquals(spec.typeRef, TypeRef.reasoning)
    assertEquals(spec.optional, false)
    assertEquals(spec.enumValues, Vector.empty)
  }

  test("a field of a named tuple has the wire type") {
    val spec = field(Shape.canonicalDerived[(reasoning: Reasoning, answer: String)].fieldSpecs, "reasoning")
    assertEquals(spec.typeRef, TypeRef.reasoning)
  }

  test("a field of a case class with no given schema has the wire type") {
    val spec = field(Shape.canonicalDerived[RfOut].fieldSpecs, "reasoning")
    assertEquals(spec.typeRef, TypeRef.reasoning)
  }

  test("an Option field is an optional field of the wire type") {
    val spec = field(Shape.derived[RfOptional].fieldSpecs, "reasoning")
    assertEquals(spec.typeRef, TypeRef.reasoning)
    assertEquals(spec.optional, true)
  }

  test("an Option field with no given schema is an optional field of the wire type") {
    val spec = field(Shape.canonicalDerived[RfOptional].fieldSpecs, "reasoning")
    assertEquals(spec.typeRef, TypeRef.reasoning)
    assertEquals(spec.optional, true)
  }

  test("a Maybe field with no given schema is an optional field of the wire type") {
    val spec = field(Shape.canonicalDerived[RfMaybe].fieldSpecs, "reasoning")
    assertEquals(spec.typeRef, TypeRef.reasoning)
    assertEquals(spec.optional, true)
  }

  test("a string signature with the type token has a typed reasoning output") {
    val signature = Signature.fromString("question -> reasoning: dspy.Reasoning, answer")
    assertEquals(signature.layout.outputFields.map(_.typeRef), Vector(TypeRef.reasoning, TypeRef.string))
    val record           = DynamicValues.record("reasoning" := "why", "answer" := "a")
    val decoded          = signature.outputShape.decode(record).fold(error => fail(error.message), identity)
    val typed: Reasoning = decoded.reasoning
    assertEquals(typed.content, "why")
    assertEquals(decoded.answer, "a")
  }

  test("a field with a default keeps the default as a text") {
    val spec = field(Shape.derived[RfDefault].fieldSpecs, "reasoning")
    assertEquals(spec.defaultValue, Some(DynamicValues.string("(omitted)")))
    assertEquals(spec.optional, false)
  }

  test("encode writes a text, and decode reads a text") {
    val shape  = Shape.derived[RfOut]
    val record = shape.encode(RfOut(Reasoning("why"), "a"))
    assertEquals(DynamicValues.recordGet(record, "reasoning"), Some(DynamicValues.string("why")))
    assertEquals(shape.decode(record), Right(RfOut(Reasoning("why"), "a")))
  }

  test("the JSON schema of the field is a text") {
    val schema                                                              = Shape.derived[RfOut].jsonSchema.getOrElse(fail("no schema"))
    def at(record: Option[DynamicValue], key: String): Option[DynamicValue] =
      record.collect { case found: DynamicValue.Record => DynamicValues.recordGet(found, key) }.flatten
    assertEquals(at(at(at(Some(schema), "properties"), "reasoning"), "type"), Some(DynamicValues.string("string")))
  }
