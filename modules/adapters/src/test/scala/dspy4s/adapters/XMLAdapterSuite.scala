package dspy4s.adapters

import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.MessageRole
import munit.FunSuite
import zio.blocks.schema.DynamicValue

class XMLAdapterSuite extends FunSuite:
  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)
  private def lookup(rec: DynamicValue.Record, key: String): Option[Any] =
    DynamicValues.recordGet(rec, key).map(DynamicValues.toAny)

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("format shows the field tags, the demo tags, and the input tags") {
    val signature  = SignatureDsl.parse("question -> answer, score: float").toOption.get
    val invocation = AdapterInvocation(
      layout = signature,
      demos = Vector(
        Example(
          values = rec("question" := "Capital of France?", "answer" := "Paris", "score" := 0.95),
          inputKeys = Set("question")
        )
      ),
      inputs = Example(values = rec("question" := "Capital of Belgium?"), inputKeys = Set("question")),
      request = LmRequest(model = "openai/test")
    )

    given RuntimeContext = RuntimeEnvironment.current
    val formatted        = XMLAdapter().format(invocation)

    assert(formatted.isRight)
    val messages = formatted.toOption.get.messages
    assertEquals(messages.head.role, MessageRole.System)
    assert(messages.head.text.get.contains("<answer>\n{answer}\n</answer>"), messages.head.text.get)
    assertEquals(messages(2).text.get, "<answer>\nParis\n</answer>\n\n<score>\n0.95\n</score>")
    assert(messages.last.text.get.startsWith("<question>\nCapital of Belgium?\n</question>"), messages.last.text.get)
  }

  test("parse reads xml fields and coerces scalar types") {
    val signature = SignatureDsl.parse("question -> answer, score: float, ok: bool").toOption.get
    val xml       = "<outputs><answer>Brussels</answer><score>0.91</score><ok>true</ok></outputs>"

    given RuntimeContext = RuntimeEnvironment.current
    val parsed           = XMLAdapter().parse(signature, LmOutput(text = xml))

    assert(parsed.isRight)
    val values = parsed.toOption.get.values
    assertEquals(lookup(values, "answer"), Some("Brussels": Any))
    assertEquals(lookup(values, "score"), Some(0.91: Any))
    assertEquals(lookup(values, "ok"), Some(true: Any))
  }

  // Upstream `XMLAdapter.parse` reads `<dspy_root>completion</dspy_root>`: the prompt asks for one tag per field.
  test("parse reads the field tags at the top level, with no root element") {
    val signature = SignatureDsl.parse("question -> answer, score: float").toOption.get
    val xml       = "<answer>\nBrussels\n</answer>\n\n<score>\n0.91\n</score>"

    given RuntimeContext = RuntimeEnvironment.current
    val parsed           = XMLAdapter().parse(signature, LmOutput(text = xml))

    assert(parsed.isRight, parsed.toString)
    assertEquals(lookup(parsed.toOption.get.values, "answer"), Some("Brussels": Any))
    assertEquals(lookup(parsed.toOption.get.values, "score"), Some(0.91: Any))
  }

  test("parse supports fenced xml payloads") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    val text      = """```xml
        |<outputs><answer>Brussels</answer></outputs>
        |```""".stripMargin

    given RuntimeContext = RuntimeEnvironment.current
    val parsed           = XMLAdapter().parse(signature, LmOutput(text = text))

    assert(parsed.isRight)
    assertEquals(lookup(parsed.toOption.get.values, "answer"), Some("Brussels": Any))
  }

  test("parse fails when xml is malformed") {
    val signature = SignatureDsl.parse("question -> answer, score: float").toOption.get

    given RuntimeContext = RuntimeEnvironment.current
    val parsed           = XMLAdapter().parse(signature, LmOutput(text = "<outputs><answer>oops</outputs>"))

    assert(parsed.isLeft)
    assert(parsed.left.toOption.get.isInstanceOf[ParseError])
  }

  test("parse falls back to raw text for single-output signatures") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get

    given RuntimeContext = RuntimeEnvironment.current
    val parsed           = XMLAdapter().parse(signature, LmOutput(text = "Brussels"))

    assert(parsed.isRight)
    assertEquals(lookup(parsed.toOption.get.values, "answer"), Some("Brussels": Any))
  }
