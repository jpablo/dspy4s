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

  test("Schema.toJsonSchema renders primitives, enums, and required fields correctly") {
    import zio.blocks.schema.Schema

    case class Mix(name: String, count: Int, value: Double, flag: Boolean, mood: Experiment.Sentiment) derives Schema
    val rendered = summon[Schema[Mix]].toJsonSchema.toJson.toString

    // Each primitive maps to the right JSON Schema type. (zio-blocks pretty-prints with " : " spacing.)
    assert(rendered.contains("\"string\""),  s"missing string type: $rendered")
    assert(rendered.contains("\"integer\""), s"missing integer type: $rendered")
    assert(rendered.contains("\"number\""),  s"missing number type: $rendered")
    assert(rendered.contains("\"boolean\""), s"missing boolean type: $rendered")
    // Enum renders as enum [...] with case names.
    assert(rendered.contains("\"sadness\""), s"missing enum case 'sadness': $rendered")
    assert(rendered.contains("\"joy\""), s"missing enum case 'joy': $rendered")
    // All fields appear in `required`.
    assert(rendered.contains("\"name\""), s"missing field name: $rendered")
    assert(rendered.contains("\"count\""), s"missing field count: $rendered")
    assert(rendered.contains("\"value\""), s"missing field value: $rendered")
    assert(rendered.contains("\"flag\""), s"missing field flag: $rendered")
    assert(rendered.contains("\"mood\""), s"missing field mood: $rendered")
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

  test("fromDynamicValue is strict: rejects a String where Boolean is expected") {
    import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

    // Construct a DynamicValue.Record by hand that has the same shape as ClassifyOutput would expect
    // (sentiment field) but uses a String "joy" where the Variant is expected.
    // Simpler probe: ask a Boolean schema to decode a String.
    val boolSchema = summon[Schema[Boolean]]
    val strInput   = DynamicValue.Primitive(PrimitiveValue.String("true"))
    val result     = boolSchema.fromDynamicValue(strInput)
    assert(
      result.isLeft,
      s"expected fromDynamicValue to reject 'true' as Boolean; got: $result"
    )
  }

  test("fromDynamicValue is strict: rejects a String where Int is expected") {
    import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}
    val intSchema = summon[Schema[Int]]
    val result    = intSchema.fromDynamicValue(DynamicValue.Primitive(PrimitiveValue.String("42")))
    assert(result.isLeft, s"expected fromDynamicValue to reject '42' as Int; got: $result")
  }

  test("a manual normalizer can coerce String -> Boolean before decode") {
    import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}
    val boolSchema = summon[Schema[Boolean]]
    val strInput   = DynamicValue.Primitive(PrimitiveValue.String("true"))
    val normalized = coerce(strInput, expectedKind = "boolean")
    val result     = boolSchema.fromDynamicValue(normalized)
    assertEquals(result, Right(true))
  }

  /** Minimal coercion helper: rewrite a `DynamicValue.Primitive(String(...))` into the expected primitive kind.
    * A full version would walk the whole DynamicValue tree against the target Reflect. */
  private def coerce(dyn: zio.blocks.schema.DynamicValue, expectedKind: String): zio.blocks.schema.DynamicValue =
    import zio.blocks.schema.{DynamicValue, PrimitiveValue}
    (dyn, expectedKind) match
      case (DynamicValue.Primitive(PrimitiveValue.String(s)), "boolean") =>
        s.trim.toLowerCase match
          case "true"  => DynamicValue.Primitive(PrimitiveValue.Boolean(true))
          case "false" => DynamicValue.Primitive(PrimitiveValue.Boolean(false))
          case _       => dyn
      case (DynamicValue.Primitive(PrimitiveValue.String(s)), "int") =>
        s.trim.toIntOption.fold(dyn)(i => DynamicValue.Primitive(PrimitiveValue.Int(i)))
      case _ => dyn

  test("fieldSpecsFrom walks a Reflect.Record and produces per-field metadata") {
    import Experiment.BaseOutput.given
    val specs = Experiment.fieldSpecsFrom(summon[zio.blocks.schema.Schema[Experiment.BaseOutput]].reflect)
    assertEquals(specs.map(_.name), Vector("answer", "score"))
    assertEquals(specs.map(_.typeKind), Vector("primitive", "primitive"))
  }

  test("fieldSpecsFrom surfaces enum cases on Variant-typed fields as dspy.enum.cases") {
    import Experiment.ClassifyOutput.given
    val specs = Experiment.fieldSpecsFrom(summon[zio.blocks.schema.Schema[Experiment.ClassifyOutput]].reflect)
    assertEquals(specs.size, 1)
    val sentiment = specs.head
    assertEquals(sentiment.name, "sentiment")
    assertEquals(sentiment.typeKind, "variant")
    assertEquals(sentiment.metadata.get("dspy.enum.cases"), Some("sadness,joy,love"))
  }

  test("augmentedLayout prepends reasoning to the base output's field list") {
    import Experiment.BaseOutput.given
    val layout = Experiment.augmentedLayout[Experiment.BaseOutput]("ReasonedOutput")
    assertEquals(layout.name, "ReasonedOutput")
    assertEquals(layout.fields.map(_.name), Vector("reasoning", "answer", "score"))
  }

  test("base Schema[O] is untouched by augmentation; round-trip still works") {
    import Experiment.BaseOutput.given
    val schema = summon[zio.blocks.schema.Schema[Experiment.BaseOutput]]
    val value  = Experiment.BaseOutput(answer = "Paris", score = 0.95)
    val dyn    = schema.toDynamicValue(value)
    assertEquals(schema.fromDynamicValue(dyn), Right(value))
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
