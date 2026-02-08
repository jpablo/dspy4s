package dspy4s.adapters

import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.core.contracts.ExampleData
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.MessageRole
import munit.FunSuite

class XMLAdapterSuite extends FunSuite:
  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("format injects xml schema instructions and demo xml") {
    val signature = SignatureDsl.parse("question -> answer, score: float").toOption.get
    val invocation = AdapterInvocation(
      signature = signature,
      demos = Vector(
        ExampleData(
          values = Map("question" -> "Capital of France?", "answer" -> "Paris", "score" -> 0.95),
          inputKeys = Set("question")
        )
      ),
      inputs = ExampleData(values = Map("question" -> "Capital of Belgium?"), inputKeys = Set("question")),
      request = LmRequest(model = "openai/test", mode = LmMode.Chat)
    )

    given RuntimeContext = RuntimeEnvironment.current
    val formatted = XMLAdapter().format(invocation)

    assert(formatted.isRight)
    val messages = formatted.toOption.get.messages
    assertEquals(messages.head.role, MessageRole.System)
    assert(messages.head.text.get.contains("<outputs>"))
    assert(messages(2).text.get.contains("<answer>Paris</answer>"))
    assert(messages.last.text.get.contains("Question: Capital of Belgium?"))
  }

  test("parse reads xml fields and coerces scalar types") {
    val signature = SignatureDsl.parse("question -> answer, score: float, ok: bool").toOption.get
    val xml = "<outputs><answer>Brussels</answer><score>0.91</score><ok>true</ok></outputs>"

    given RuntimeContext = RuntimeEnvironment.current
    val parsed = XMLAdapter().parse(signature, LmOutput(text = xml))

    assert(parsed.isRight)
    val values = parsed.toOption.get.values
    assertEquals(values("answer"), "Brussels")
    assertEquals(values("score"), 0.91)
    assertEquals(values("ok"), true)
  }

  test("parse supports fenced xml payloads") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    val text =
      """```xml
        |<outputs><answer>Brussels</answer></outputs>
        |```""".stripMargin

    given RuntimeContext = RuntimeEnvironment.current
    val parsed = XMLAdapter().parse(signature, LmOutput(text = text))

    assert(parsed.isRight)
    assertEquals(parsed.toOption.get.values("answer"), "Brussels")
  }

  test("parse fails when xml is malformed") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get

    given RuntimeContext = RuntimeEnvironment.current
    val parsed = XMLAdapter().parse(signature, LmOutput(text = "<outputs><answer>oops</outputs>"))

    assert(parsed.isLeft)
    assert(parsed.left.toOption.get.isInstanceOf[ParseError])
  }
