package dspy4s.adapters

import dspy4s.adapters.internal.AdapterTextSupport
import dspy4s.core.contracts.{:=, DynamicValues, FieldSpec, JsonSchemaRefs, RuntimeContext, SignatureLayout, TypeRef}
import dspy4s.lm.contracts.LmOutput
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** The bugs of the code review of the adapters internals and contracts: each test failed before its fix. */
class AdapterInternalsReviewSuite extends FunSuite:

  private given RuntimeContext = RuntimeContext()

  private def long(n: Long): DynamicValue = DynamicValue.Primitive(PrimitiveValue.Long(n))

  private val count = FieldSpec("count", typeRef = TypeRef.int)

  private val layout = SignatureLayout.of(
    name = "Count",
    inputFields = Vector(FieldSpec("question")),
    outputFields = Vector(count),
    instructions = None
  )

  test("a required int output above Int.MaxValue keeps its value, as a Python int") {
    assertEquals(AdapterTextSupport.outputTextValue(count, "3000000000"), Right(long(3000000000L)))
  }

  test("the JSON adapter reads a required int above Int.MaxValue") {
    val parsed = JSONAdapter().parse(layout, LmOutput(text = """{"count": 3000000000}"""))
    assertEquals(parsed.map(_.values), Right(DynamicValues.record("count" -> long(3000000000L))))
  }

  test("an optional field with allowed values takes only None and null as a null") {
    val sentiment = FieldSpec("sentiment", enumValues = Vector("pos", "neg"), optional = true)
    assertEquals(AdapterTextSupport.outputTextValue(sentiment, "None"), Right(DynamicValue.Null))
    assertEquals(AdapterTextSupport.outputTextValue(sentiment, "null"), Right(DynamicValue.Null))
    Vector("n/a", "", "none").foreach { text =>
      assert(AdapterTextSupport.outputTextValue(sentiment, text).isLeft, s"the text '$text' must be an error")
    }
  }

  test("a $ref path decodes the JSON pointer escapes ~1 and ~0") {
    val target = DynamicValues.record("type" := "string")
    val root   = DynamicValues.record("$defs" -> DynamicValues.record("a/b~c" -> target))
    assertEquals(JsonSchemaRefs.lookup(root, "#/$defs/a~1b~0c"), Right(target))
  }
