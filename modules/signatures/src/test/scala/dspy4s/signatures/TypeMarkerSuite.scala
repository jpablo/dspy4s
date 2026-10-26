package dspy4s.signatures

import dspy4s.core.contracts.{DynamicValues, History, ToolCall, TypeMarker, TypeRef}
import dspy4s.core.contracts.Reflects.withoutDeferred
import dspy4s.core.decisions.{Choice, Noul, Score}
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, Modifier, Reflect, Schema}

enum TmLevel:
  case Low, High

final case class TmHolder(
    history : History,
    calls   : Vector[ToolCall],
    urgent  : Noul,
    severity: Score[TmLevel],
    label   : Choice[TmLevel]
)

/** A type with the marker of `History`, with literal arguments in place of the constants of `TypeMarker`. */
@Modifier.config("dspy4s.typeRef", "history")
final case class TmLiteralMarked(messages: Vector[String])

/** The type marker of the framework types ([[TypeMarker]]). `Schema.derived` and `Shape.canonicalSchema` keep a
  * type-level `Modifier.config` entry at the root, in a field, in the element of a sequence, and on a generic type. The
  * codec reads the marker from the reflect, and the signature macros read it from the annotations of the type symbol.
  */
class TypeMarkerSuite extends FunSuite:

  test("the key and the names of the markers do not change") {
    assertEquals(TypeMarker.Key, "dspy4s.typeRef")
    assertEquals(
      Vector(TypeMarker.history, TypeMarker.toolCall, TypeMarker.noul, TypeMarker.score, TypeMarker.choice),
      Vector("history", "tool_call", "noul", "score", "choice")
    )
  }

  test("Schema.derived keeps the marker of each framework type, also of a generic type") {
    assertEquals(TypeMarker.of(Schema[History].reflect), Some(TypeMarker.history))
    assertEquals(TypeMarker.of(Schema[ToolCall].reflect), Some(TypeMarker.toolCall))
    assertEquals(TypeMarker.of(Schema[Noul].reflect), Some(TypeMarker.noul))
    assertEquals(TypeMarker.of(Schema.derived[Score[TmLevel]].reflect), Some(TypeMarker.score))
    assertEquals(TypeMarker.of(Schema.derived[Choice[TmLevel]].reflect), Some(TypeMarker.choice))
    assertEquals(TypeMarker.of(Schema.derived[TmLiteralMarked].reflect), Some(TypeMarker.history))
  }

  test("the canonical schema keeps the marker at the root, in the fields, and in the element of a sequence") {
    assertEquals(TypeMarker.of(Shape.canonicalSchema[Noul].reflect), Some(TypeMarker.noul))
    withoutDeferred(Shape.canonicalSchema[TmHolder].reflect) match
      case record: Reflect.Record[?, ?] =>
        val fields = record.fields.map(term => term.name -> term.value).toMap
        assertEquals(TypeMarker.of(fields("history")), Some(TypeMarker.history))
        assertEquals(TypeMarker.of(fields("urgent")), Some(TypeMarker.noul))
        assertEquals(TypeMarker.of(fields("severity")), Some(TypeMarker.score))
        assertEquals(TypeMarker.of(fields("label")), Some(TypeMarker.choice))
        withoutDeferred(fields("calls")) match
          case sequence: Reflect.Sequence[?, ?, ?] =>
            assertEquals(TypeMarker.of(sequence.element), Some(TypeMarker.toolCall))
          case other => fail(s"not a sequence: $other")
      case other => fail(s"not a record: $other")
  }

  test("the codec gives the wire type of each marked field, the levels of a Score, and the labels of a Choice") {
    assertEquals(
      Shape.canonicalDerived[TmHolder].fieldSpecs.map(spec => (spec.name, spec.typeRef, spec.enumValues)),
      Vector(
        ("history", TypeRef.history, Vector.empty),
        ("calls", TypeRef.toolCalls, Vector.empty),
        ("urgent", TypeRef.noul, Vector.empty),
        ("severity", TypeRef.score, Vector("Low", "High")),
        ("label", TypeRef.choice, Vector("Low", "High"))
      )
    )
  }

  test("the JSON schema does not show the id of a tool call, also in a sequence") {
    val schema = Shape.canonicalDerived[TmHolder].jsonSchema
    val items  = Vector("properties", "calls", "items", "properties").foldLeft(schema: Option[DynamicValue]) {
      case (Some(record: DynamicValue.Record), key) => DynamicValues.recordGet(record, key)
      case _                                        => None
    }
    items match
      case Some(record: DynamicValue.Record) =>
        assertEquals(DynamicValues.recordKeys(record).toSet, Set("name", "args"))
      case other => fail(s"no item properties: $other")
  }

  test("a marker with literal arguments is the same marker for the codec and for the macros") {
    assertEquals(ZioSchemaCodec.typeRefForSchema(using Shape.canonicalSchema[TmLiteralMarked]), TypeRef.history)
    val layout = Signature.fromType[(text: String) => TmLiteralMarked].layout
    assertEquals(layout.outputFields.map(field => field.name -> field.typeRef), Vector("result" -> TypeRef.history))
  }

  test("a ToolCall return type is not one value: it keeps the fields of the record") {
    val layout = Signature.fromType[(text: String) => ToolCall].layout
    assertEquals(layout.outputFields.map(_.name), Vector("name", "args", "id"))
  }
