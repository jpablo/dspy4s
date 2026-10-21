package dspy4s.signatures

import munit.FunSuite

/** An `Option` field of a typed signature is an optional field of the layout (decision D5, PORT_GAPS G-29). */
class OptionalFieldSpecSuite extends FunSuite:

  test("an Option field gives an optional field spec, and each other field a required one") {
    import ZsOptionProbe.given
    val specs = ZioSchemaCodec.derivedFromZioSchema[ZsOptionProbe].fieldSpecs
    assertEquals(specs.map(spec => spec.name -> spec.optional), Vector("amount" -> true, "note" -> false))
  }
