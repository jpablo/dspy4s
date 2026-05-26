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
