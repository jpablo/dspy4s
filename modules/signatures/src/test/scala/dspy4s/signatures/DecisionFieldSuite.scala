package dspy4s.signatures

import dspy4s.core.contracts.TypeRef
import dspy4s.core.decisions.{Choice, Noul, Score}
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, Schema}

import java.nio.charset.StandardCharsets

enum DfSeverity derives Schema:
  case Minor, Disruptive, Blocking

enum DfCategory derives Schema:
  case billing, technical

final case class DfTicket(ticket: String) derives Schema

final case class DfAssessment(
    urgent  : Noul,
    severity: Score[DfSeverity],
    category: Choice[DfCategory],
    flag    : Boolean,
    team    : DfCategory
) derives Schema

/** The decision outputs of a typed signature (experimental; upstream `Noul`, `Score`, and `Choice` annotations). A
  * field of type `Noul`, `Score[L]`, or `Choice[O]` is a rich decision output. The cases of `L` are the levels of the
  * Score, in order, and the cases of `O` are the options of the Choice.
  */
class DecisionFieldSuite extends FunSuite:

  private def json(text: String): DynamicValue.Record =
    Schema.dynamic.jsonCodec.decode(text.getBytes(StandardCharsets.UTF_8)) match
      case Right(record: DynamicValue.Record) => record
      case other                              => fail(s"not an object: $other")

  test("a Noul, Score, or Choice field gives the decision type, with the levels or the options as enum values") {
    val specs = ZioSchemaCodec.derivedFromZioSchema[DfAssessment].fieldSpecs
    assertEquals(
      specs.map(spec => (spec.name, spec.typeRef, spec.enumValues, spec.optional)),
      Vector(
        ("urgent", TypeRef.noul, Vector.empty, false),
        ("severity", TypeRef.score, Vector("Minor", "Disruptive", "Blocking"), false),
        ("category", TypeRef.choice, Vector("billing", "technical"), false),
        ("flag", TypeRef.bool, Vector.empty, false),
        ("team", TypeRef.string, Vector("billing", "technical"), false)
      )
    )
  }

  test("the signature builder gives a decision field the same type") {
    val layout = Signature.builder("Builder").input[String]("ticket").output[Score[DfSeverity]]("severity")
      .output[Choice[DfCategory]]("category").output[Noul]("urgent").build
    assertEquals(
      layout.outputFields.map(spec => (spec.name, spec.typeRef, spec.enumValues)),
      Vector(
        ("severity", TypeRef.score, Vector("Minor", "Disruptive", "Blocking")),
        ("category", TypeRef.choice, Vector("billing", "technical")),
        ("urgent", TypeRef.noul, Vector.empty)
      )
    )
  }

  test("a typed signature decodes the result records of the decision outputs") {
    val shape  = ZioSchemaCodec.derivedFromZioSchema[DfAssessment]
    val record = json(
      """{"urgent": {"value": true, "confidence": 0.6, "probability": 0.8},
        | "severity": {"value": 1.5, "confidence": 0.61, "probabilities": {"0": 0.1, "1": 0.3, "2": 0.6},
        |   "level": "Disruptive"},
        | "category": {"value": "technical", "confidence": 0.73, "probabilities": {"billing": 0.2, "technical": 0.8}},
        | "flag": false, "team": "billing"}""".stripMargin
    )
    assertEquals(
      shape.decode(record),
      Right(DfAssessment(
        urgent = Noul(value = true, confidence = 0.6, probability = Some(0.8)),
        severity = Score(1.5, 0.61, Some(Map(0 -> 0.1, 1 -> 0.3, 2 -> 0.6)), Some(DfSeverity.Disruptive)),
        category = Choice(DfCategory.technical, 0.73, Some(Map("billing" -> 0.2, "technical" -> 0.8))),
        flag = false,
        team = DfCategory.billing
      ))
    )
  }

  test("the typed decode gives the level of a Score as a case of the enum, and rejects a name that is not a level") {
    val shape = Shape.canonicalDerived[(severity: Score[DfSeverity])]

    // `entry` is the JSON text of the `level` entry of the record, with its comma, or empty for no entry.
    def decode(entry: String): Either[String, Option[DfSeverity]] =
      shape.decode(json(s"""{"severity": {"value": 1.5, "confidence": 0.61$entry}}""")).map(_.severity.level)
        .left.map(_.message)
    assertEquals(decode(""", "level": "Blocking""""), Right(Some(DfSeverity.Blocking)))
    assertEquals(decode(""), Right(None))
    assert(decode(""", "level": "Huge"""").isLeft)
    assert(decode(""", "level": 2""").isLeft, "a level index is not a level")
  }

  test("a typed decision value survives an encode and a decode, so it can be an input or a demo") {
    val shape = ZioSchemaCodec.derivedFromZioSchema[DfAssessment]
    val value = DfAssessment(
      urgent = Noul(value = false, confidence = 0.6, probability = Some(0.2)),
      severity = Score(0.4, 0.7, Some(Map(0 -> 0.6, 1 -> 0.4, 2 -> 0.0)), Some(DfSeverity.Minor)),
      category = Choice(DfCategory.billing, 0.9, None),
      flag = true,
      team = DfCategory.technical
    )
    assertEquals(shape.decode(shape.encode(value)), Right(value))
  }

  test("Signature.derived gives a Score field its levels and a Choice field its options") {
    val layout = Signature.derived[DfTicket, DfAssessment]("Assess").layout
    assertEquals(
      layout.outputFields.map(spec => (spec.name, spec.typeRef, spec.enumValues)).take(3),
      Vector(
        ("urgent", TypeRef.noul, Vector.empty),
        ("severity", TypeRef.score, Vector("Minor", "Disruptive", "Blocking")),
        ("category", TypeRef.choice, Vector("billing", "technical"))
      )
    )
  }
