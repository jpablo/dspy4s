package dspy4s.signatures

import dspy4s.core.contracts.TypeRef
import munit.FunSuite
import zio.blocks.schema.Schema

final case class DtDefaults(
    count: Int          = 3,
    name : String       = "x",
    ratio: Double       = 0.5,
    flag : Boolean      = true,
    tags : List[String] = Nil,
    plain: Int
) derives Schema

/** A field with a default value has the type of its value. Before, zio-blocks wrapped such a field in
  * `Reflect.Deferred`, and the derivation gave it `TypeRef.json`, so the prompt showed an `Int` field as a `dict`.
  */
class DefaultFieldTypeRefSuite extends FunSuite:

  private val expected = Vector(
    "count" -> TypeRef.int,
    "name"  -> TypeRef.string,
    "ratio" -> TypeRef.double,
    "flag"  -> TypeRef.bool,
    "tags"  -> TypeRef.list,
    "plain" -> TypeRef.int
  )

  test("each field of a record with default values has the type of its value") {
    val specs = ZioSchemaCodec.derivedFromZioSchema[DtDefaults].fieldSpecs
    assertEquals(specs.map(spec => spec.name -> spec.typeRef), expected)
  }

  test("Signature.derived gives a field with a default value the type of its value") {
    val layout = Signature.derived[PtDefaultsIn, DtDefaults]("Defaults").layout
    assertEquals(layout.outputFields.map(spec => spec.name -> spec.typeRef), expected)
  }

final case class PtDefaultsIn(question: String) derives Schema
