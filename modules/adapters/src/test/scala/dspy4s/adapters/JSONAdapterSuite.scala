package dspy4s.adapters

import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.core.contracts.:=
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
  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
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
          values = rec("question" := "Capital of France?", "answer" := "Paris", "score" := 0.95),
          inputKeys = Set("question")
        )
      ),
      inputs = Example(values = rec("question" := "Capital of Belgium?"), inputKeys = Set("question")),
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
      inputs           = Example(values = rec("question" := "x"), inputKeys = Set("question")),
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
      inputs = Example(values = rec("question" := "x"), inputKeys = Set("question")),
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

  test("parse extracts prose-embedded json with braces inside string values") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get

    given RuntimeContext = RuntimeEnvironment.current
    // Model wraps the JSON in prose (so the direct start-{/end-} path is skipped and
    // extractFirstJsonObject runs), and the string value itself contains } and {.
    val parsed = JSONAdapter().parse(
      signature,
      LmOutput(text = """Sure, here you go: {"answer":"use the } brace { here"} -- hope that helps""")
    )

    assert(parsed.isRight, s"expected Right, got $parsed")
    assertEquals(lookup(parsed.toOption.get.values, "answer"), Some("use the } brace { here": Any))
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

  // C1 — empty/null LM response must be a parse error (dspy 3.2.1 base.py raises
  // AdapterParseError("The LM returned an empty or null response.") instead of
  // null-filling output fields). dspy4s never null-fills: structured parse fails
  // (no JSON object), and the single-output text fallback explicitly refuses
  // empty text -> ParseError. Multi-output skips the fallback entirely -> ParseError.
  test("C1: parse of an empty response for a single-output signature is a ParseError") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    given RuntimeContext = RuntimeEnvironment.current
    for blank <- Vector("", "   ", "\n\n") do
      val parsed = JSONAdapter().parse(signature, LmOutput(text = blank))
      assert(parsed.isLeft, s"expected Left for blank text '${blank.replace("\n", "\\n")}', got $parsed")
      assert(
        parsed.left.toOption.get.isInstanceOf[ParseError],
        s"expected ParseError, got ${parsed.left.toOption.get}"
      )
  }

  test("C1: parse of an empty response for a multi-output signature is a ParseError") {
    val signature = SignatureDsl.parse("question -> answer, score: float").toOption.get
    given RuntimeContext = RuntimeEnvironment.current
    for blank <- Vector("", "   ", "\n\n") do
      val parsed = JSONAdapter().parse(signature, LmOutput(text = blank))
      assert(parsed.isLeft, s"expected Left for blank text '${blank.replace("\n", "\\n")}', got $parsed")
      assert(
        parsed.left.toOption.get.isInstanceOf[ParseError],
        s"expected ParseError, got ${parsed.left.toOption.get}"
      )
  }

  // A1 — non-ASCII characters must be emitted literally (not \uXXXX escaped).
  // dspy 3.2.1 json_adapter.py uses json.dumps(..., ensure_ascii=False); dspy4s
  // formats demo assistant messages via ujson.write, which writes UTF-8 literally.
  test("A1: format emits non-ASCII demo values literally in the assistant JSON (no \\uXXXX)") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    val invocation = AdapterInvocation(
      layout = signature,
      demos = Vector(
        Example(
          values = rec("question" := "Une boisson?", "answer" := "café naïve 日本語"),
          inputKeys = Set("question")
        )
      ),
      inputs = Example(values = rec("question" := "x"), inputKeys = Set("question")),
      request = LmRequest(model = "openai/test", mode = LmMode.Chat)
    )
    given RuntimeContext = RuntimeEnvironment.current
    val all = JSONAdapter().format(invocation).toOption.get.messages.flatMap(_.text).mkString("\n")

    assert(all.contains("café naïve 日本語"), s"expected literal non-ASCII in assistant JSON: $all")
    assert(!all.contains("\\u"), s"non-ASCII was \\u-escaped in assistant JSON: $all")
  }

  // Companion of A1 on the parse/round-trip side: a json output field whose value
  // is itself a nested JSON object containing non-ASCII must round-trip literally
  // through value.render() (JSONAdapter coerce for TypeRef.json -> JsonDynamic).
  test("A1: parse round-trips non-ASCII inside a nested json output field literally") {
    val signature = SignatureDsl.parse("question -> payload: json").toOption.get
    given RuntimeContext = RuntimeEnvironment.current
    val parsed = JSONAdapter().parse(
      signature,
      LmOutput(text = """{"payload":{"drink":"café","note":"naïve 日本語"}}""")
    )
    assert(parsed.isRight, s"expected Right, got $parsed")
    val rendered = DynamicValues.renderText(
      DynamicValues.recordGet(parsed.toOption.get.values, "payload").get
    )
    assert(rendered.contains("café"), s"expected literal 'café' in rendered payload: $rendered")
    assert(rendered.contains("naïve"), s"expected literal 'naïve' in rendered payload: $rendered")
    assert(rendered.contains("日本語"), s"expected literal '日本語' in rendered payload: $rendered")
    assert(!rendered.contains("\\u"), s"non-ASCII was \\u-escaped in rendered payload: $rendered")
  }
