package dspy4s.typed

import zio.blocks.schema.Schema

import dspy4s.core.contracts.TypeRef
import munit.FunSuite

// Characterization fixture: a structured product field for the builder matrix.
case class PinProduct(a: String, b: Int) derives Schema

/** Pins the `TypeRef` the `SignatureBuilder` assigns to each supported field
  * type. Primitives and enums are pinned in `Phase3SurfacesSuite`; this suite
  * locks the remaining types (collections, Option, structured products) so the
  * `FieldCodec` -> `Schema` consolidation of the builder is provably
  * behavior-preserving. */
class BuilderTypeRefPinSuite extends FunSuite:

  private def builderTypeRef(build: SignatureBuilder => SignatureBuilder): TypeRef =
    build(Signature.builder("Pin")).build.fields.find(_.name == "f").get.typeRef

  test("List field -> json") {
    assertEquals(builderTypeRef(_.output[List[String]]("f")), TypeRef.json)
  }

  test("Vector field -> json") {
    assertEquals(builderTypeRef(_.output[Vector[Int]]("f")), TypeRef.json)
  }

  test("Seq field -> json") {
    assertEquals(builderTypeRef(_.output[Seq[Double]]("f")), TypeRef.json)
  }

  test("Set field -> json") {
    assertEquals(builderTypeRef(_.output[Set[String]]("f")), TypeRef.json)
  }

  test("Map field -> json") {
    assertEquals(builderTypeRef(_.output[Map[String, List[String]]]("f")), TypeRef.json)
  }

  // Option reflects its *element* type at the wire boundary (an optional string
  // is a string that may be absent). This matches what the case-class and
  // spec-macro paths already produce for an Option field -- they share
  // `ZioSchemaCodec.typeRefForSchema`. The pre-consolidation builder was the
  // outlier: `FieldCodec.option` hardcoded `json` for every Option.
  test("Option field reflects its element type (string), consistent with case-class derivation") {
    assertEquals(builderTypeRef(_.output[Option[String]]("f")), TypeRef.string)
  }

  test("structured product field -> json") {
    assertEquals(builderTypeRef(_.output[PinProduct]("f")), TypeRef.json)
  }
