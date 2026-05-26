package dspy4s.prototype

import munit.FunSuite

class ExperimentSuite extends FunSuite:

  test("Schema.derived produces a Record reflect with the expected fields") {
    val fields = Experiment.listFields[Experiment.ClassifyInput]
    assertEquals(fields, Vector("sentence" -> "primitive"))
  }

  test("Schema.derived for an enum produces a Variant reflect") {
    import zio.blocks.schema.{Reflect, Schema}
    val schema = summon[Schema[Experiment.Sentiment]]
    assert(
      schema.reflect.isInstanceOf[Reflect.Variant[?, ?]],
      s"expected Variant, got ${schema.reflect.getClass.getSimpleName}"
    )
  }

  test("ClassifyOutput round-trips through DynamicValue") {
    val out = Experiment.ClassifyOutput(Experiment.Sentiment.joy)
    val result = Experiment.roundtripOutput(out)
    assertEquals(result, Right(out))
  }

  test("ClassifyInput round-trips through DynamicValue") {
    val in = Experiment.ClassifyInput("Best movie ever!")
    val result = Experiment.roundtripInput(in)
    assertEquals(result, Right(in))
  }

  test("Schema.derived works for a named-tuple type alias") {
    import zio.blocks.schema.{Reflect, Schema}
    import Experiment.namedTupleSchema
    val schema = summon[Schema[Experiment.SimpleInput]]
    schema.reflect match
      case rec: Reflect.Record[?, ?] =>
        val names = rec.fields.toVector.map(_.name)
        assertEquals(names, Vector("sentence", "lang"))
      case other =>
        fail(s"expected Record reflect for named tuple, got: ${other.getClass.getSimpleName}")
  }

  test("named tuple round-trips through DynamicValue") {
    import zio.blocks.schema.{DynamicValue, Schema}
    import Experiment.namedTupleSchema
    val schema = summon[Schema[Experiment.SimpleInput]]
    val value: Experiment.SimpleInput = (sentence = "Best movie ever!", lang = "en")
    val dyn = schema.toDynamicValue(value)

    // Inspect the shape: should be a Record with two fields.
    dyn match
      case rec: DynamicValue.Record =>
        val byName = rec.fields.toMap
        assertEquals(byName.keySet, Set("sentence", "lang"))
      case other =>
        fail(s"expected Record, got: ${other.getClass.getSimpleName}")

    // Round-trip back to the named tuple.
    val decoded = schema.fromDynamicValue(dyn)
    assertEquals(decoded, Right(value))
  }

  test("DynamicValue shape for ClassifyOutput is a Record-of-Variant") {
    import zio.blocks.schema.{DynamicValue, Schema}
    val schema = summon[Schema[Experiment.ClassifyOutput]]
    val dyn    = schema.toDynamicValue(Experiment.ClassifyOutput(Experiment.Sentiment.joy))
    dyn match
      case rec: DynamicValue.Record =>
        // Expect one field "sentiment" containing a Variant.
        val sentiment = rec.fields.toMap.get("sentiment")
        assert(sentiment.isDefined, s"missing 'sentiment' in $rec")
        sentiment.get match
          case v: DynamicValue.Variant =>
            // Verify the variant case tag matches the enum case name.
            assertEquals(v.caseName, Some("joy"))
          case other =>
            fail(s"expected Variant for 'sentiment', got: ${other.getClass.getSimpleName}")
      case other =>
        fail(s"expected Record, got ${other.getClass.getSimpleName}")
  }
