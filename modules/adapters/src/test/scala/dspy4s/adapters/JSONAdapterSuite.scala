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

class JSONAdapterSuite extends FunSuite:
  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("format injects json output contract into system message") {
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
    val formatted = JSONAdapter().format(invocation)

    assert(formatted.isRight)
    val messages = formatted.toOption.get.messages
    assertEquals(messages.head.role, MessageRole.System)
    assert(messages.head.text.get.contains("valid JSON object"))
    assert(messages(2).role == MessageRole.Assistant)
    assert(messages(2).text.get.contains("\"answer\""))
    assert(messages.last.text.get.contains("Question: Capital of Belgium?"))
  }

  test("parse reads plain json payload and coerces scalar types") {
    val signature = SignatureDsl.parse("question -> answer, score: float, ok: bool").toOption.get

    given RuntimeContext = RuntimeEnvironment.current
    val parsed = JSONAdapter().parse(
      signature,
      LmOutput(text = """{"answer":"Brussels","score":0.91,"ok":true}""")
    )

    assert(parsed.isRight)
    val values = parsed.toOption.get.values
    assertEquals(values("answer"), "Brussels")
    assertEquals(values("score"), 0.91)
    assertEquals(values("ok"), true)
  }

  test("parse supports fenced json payloads") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    val text =
      """```json
        |{"answer":"Brussels"}
        |```""".stripMargin

    given RuntimeContext = RuntimeEnvironment.current
    val parsed = JSONAdapter().parse(signature, LmOutput(text = text))

    assert(parsed.isRight)
    assertEquals(parsed.toOption.get.values("answer"), "Brussels")
  }

  test("parse fails when json payload is malformed") {
    val signature = SignatureDsl.parse("question -> answer, score: float").toOption.get

    given RuntimeContext = RuntimeEnvironment.current
    val parsed = JSONAdapter().parse(signature, LmOutput(text = "{not-json}"))

    assert(parsed.isLeft)
    assert(parsed.left.toOption.get.isInstanceOf[ParseError])
  }

  test("parse falls back to raw text for single-output signatures") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get

    given RuntimeContext = RuntimeEnvironment.current
    val parsed = JSONAdapter().parse(signature, LmOutput(text = "Brussels"))

    assert(parsed.isRight)
    assertEquals(parsed.toOption.get.values("answer"), "Brussels")
    assertEquals(parsed.toOption.get.metadata("fallback"), "text")
  }
