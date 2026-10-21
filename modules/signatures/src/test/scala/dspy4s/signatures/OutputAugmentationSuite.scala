package dspy4s.signatures

import dspy4s.signatures.OutputAugmentation.{PrependField, WithField}
import dspy4s.core.contracts.{DspyError, DynamicValues, FieldSpec, NotFoundError, ValidationError}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}
import scala.NamedTuple
import munit.FunSuite

final case class OaAnswer(answer: String, score: Double)
final case class OaReasoned(reasoning: String, answer: String)
final case class OaNode(label: String, children: List[OaNode])

class OutputAugmentationSuite extends FunSuite:

  case class Answer(answer: String, score: Double)

  // Summon the pinned instance the way a program would (WithOutput[Name, T, O, WithField[O, Name, T]]).
  private inline def prepend[Name <: String & Singleton, T, O](value: T, base: O)(using
      p: PrependField.Of[Name, T, O]
  ): Option[WithField[O, Name, T]] = p.prepend(value, base)

  test("named-tuple output without the field: prepends it") {
    type O = (answer: String, score: Double)
    val base: O = (answer = "Paris", score = 0.9)
    val result  = prepend["reasoning", String, O]("because", base)
    assertEquals(result.map(NamedTuple.toTuple), Some(("because", "Paris", 0.9)))
  }

  test("named-tuple output that already declares the field: idempotent (kept unchanged)") {
    type O = (reasoning: String, answer: String)
    val base: O = (reasoning = "kept", answer = "Paris")
    val result  = prepend["reasoning", String, O]("ignored", base)
    assertEquals(result.map(NamedTuple.toTuple), Some(("kept", "Paris")))
  }

  test("case-class output: normalized to a named tuple with the field prepended") {
    val result = prepend["reasoning", String, Answer]("because", Answer("Paris", 0.9))
    assertEquals(result.map(NamedTuple.toTuple), Some(("because", "Paris", 0.9)))
  }

  test("generic over field name and type (e.g. rationale)") {
    type O = (winner: Int)
    val base: O = (winner = 2)
    val result  = prepend["rationale", String, O]("chain 2 wins", base)
    assertEquals(result.map(NamedTuple.toTuple), Some(("chain 2 wins", 2)))
  }

  test("fallback: a non-product / non-named-tuple output is unsupported (None)") {
    // No `Mirror.ProductOf[Int]` and not a named tuple -> only the low-priority fallback matches.
    val result = summon[PrependField["x", String, Int]].prepend("v", 5)
    assert(result.isEmpty)
  }

  // ── decodePrepended: the value-level decode shared by the composite programs ──────────────────────

  private def str(s    : String): DynamicValue                         = DynamicValue.Primitive(PrimitiveValue.String(s))
  private def rec(pairs: (String, DynamicValue)*): DynamicValue.Record = DynamicValue.Record(Chunk.from(pairs))

  // A minimal named-tuple Shape that decodes `{ answer }` into `(answer: String)`.
  private def answerShape: Shape[(answer: String)] =
    new Shape[(answer: String)]:
      val fieldSpecs: Vector[FieldSpec]                                         = Vector(FieldSpec("answer"))
      def encode(value: (answer: String)): DynamicValue.Record                  = rec("answer" -> str(value.answer))
      def decode(raw: DynamicValue.Record): Either[DspyError, (answer: String)] =
        DynamicValues.requireString(raw, "answer", "test").map(a => (answer = a))

  // A non-product Shape: its decoded output hits the PrependField fallback (None) inside decodePrepended.
  private def intShape: Shape[Int] =
    new Shape[Int]:
      val fieldSpecs: Vector[FieldSpec]                              = Vector.empty
      def encode(value: Int): DynamicValue.Record                    = rec()
      def decode(raw  : DynamicValue.Record): Either[DspyError, Int] = Right(0)

  test("decodePrepended: reads the field, decodes the base, prepends") {
    val r = OutputAugmentation.decodePrepended(
      rec("reasoning" -> str("because"), "answer" -> str("Paris")),
      answerShape,
      "reasoning",
      "test",
      "sig"
    )
    assertEquals(r.map(NamedTuple.toTuple), Right(("because", "Paris")): Either[DspyError, (String, String)])
  }

  test("decodePrepended: a missing field is a NotFoundError") {
    val r = OutputAugmentation.decodePrepended(rec("answer" -> str("Paris")), answerShape, "reasoning", "test", "sig")
    r match
      case Left(_: NotFoundError) => ()
      case other                  => fail(s"expected NotFoundError, got $other")
  }

  test("decodePrepended: a fieldless (non-product) output is a ValidationError") {
    val r = OutputAugmentation.decodePrepended(rec("reasoning" -> str("x")), intShape, "reasoning", "test", "sig")
    r match
      case Left(_: ValidationError) => ()
      case other                    => fail(s"expected ValidationError, got $other")
  }

  // ── decodeAugmented: the generalized (field + post-decode hook) augment ──────────────────────

  private def int(i: Int): DynamicValue = DynamicValue.Primitive(PrimitiveValue.Int(i))

  /** Read an augment field as an `Int` (proving the augment is not String-only). */
  private def readInt(record: DynamicValue.Record, field: String, label: String): Either[DspyError, Int] =
    DynamicValues.recordGet(record, field).collect { case DynamicValue.Primitive(PrimitiveValue.Int(i)) => i }
      .toRight(NotFoundError("field", s"$label: missing or non-Int field '$field'"))

  test("decodeAugmented: a non-String (Int) field is read and prepended (generic field support is live)") {
    val r = OutputAugmentation.decodeAugmented[(answer: String), "confidence", Int, (confidence: Int, answer: String)](
      rec("confidence" -> int(3), "answer" -> str("Paris")),
      answerShape,
      "confidence",
      "test",
      "sig",
      readField = readInt
    )
    assertEquals(r.map(NamedTuple.toTuple), Right((3, "Paris")): Either[DspyError, (Int, String)])
  }

  test("decodeAugmented: augment round-trip — the base output is recoverable (the added field is extra)") {
    // base(decodeAugmented(...)) == shape.decode(...): dropping the prepended field yields the base output.
    val raw = rec("confidence" -> int(7), "answer" -> str("Rome"))
    val aug =
      OutputAugmentation.decodeAugmented[(answer: String), "confidence", Int, (confidence: Int, answer: String)](
        raw,
        answerShape,
        "confidence",
        "test",
        "sig",
        readField = readInt
      )
    val base = answerShape.decode(raw)
    assertEquals(aug.map(o => NamedTuple.toTuple(o).tail), base.map(NamedTuple.toTuple))
  }

  test("decodeAugmented: the post-decode hook runs and can reject") {
    val raw = rec("reasoning" -> str("because"), "answer" -> str("Paris"))
    // A hook that rejects empty answers; here it passes through unchanged.
    val pass =
      OutputAugmentation.decodeAugmented[(answer: String), "reasoning", String, (reasoning: String, answer: String)](
        raw,
        answerShape,
        "reasoning",
        "test",
        "sig",
        readField = (rrec, f, l) => DynamicValues.requireString(rrec, f, l),
        hook = out => if out.answer.isEmpty then Left(ValidationError("empty answer")) else Right(out)
      )
    assertEquals(pass.map(NamedTuple.toTuple), Right(("because", "Paris")): Either[DspyError, (String, String)])

    val rejected =
      OutputAugmentation.decodeAugmented[(answer: String), "reasoning", String, (reasoning: String, answer: String)](
        rec("reasoning" -> str("because"), "answer" -> str("")),
        answerShape,
        "reasoning",
        "test",
        "sig",
        readField = (rrec, f, l) => DynamicValues.requireString(rrec, f, l),
        hook = out => if out.answer.isEmpty then Left(ValidationError("empty answer")) else Right(out)
      )
    assert(rejected.isLeft, s"hook should reject empty answer, got $rejected")
  }

  // ── prependedStringShape: the JSON schema of the augmented output ──────────────────────

  private def reasoningField: FieldSpec = FieldSpec("reasoning")

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

  test("prependedStringShape: the JSON schema has the prepended field first in the properties and in required") {
    val base   = Shape.canonicalDerived[OaAnswer]
    val schema = parsed(
      OutputAugmentation.prependedStringShape(base, reasoningField, "reasoning", "test", "sig").jsonSchemaString
    )
    assertEquals(keysAt(schema, "properties"), Vector("reasoning", "answer", "score"))
    assertEquals(at(schema, "properties", "reasoning"), Some(rec("type" -> str("string"))))
    assertEquals(names(at(schema, "required")), Vector("reasoning", "answer", "score"))
    assertEquals(at(schema, "properties", "score"), at(parsed(base.jsonSchemaString), "properties", "score"))
  }

  test("prependedStringShape: an optional field or a field with a default value is not in required") {
    val base = Shape.canonicalDerived[OaAnswer]
    for field <- Vector(reasoningField.copy(optional = true), reasoningField.copy(defaultValue = Some(str("none"))))
    do
      val schema = parsed(
        OutputAugmentation.prependedStringShape(base, field, "reasoning", "test", "sig").jsonSchemaString
      )
      assertEquals(keysAt(schema, "properties"), Vector("reasoning", "answer", "score"))
      assertEquals(names(at(schema, "required")), Vector("answer", "score"))
  }

  test("prependedStringShape: a base that already has the field keeps its JSON schema") {
    val base  = Shape.canonicalDerived[OaReasoned]
    val shape = OutputAugmentation.prependedStringShape(base, reasoningField, "reasoning", "test", "sig")
    assertEquals(shape.jsonSchemaString, base.jsonSchemaString)
  }

  test("prependedStringShape: for a recursive base type, the definition replaces the root $ref and $defs stays") {
    val base   = Shape.canonicalDerived[OaNode]
    val schema = parsed(
      OutputAugmentation.prependedStringShape(base, reasoningField, "reasoning", "test", "sig").jsonSchemaString
    )
    assertEquals(at(schema, "$ref"), None)
    assertEquals(keysAt(schema, "properties"), Vector("reasoning", "label", "children"))
    assertEquals(names(at(schema, "required")), Vector("reasoning", "label"))
    assertEquals(at(schema, "properties", "children", "items"), Some(rec("$ref" -> str("#/$defs/OaNode"))))
    assertEquals(keysAt(schema, "$defs", "OaNode", "properties"), Vector("label", "children"))
  }

  test("prependedStringShape: a base with no JSON schema gives no JSON schema") {
    val shape = OutputAugmentation.prependedStringShape(answerShape, reasoningField, "reasoning", "test", "sig")
    assertEquals(shape.jsonSchemaString, None)
  }
