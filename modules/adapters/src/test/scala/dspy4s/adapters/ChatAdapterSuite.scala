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

  test("format builds system + demo + final user messages using marker framing") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get
      .withInstructions(Some("Be concise."))
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
    assertEquals(
      messages.map(_.role),
      Vector(MessageRole.System, MessageRole.User, MessageRole.Assistant, MessageRole.User)
    )
    val system = messages.head.text.get
    assert(system.contains("Your input fields are: question."), s"system: $system")
    assert(system.contains("Your output fields are: answer."), s"system: $system")
    assert(system.contains("[[ ## question ## ]]"), s"system: $system")
    assert(system.contains("[[ ## answer ## ]]"), s"system: $system")
    assert(system.contains("[[ ## completed ## ]]"), s"system: $system")
    assert(system.contains("Be concise."), s"system: $system")

    // Demo user message uses input marker; demo assistant uses output + completed.
    val demoUser = messages(1).text.get
    assert(demoUser.contains("[[ ## question ## ]]\nCapital of France?"), s"demo user: $demoUser")

    val demoAssistant = messages(2).text.get
    assert(demoAssistant.contains("[[ ## answer ## ]]\nParis"), s"demo assistant: $demoAssistant")
    assert(demoAssistant.contains("[[ ## completed ## ]]"), s"demo assistant: $demoAssistant")

    // Final user message includes a reminder about the marker output format.
    val mainUser = messages(3).text.get
    assert(mainUser.contains("[[ ## question ## ]]\nCapital of Belgium?"), s"main user: $mainUser")
    assert(mainUser.contains("[[ ## answer ## ]]"), s"main user reminder: $mainUser")
    assert(mainUser.contains("[[ ## completed ## ]]"), s"main user reminder: $mainUser")
  }

  test("parse extracts marker-delimited fields and applies type coercion") {
    val signature = SignatureDsl.parse("question -> answer, score: float").toOption.get

    given RuntimeContext = RuntimeEnvironment.current
    val completion =
      """[[ ## answer ## ]]
        |Brussels
        |
        |[[ ## score ## ]]
        |0.91
        |
        |[[ ## completed ## ]]""".stripMargin
    val parsed = ChatAdapter().parse(signature, LmOutput(text = completion))

    assert(parsed.isRight, s"parse failed: ${parsed.left.toOption.map(_.message).getOrElse("?")}")
    val values = parsed.toOption.get.values
    assertEquals(values("answer"), "Brussels")
    assertEquals(values("score"), 0.91)
  }

  test("parse preserves multi-line field values verbatim") {
    val signature = SignatureDsl.parse("question -> reasoning, answer").toOption.get
    given RuntimeContext = RuntimeEnvironment.current

    val completion =
      """[[ ## reasoning ## ]]
        |First I consider X.
        |Then I weigh Y.
        |Finally I conclude Z.
        |
        |[[ ## answer ## ]]
        |42
        |
        |[[ ## completed ## ]]""".stripMargin
    val parsed = ChatAdapter().parse(signature, LmOutput(text = completion))
    assert(parsed.isRight)
    val values = parsed.toOption.get.values
    assertEquals(
      values("reasoning"),
      "First I consider X.\nThen I weigh Y.\nFinally I conclude Z."
    )
    assertEquals(values("answer"), "42")
  }

  test("parse tolerates a missing completed marker") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    given RuntimeContext = RuntimeEnvironment.current
    val parsed = ChatAdapter().parse(signature, LmOutput(text = "[[ ## answer ## ]]\nBrussels"))
    assert(parsed.isRight)
    assertEquals(parsed.toOption.get.values("answer"), "Brussels")
  }

  test("parse falls back to full text for single output signatures when no markers present") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    given RuntimeContext = RuntimeEnvironment.current
    val parsed = ChatAdapter().parse(signature, LmOutput(text = "Brussels"))
    assert(parsed.isRight)
    assertEquals(parsed.toOption.get.values("answer"), "Brussels")
  }

  test("parse fails when a required output field is missing from the markers") {
    val signature = SignatureDsl.parse("question -> answer, score: float").toOption.get
    given RuntimeContext = RuntimeEnvironment.current
    val parsed = ChatAdapter().parse(signature, LmOutput(text = "[[ ## answer ## ]]\nBrussels"))
    assert(parsed.isLeft)
    assert(parsed.left.toOption.get.isInstanceOf[ParseError])
  }

  test("parse skips unknown markers without polluting tracked fields") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    given RuntimeContext = RuntimeEnvironment.current
    val completion =
      """[[ ## thinking ## ]]
        |hallucinated section
        |
        |[[ ## answer ## ]]
        |Brussels
        |
        |[[ ## completed ## ]]""".stripMargin
    val parsed = ChatAdapter().parse(signature, LmOutput(text = completion))
    assert(parsed.isRight)
    assertEquals(parsed.toOption.get.values("answer"), "Brussels")
    assert(!parsed.toOption.get.values.contains("thinking"))
  }
