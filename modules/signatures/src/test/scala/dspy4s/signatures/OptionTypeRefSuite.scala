package dspy4s.signatures

import dspy4s.core.contracts.TypeRef
import munit.FunSuite
import zio.blocks.schema.Schema

enum OtColor derives Schema:
  case red, green

final case class OtAddress(street: String) derives Schema
final case class OtFields(
    text   : Option[String],
    count  : Option[Int],
    big    : Option[Long],
    ratio  : Option[Double],
    flag   : Option[Boolean],
    words  : Option[List[String]],
    address: Option[OtAddress],
    color  : Option[OtColor]
) derives Schema

/** An `Option` field has the type of its element, and it is optional (the comment of `BuilderTypeRefPinSuite`: "Option
  * reflects its element type at the wire boundary"). Before, each `Option` field had the type `string`, so an
  * `Option[Int]` output showed as a text field in the prompt.
  */
class OptionTypeRefSuite extends FunSuite:

  private val expected: Vector[(String, TypeRef, Vector[String])] = Vector(
    ("text", TypeRef.string, Vector.empty),
    ("count", TypeRef.int, Vector.empty),
    ("big", TypeRef.int, Vector.empty),
    ("ratio", TypeRef.double, Vector.empty),
    ("flag", TypeRef.bool, Vector.empty),
    ("words", TypeRef.list, Vector.empty),
    ("address", TypeRef.json, Vector.empty),
    ("color", TypeRef.string, Vector("red", "green"))
  )

  test("each Option field of a record has the type and the enum values of its element") {
    val specs = ZioSchemaCodec.derivedFromZioSchema[OtFields].fieldSpecs
    assertEquals(specs.map(spec => (spec.name, spec.typeRef, spec.enumValues)), expected)
    assert(specs.forall(_.optional), specs.toString)
  }

  test("the signature builder gives an Option field the type of its element") {
    val layout = Signature.builder("Builder").input[String]("question").output[Option[Int]]("count")
      .output[Option[OtAddress]]("address").output[Option[OtColor]]("color").build
    assertEquals(
      layout.outputFields.map(spec => (spec.name, spec.typeRef, spec.enumValues)),
      Vector(
        ("count", TypeRef.int, Vector.empty),
        ("address", TypeRef.json, Vector.empty),
        ("color", TypeRef.string, Vector("red", "green"))
      )
    )
  }
