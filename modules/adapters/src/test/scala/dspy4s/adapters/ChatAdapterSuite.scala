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

class ChatAdapterSuite extends FunSuite:
  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("format builds system demo and input messages") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get.withInstructions(Some("Be concise."))
    val invocation = AdapterInvocation(
      signature = signature,
      demos = Vector(
        ExampleData(
          values = Map("question" -> "Capital of France?", "answer" -> "Paris"),
          inputKeys = Set("question")
        )
      ),
      inputs = ExampleData(values = Map("question" -> "Capital of Belgium?"), inputKeys = Set("question")),
      request = LmRequest(model = "openai/test", mode = LmMode.Chat)
    )

    given RuntimeContext = RuntimeEnvironment.current
    val formatted = ChatAdapter().format(invocation)

    assert(formatted.isRight)
    val messages = formatted.toOption.get.messages
    assertEquals(messages.map(_.role), Vector(MessageRole.System, MessageRole.User, MessageRole.Assistant, MessageRole.User))
    assertEquals(messages.head.text.get, "Be concise.")
    assert(messages(1).text.get.contains("Question: Capital of France?"))
    assert(messages(2).text.get.contains("Answer: Paris"))
    assert(messages(3).text.get.contains("Question: Capital of Belgium?"))
  }

  test("parse extracts labeled fields and applies type coercion") {
    val signature = SignatureDsl.parse("question -> answer, score: float").toOption.get

    given RuntimeContext = RuntimeEnvironment.current
    val parsed = ChatAdapter().parse(
      signature,
      LmOutput(text = "Answer: Brussels\nScore: 0.91")
    )

    assert(parsed.isRight)
    val values = parsed.toOption.get.values
    assertEquals(values("answer"), "Brussels")
    assertEquals(values("score"), 0.91)
  }

  test("parse falls back to full text for single output signatures") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get

    given RuntimeContext = RuntimeEnvironment.current
    val parsed = ChatAdapter().parse(signature, LmOutput(text = "Brussels"))

    assert(parsed.isRight)
    assertEquals(parsed.toOption.get.values("answer"), "Brussels")
  }

  test("parse fails when a required output field is missing") {
    val signature = SignatureDsl.parse("question -> answer, score: float").toOption.get

    given RuntimeContext = RuntimeEnvironment.current
    val parsed = ChatAdapter().parse(signature, LmOutput(text = "Answer: Brussels"))

    assert(parsed.isLeft)
    assert(parsed.left.toOption.get.isInstanceOf[ParseError])
  }
