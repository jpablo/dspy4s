package dspy4s.adapters

import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.MessageRole
import munit.FunSuite
import zio.blocks.schema.DynamicValue

class JSONAdapterSuite extends FunSuite:
  private def rec(entries: (String, Any)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)
  private def lookup(rec: DynamicValue.Record, key: String): Option[Any] =
    DynamicValues.recordGet(rec, key).map(DynamicValues.toAny)


  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("format injects json output contract into system message") {
    val signature = SignatureDsl.parse("question -> answer, score: float").toOption.get
    val invocation = AdapterInvocation(
      layout = signature,
      demos = Vector(
        Example(
          values = rec("question" -> "Capital of France?", "answer" -> "Paris", "score" -> 0.95),
          inputKeys = Set("question")
        )
      ),
      inputs = Example(values = rec("question" -> "Capital of Belgium?"), inputKeys = Set("question")),
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

  test("format inlines outputJsonSchema in the system message when supplied") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    val schemaString =
      """{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"]}"""
    val invocation = AdapterInvocation(
      layout           = signature,
      demos            = Vector.empty,
      inputs           = Example(values = rec("question" -> "x"), inputKeys = Set("question")),
      request          = LmRequest(model = "openai/test", mode = LmMode.Chat),
      outputJsonSchema = Some(schemaString)
    )

    given RuntimeContext = RuntimeEnvironment.current
    val formatted = JSONAdapter().format(invocation)

    assert(formatted.isRight)
    val systemText = formatted.toOption.get.messages.head.text.get
    assert(systemText.contains("conforms to the following JSON Schema"),
      s"expected schema-aware instruction, got:\n$systemText")
    assert(systemText.contains(schemaString),
      s"expected schema inlined in system message, got:\n$systemText")
  }

  test("format falls back to the keys-list instruction when outputJsonSchema is None") {
    val signature = SignatureDsl.parse("question -> answer, score").toOption.get
    val invocation = AdapterInvocation(
      layout = signature,
      demos  = Vector.empty,
      inputs = Example(values = rec("question" -> "x"), inputKeys = Set("question")),
      request = LmRequest(model = "openai/test", mode = LmMode.Chat)
      // outputJsonSchema defaults to None
    )

    given RuntimeContext = RuntimeEnvironment.current
    val systemText = JSONAdapter().format(invocation).toOption.get.messages.head.text.get
    assert(systemText.contains("exactly these keys: answer, score"),
      s"expected key-list instruction, got:\n$systemText")
    assert(!systemText.contains("JSON Schema"),
      s"unexpected schema mention in fallback instruction:\n$systemText")
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
    assertEquals(lookup(values, "answer"), Some("Brussels": Any))
    assertEquals(lookup(values, "score"), Some(0.91: Any))
    assertEquals(lookup(values, "ok"), Some(true: Any))
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
    assertEquals(lookup(parsed.toOption.get.values, "answer"), Some("Brussels": Any))
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
    assertEquals(lookup(parsed.toOption.get.values, "answer"), Some("Brussels": Any))
    assertEquals(parsed.toOption.get.metadata("fallback"), "text")
  }
