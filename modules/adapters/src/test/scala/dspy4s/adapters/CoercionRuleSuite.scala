package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.internal.AdapterTextSupport
import dspy4s.core.contracts.{DynamicValues, FieldSpec, RuntimeContext, SignatureLayout, TypeRef}
import dspy4s.lm.contracts.LmOutput
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** The output values of the adapters take the one rule of `LenientValues.valueOf`, on each adapter, for a required
  * field and for an optional field. An optional field differs only in its null texts. Each test failed before the
  * change.
  */
class CoercionRuleSuite extends FunSuite:

  private given RuntimeContext = RuntimeContext()

  private def int(n   : Int): DynamicValue     = DynamicValue.Primitive(PrimitiveValue.Int(n))
  private def double(n: Double): DynamicValue  = DynamicValue.Primitive(PrimitiveValue.Double(n))
  private def bool(b  : Boolean): DynamicValue = DynamicValue.Primitive(PrimitiveValue.Boolean(b))

  private val count     = FieldSpec("count", typeRef = TypeRef.int)
  private val maybe     = FieldSpec("count", typeRef = TypeRef.int, optional = true)
  private val price     = FieldSpec("price", typeRef = TypeRef.double)
  private val flag      = FieldSpec("flag", typeRef = TypeRef.bool)
  private val maybeFlag = FieldSpec("flag", typeRef = TypeRef.bool, optional = true)

  private def layout(output: FieldSpec): SignatureLayout =
    SignatureLayout.of(name = "Sig", inputFields = Vector(FieldSpec("question")), outputFields = Vector(output), None)

  private def valueOf(adapter: Adapter, field: FieldSpec, text: String) =
    adapter.parse(layout(field), LmOutput(text = text)).map(parsed =>
      DynamicValues.recordGet(parsed.values, field.name)
    )

  test("a required int takes the lenient number forms, and no Java-only form") {
    assertEquals(AdapterTextSupport.outputTextValue(count, "$1,200"), Right(int(1200)))
    assertEquals(AdapterTextSupport.outputTextValue(count, "1_000"), Right(int(1000)))
    assertEquals(AdapterTextSupport.outputTextValue(count, "1e3"), Right(int(1000)))
    Vector("3d", "0x1p3", "3.5", "n/a", "None", "").foreach { text =>
      assert(AdapterTextSupport.outputTextValue(count, text).isLeft, s"the text '$text' must be an error")
    }
  }

  test("an optional int has the rule of a required int, and also the null texts") {
    assertEquals(AdapterTextSupport.outputTextValue(maybe, "$1,200"), Right(int(1200)))
    Vector("None", "null", "n/a", "none", "").foreach { text =>
      assertEquals(AdapterTextSupport.outputTextValue(maybe, text), Right(DynamicValue.Null), text)
    }
    Vector("3d", "0x1p3", "3.5").foreach { text =>
      assert(AdapterTextSupport.outputTextValue(maybe, text).isLeft, s"the text '$text' must be an error")
    }
  }

  test("a required float takes the lenient number forms, and no Java-only form") {
    assertEquals(AdapterTextSupport.outputTextValue(price, "$2,399.50"), Right(double(2399.5)))
    assertEquals(AdapterTextSupport.outputTextValue(price, "1_000"), Right(double(1000.0)))
    Vector("3d", "0x1p3", "n/a").foreach { text =>
      assert(AdapterTextSupport.outputTextValue(price, text).isLeft, s"the text '$text' must be an error")
    }
  }

  test("a bool takes the texts of pydantic lax mode") {
    assertEquals(AdapterTextSupport.outputTextValue(flag, "yes"), Right(bool(true)))
    assertEquals(AdapterTextSupport.outputTextValue(flag, "off"), Right(bool(false)))
    assert(AdapterTextSupport.outputTextValue(flag, "maybe").isLeft)
    assert(AdapterTextSupport.outputTextValue(flag, "n/a").isLeft)
    assertEquals(AdapterTextSupport.outputTextValue(maybeFlag, "n/a"), Right(DynamicValue.Null))
  }

  test("each adapter gives one value for one text") {
    val replies = Vector[(Adapter, String)](
      ChatAdapter() -> "[[ ## count ## ]]\n$1,200\n\n[[ ## completed ## ]]",
      JSONAdapter() -> """{"count": "$1,200"}""",
      XMLAdapter()  -> "<count>$1,200</count>"
    )
    replies.foreach { (adapter, text) =>
      assertEquals(valueOf(adapter, count, text), Right(Some(int(1200))), adapter.name)
    }
  }

  test("a JSON value takes the rule of its type") {
    val adapter = JSONAdapter()
    assertEquals(valueOf(adapter, count, """{"count": 3.0}"""), Right(Some(int(3))))
    assert(valueOf(adapter, count, """{"count": 3.5}""").isLeft)
    assertEquals(valueOf(adapter, flag, """{"flag": 1}"""), Right(Some(bool(true))))
    assertEquals(valueOf(adapter, flag, """{"flag": 0}"""), Right(Some(bool(false))))
    assert(valueOf(adapter, flag, """{"flag": 2}""").isLeft)
    assertEquals(valueOf(adapter, price, """{"price": 3}"""), Right(Some(double(3.0))))
  }

  test("a decimal comma is an error, not a larger number") {
    assert(AdapterTextSupport.outputTextValue(price, "3,5").isLeft)
    assert(AdapterTextSupport.outputTextValue(count, "1,2").isLeft)
  }
