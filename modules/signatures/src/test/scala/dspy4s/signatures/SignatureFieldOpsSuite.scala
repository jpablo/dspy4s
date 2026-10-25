package dspy4s.signatures

import dspy4s.signatures.OutputAugmentation.{PrependField, WithField}
import dspy4s.core.contracts.{:=, DspyError, DynamicValues, FieldSpec, NotFoundError, TypeRef, ValidationError}
import munit.{FunSuite, Location}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}
import scala.NamedTuple

final case class SfQuestion(question: String)
final case class SfAnswer(answer: String, score: Double)
final case class SfReasoned(reasoning: String, answer: String)
final case class SfNode(label: String, children: List[SfNode])

/** The field operations of `Signature`: `appendInput`, `appendInputs`, `prependOutput`, and `withOutputs`. Each
  * operation changes the layout and the shapes in one call, thus the two agree on the names and the order of the
  * fields.
  */
class SignatureFieldOpsSuite extends FunSuite:

  private val base: Signature[SfQuestion, SfAnswer] = Signature.derived[SfQuestion, SfAnswer]("QA")

  private val reasoningField: FieldSpec  = FieldSpec("reasoning")
  private val trajectoryField: FieldSpec = FieldSpec("trajectory")

  // The outputs of a loop step, for `withOutputs`.
  private val stepFields: Vector[FieldSpec] = Vector(FieldSpec("next_thought"), FieldSpec("next_tool_name"))

  private val stepShape: Shape[(next_thought: String, next_tool_name: String)] =
    Shape.canonicalDerived[(next_thought: String, next_tool_name: String)]

  /** `signature` with the output `reasoning` first, with the evidence that a program gets from its caller. */
  private def withReasoning[I, O](signature: Signature[I, O], field: FieldSpec = reasoningField)(using
      PrependField.Of["reasoning", String, O]
  ): Signature[I, WithField[O, "reasoning", String]] =
    signature.prependOutput("reasoning", field)

  /** The parts of a field that the layout and the shape share: the name, the type, and the optional flag. */
  private def shared(fields: Vector[FieldSpec]): Vector[(String, TypeRef, Boolean)] =
    fields.map(field => (field.name, field.typeRef, field.optional))

  /** The field specs of the shapes match the layout, in the same order. */
  private def assertAgree(signature: Signature[?, ?])(using Location): Unit =
    assertEquals(shared(signature.inputShape.fieldSpecs), shared(signature.layout.inputFields))
    assertEquals(shared(signature.outputShape.fieldSpecs), shared(signature.layout.outputFields))

  private def str(text: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(text))

  private def rec(pairs: (String, DynamicValue)*): DynamicValue.Record = DynamicValue.Record(Chunk.from(pairs))

  // ── appendInput ──────────────────────────────────────────────────────────────────────────────

  test("appendInput: the field is the last input of the layout and of the input shape") {
    val signature = base.appendInput(trajectoryField)
    assertAgree(signature)
    assertEquals(signature.layout.inputFields.map(_.name), Vector("question", "trajectory"))
    assertEquals(signature.layout.outputFields, base.layout.outputFields)
    assertEquals(signature.layout.instructions, Some(base.layout.effectiveInstructions))

    val encoded = signature.inputShape.encode((SfQuestion("why?"), "step 1"))
    assertEquals(encoded, rec("question" -> str("why?"), "trajectory" -> str("step 1")))
    assertEquals(signature.inputShape.decode(encoded), Right((SfQuestion("why?"), "step 1")))
  }

  test("appendInput: a name that the base has is rejected") {
    val _ = intercept[IllegalArgumentException](base.appendInput(FieldSpec("question")))
    val _ = intercept[IllegalArgumentException](base.appendInput(FieldSpec("answer")))
  }

  // ── appendInputs ─────────────────────────────────────────────────────────────────────────────

  test("appendInputs: the fields come after the inputs, and only validate gives their values") {
    val appended = base.appendInputs(Vector(FieldSpec("attempt_1"), FieldSpec("attempt_2")))
    assertAgree(appended.signature)
    assertEquals(appended.signature.layout.inputFields.map(_.name), Vector("question", "attempt_1", "attempt_2"))
    assert(appended.validate(Vector("one")).isLeft)

    val values  = appended.validate(Vector("one", "two")).fold(error => fail(error.message), identity)
    val encoded = appended.signature.inputShape.encode((SfQuestion("q"), values))
    assertEquals(DynamicValues.recordKeys(encoded), Vector("question", "attempt_1", "attempt_2"))
    assertEquals(appended.signature.inputShape.decode(encoded), Right((SfQuestion("q"), values)))
  }

  test("appendInputs: a name that the base has, and two fields with the same name, are rejected") {
    val _ = intercept[IllegalArgumentException](base.appendInputs(Vector(FieldSpec("x"), FieldSpec("question"))))
    val _ = intercept[IllegalArgumentException](base.appendInputs(Vector(FieldSpec("x"), FieldSpec("x"))))
  }

  // ── prependOutput ────────────────────────────────────────────────────────────────────────────

  test("prependOutput: the field is the first output of the layout and of the output shape") {
    val signature = withReasoning(base)
    assertAgree(signature)
    assertEquals(signature.layout.outputFields.map(_.name), Vector("reasoning", "answer", "score"))
    assertEquals(signature.layout.inputFields, base.layout.inputFields)
    assertEquals(signature.layout.instructions, Some(base.layout.effectiveInstructions))

    val raw     = DynamicValues.record("reasoning" := "because", "answer" := "Paris", "score" := 0.9)
    val decoded = signature.outputShape.decode(raw)
    assertEquals(
      decoded.map(NamedTuple.toTuple),
      Right(("because", "Paris", 0.9)): Either[DspyError, (String, String, Double)]
    )
    assertEquals(decoded.map(signature.outputShape.encode), Right(raw): Either[DspyError, DynamicValue.Record])
  }

  test("prependOutput: a base that already has the field stays the same") {
    val reasoned  = Signature.derived[SfQuestion, SfReasoned]("Reasoned")
    val signature = withReasoning(reasoned)
    assertEquals(signature.layout, reasoned.layout)
    assertEquals(signature.outputShape.fieldSpecs, reasoned.outputShape.fieldSpecs)
    assertEquals(signature.outputShape.jsonSchemaString, reasoned.outputShape.jsonSchemaString)

    val raw = reasoned.outputShape.encode(SfReasoned("kept", "Paris"))
    assertEquals(
      signature.outputShape.decode(raw).map(NamedTuple.toTuple),
      Right(("kept", "Paris")): Either[DspyError, (String, String)]
    )
    assertEquals(
      signature.outputShape.decode(raw).map(signature.outputShape.encode),
      Right(raw): Either[DspyError, DynamicValue.Record]
    )
  }

  test("prependOutput: an input with the name, and a field with another name, are rejected") {
    val _ = intercept[IllegalArgumentException](base.prependOutput("question", FieldSpec("question")))
    val _ = intercept[IllegalArgumentException](withReasoning(base, FieldSpec("rationale")))
  }

  test("prependOutput: the decode needs the field as a String") {
    val shape = withReasoning(base).outputShape
    shape.decode(DynamicValues.record("answer" := "Paris", "score" := 0.9)) match
      case Left(_: NotFoundError) => ()
      case other                  => fail(s"expected NotFoundError, got $other")
    shape.decode(DynamicValues.record("reasoning" := 3, "answer" := "Paris", "score" := 0.9)) match
      case Left(_: ValidationError) => ()
      case other                    => fail(s"expected ValidationError, got $other")
  }

  test("prependOutput: a fieldless (non-product) output gives a ValidationError") {
    val intShape = new Shape[Int]:
      val fieldSpecs: Vector[FieldSpec]                              = Vector.empty
      def encode(value: Int): DynamicValue.Record                    = rec()
      def decode(raw  : DynamicValue.Record): Either[DspyError, Int] = Right(0)
    val signature =
      Signature.fromShapes("Ints", "", base.inputShape, intShape).prependOutput("reasoning", reasoningField)
    signature.outputShape.decode(rec("reasoning" -> str("x"))) match
      case Left(_: ValidationError) => ()
      case other                    => fail(s"expected ValidationError, got $other")
  }

  // ── prependOutput: the JSON schema of the output ─────────────────────────────────────────────

  private def parsed(schema: Option[String]): DynamicValue.Record =
    schema.flatMap(DynamicValues.parseJsonRecord).getOrElse(fail(s"no JSON schema: $schema"))

  private def at(record: DynamicValue.Record, path: String*): Option[DynamicValue] =
    path.foldLeft[Option[DynamicValue]](Some(record)) {
      case (Some(node: DynamicValue.Record), key) => DynamicValues.recordGet(node, key)
      case _                                      => None
    }

  private def keysAt(record: DynamicValue.Record, path: String*): Vector[String] =
    at(record, path*) match
      case Some(node: DynamicValue.Record) => DynamicValues.recordKeys(node)
      case other                           => fail(s"no object at ${path.mkString("/")}: $other")

  private def names(value: Option[DynamicValue]): Vector[String] =
    value match
      case Some(list: DynamicValue.Sequence) => list.elements.toVector.flatMap(DynamicValues.asString)
      case other                             => fail(s"expected a list, got: $other")

  test("prependOutput: the JSON schema has the prepended property first in the properties and in required") {
    val schema = parsed(withReasoning(base).outputShape.jsonSchemaString)
    assertEquals(keysAt(schema, "properties"), Vector("reasoning", "answer", "score"))
    assertEquals(at(schema, "properties", "reasoning"), Some(rec("type" -> str("string"))))
    assertEquals(names(at(schema, "required")), Vector("reasoning", "answer", "score"))
    assertEquals(
      at(schema, "properties", "score"),
      at(parsed(base.outputShape.jsonSchemaString), "properties", "score")
    )
  }

  test("prependOutput: an optional field or a field with a default value is not in required") {
    for field <- Vector(reasoningField.copy(optional = true), reasoningField.copy(defaultValue = Some(str("none"))))
    do
      val schema = parsed(withReasoning(base, field).outputShape.jsonSchemaString)
      assertEquals(keysAt(schema, "properties"), Vector("reasoning", "answer", "score"))
      assertEquals(names(at(schema, "required")), Vector("answer", "score"))
  }

  test("prependOutput: for a recursive base type, the definition replaces the root $ref and $defs stays") {
    val schema = parsed(withReasoning(Signature.derived[SfQuestion, SfNode]("Tree")).outputShape.jsonSchemaString)
    assertEquals(at(schema, "$ref"), None)
    assertEquals(keysAt(schema, "properties"), Vector("reasoning", "label", "children"))
    assertEquals(names(at(schema, "required")), Vector("reasoning", "label"))
    assertEquals(at(schema, "properties", "children", "items"), Some(rec("$ref" -> str("#/$defs/SfNode"))))
    assertEquals(keysAt(schema, "$defs", "SfNode", "properties"), Vector("label", "children"))
  }

  test("prependOutput: a base output shape with no JSON schema gives no JSON schema") {
    val answerShape = new Shape[(answer: String)]:
      val fieldSpecs: Vector[FieldSpec]                                         = Vector(FieldSpec("answer"))
      def encode(value: (answer: String)): DynamicValue.Record                  = rec("answer" -> str(value.answer))
      def decode(raw: DynamicValue.Record): Either[DspyError, (answer: String)] =
        DynamicValues.requireString(raw, "answer", "test").map(answer => (answer = answer))
    val signature = withReasoning(Signature.fromShapes("Plain", "", base.inputShape, answerShape))
    assertAgree(signature)
    assertEquals(signature.outputShape.jsonSchemaString, None)
  }

  // ── withOutputs ──────────────────────────────────────────────────────────────────────────────

  test("withOutputs: the outputs and the output shape replace the base outputs, and the inputs stay") {
    val signature = base.appendInput(trajectoryField).withOutputs(stepFields, stepShape)
    assertAgree(signature)
    assertEquals(signature.layout.inputFields.map(_.name), Vector("question", "trajectory"))
    assertEquals(signature.layout.outputFields, stepFields)
    assertEquals(signature.layout.instructions, Some(base.layout.effectiveInstructions))
  }

  test("withOutputs: a shape with other names, and an output with the name of an input, are rejected") {
    val _ = intercept[IllegalArgumentException](base.withOutputs(stepFields.reverse, stepShape))
    val _ = intercept[IllegalArgumentException](base.withOutputs(Vector(FieldSpec("question")), base.inputShape))
  }

  // ── composition ──────────────────────────────────────────────────────────────────────────────

  test("appendInput and prependOutput compose: the inputs and the outputs of both operations agree") {
    val signature = withReasoning(base.appendInput(trajectoryField))
    assertAgree(signature)
    assertEquals(signature.layout.inputFields.map(_.name), Vector("question", "trajectory"))
    assertEquals(signature.layout.outputFields.map(_.name), Vector("reasoning", "answer", "score"))
  }
