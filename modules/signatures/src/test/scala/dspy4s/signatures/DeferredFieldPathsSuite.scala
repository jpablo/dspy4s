package dspy4s.signatures

import dspy4s.core.contracts.{FieldSpec, TypeRef}
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.binding.Binding
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Reflect, Schema}

enum DpMood derives Schema:
  case calm, angry

final case class DpFields(
    mood : DpMood         = DpMood.calm,
    note : Option[Int]    = None,
    moods: Option[DpMood] = Some(DpMood.angry),
    count: Int            = 3
) derives Schema

final case class DpTree(label: String, children: List[DpTree]) derives Schema

final case class DpIn(question: String) derives Schema

/** zio-blocks wraps the reflect of a field in `Reflect.Deferred` when the field has a default value, or when the type
  * of the field can be recursive. Each path that reads a field type unwraps it, as `DefaultFieldTypeRefSuite` shows for
  * the primitive fields.
  */
class DeferredFieldPathsSuite extends FunSuite:

  private def described(specs: Vector[FieldSpec]): Vector[(String, TypeRef, Vector[String], Boolean)] =
    specs.map(spec => (spec.name, spec.typeRef, spec.enumValues, spec.optional))

  private val expected = Vector(
    ("mood", TypeRef.string, Vector("calm", "angry"), false),
    ("note", TypeRef.int, Vector.empty, true),
    ("moods", TypeRef.string, Vector("calm", "angry"), true),
    ("count", TypeRef.int, Vector.empty, false)
  )

  private def text(value: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(value))

  test("an enum field and an Option field with a default value have the type, the enum values, and the flag") {
    assertEquals(described(ZioSchemaCodec.derivedFromZioSchema[DpFields].fieldSpecs), expected)
  }

  test("Signature.derived gives an enum field and an Option field with a default value the same specs") {
    val layout = Signature.derived[DpIn, DpFields]("Defaults").layout
    assertEquals(described(layout.outputFields), expected)
  }

  test("a field with a recursive type has the type of its value") {
    assertEquals(
      described(ZioSchemaCodec.derivedFromZioSchema[DpTree].fieldSpecs),
      Vector(("label", TypeRef.string, Vector.empty, false), ("children", TypeRef.list, Vector.empty, false))
    )
  }

  test("the signature builder unwraps a deferred schema") {
    val count: Schema[Int]        = Schema(new Reflect.Deferred[Binding, Int](() => Schema.int.reflect))
    val mood: Schema[DpMood]      = Schema(new Reflect.Deferred[Binding, DpMood](() => Schema[DpMood].reflect))
    val note: Schema[Option[Int]] =
      Schema(new Reflect.Deferred[Binding, Option[Int]](() => Schema[Option[Int]].reflect))
    val layout = Signature.builder("Builder").input[String]("question").output[Int]("count")(using count)
      .output[DpMood]("mood")(using mood).output[Option[Int]]("note")(using note).build
    assertEquals(
      described(layout.outputFields),
      Vector(
        ("count", TypeRef.int, Vector.empty, false),
        ("mood", TypeRef.string, Vector("calm", "angry"), false),
        ("note", TypeRef.int, Vector.empty, true)
      )
    )
  }

  test("the decode coerces the text values of the fields with a default value") {
    val raw = DynamicValue.Record(Chunk(
      "mood"  -> text("angry"),
      "note"  -> text("7"),
      "moods" -> text("calm"),
      "count" -> text("5")
    ))
    assertEquals(
      ZioSchemaCodec.derivedFromZioSchema[DpFields].decode(raw),
      Right(DpFields(mood = DpMood.angry, note = Some(7), moods = Some(DpMood.calm), count = 5))
    )
  }

  test("the decode gives None to an absent Option field with the default value None") {
    val raw = DynamicValue.Record(Chunk("mood" -> text("calm"), "moods" -> text("angry"), "count" -> text("4")))
    assertEquals(
      ZioSchemaCodec.derivedFromZioSchema[DpFields].decode(raw),
      Right(DpFields(mood = DpMood.calm, note = None, moods = Some(DpMood.angry), count = 4))
    )
  }
