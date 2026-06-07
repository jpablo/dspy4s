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

class ChatAdapterSuite extends FunSuite:
  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)
  private def lookup(rec: DynamicValue.Record, key: String): Option[Any] =
    DynamicValues.recordGet(rec, key).map(DynamicValues.toAny)


  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("format builds system + demo + final user messages using marker framing") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get
      .withInstructions(Some("Be concise."))
    val invocation = AdapterInvocation(
      layout = signature,
      demos = Vector(
        Example(
          values = rec("question" := "Capital of France?", "answer" := "Paris"),
          inputKeys = Set("question")
        )
      ),
      inputs = Example(values = rec("question" := "Capital of Belgium?"), inputKeys = Set("question")),
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
    assert(system.contains("Your input fields are:"), s"system: $system")
    assert(system.contains("1. `question` (str)"), s"system: $system")
    assert(system.contains("Your output fields are:"), s"system: $system")
    assert(system.contains("1. `answer` (str)"), s"system: $system")
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

  test("format emits type hints in the system prompt's structure example for non-string outputs") {
    val signature = SignatureDsl.parse("question -> answer: int, score: float, flag: bool, payload: json").toOption.get
    val invocation = AdapterInvocation(
      layout = signature,
      demos = Vector.empty,
      inputs = Example(values = rec("question" := "?"), inputKeys = Set("question")),
      request = LmRequest(model = "x", mode = LmMode.Chat)
    )
    given RuntimeContext = RuntimeEnvironment.current
    val system = ChatAdapter().format(invocation).toOption.get.messages.head.text.get

    // The structure example must annotate each non-string output with a
    // `# note:` comment showing the typing constraint.
    assert(system.contains("# note: the value you produce must be a single int value"), system)
    assert(system.contains("# note: the value you produce must be a single float value"), system)
    assert(system.contains("# note: the value you produce must be true or false"), system)
    assert(system.contains("# note: the value you produce must be a valid JSON object"), system)
    // String fields get no note.
    val questionLine = system.linesIterator.find(_.contains("{question}")).getOrElse("")
    assert(!questionLine.contains("# note:"), s"string input should not carry a type note: '$questionLine'")
  }

  test("format emits type hints in the final user reminder for non-string outputs") {
    val signature = SignatureDsl.parse("q -> answer: int, summary").toOption.get
    val invocation = AdapterInvocation(
      layout = signature,
      demos = Vector.empty,
      inputs = Example(values = rec("q" := "?"), inputKeys = Set("q")),
      request = LmRequest(model = "x", mode = LmMode.Chat)
    )
    given RuntimeContext = RuntimeEnvironment.current
    val mainUser = ChatAdapter().format(invocation).toOption.get.messages.last.text.get

    // Output reminder names each output marker; non-string fields get a
    // parenthetical type hint, strings don't.
    assert(
      mainUser.contains("`[[ ## answer ## ]]` (must be formatted as a valid int)"),
      s"main user reminder missing int hint: $mainUser"
    )
    // The `summary` field is str and should not carry a parenthetical.
    assert(
      mainUser.contains("`[[ ## summary ## ]]`,") || mainUser.contains("`[[ ## summary ## ]]`, and"),
      s"main user reminder should reference summary without a type hint: $mainUser"
    )
  }

  test("format emits FieldSpec.description when one is set, omits it when default") {
    val signature = SignatureDsl.parse("q -> answer").toOption.get
      .withFields(
        Vector(
          dspy4s.core.contracts.FieldSpec(
            name = "q",
            role = dspy4s.core.contracts.FieldRole.Input,
            description = Some("The user's question to answer.")
          ),
          dspy4s.core.contracts.FieldSpec(
            name = "answer",
            role = dspy4s.core.contracts.FieldRole.Output
            // description omitted — should fall back to the `${name}` placeholder
            // inserted by FieldSpec.normalize and be suppressed by the renderer.
          )
        )
      )
    val invocation = AdapterInvocation(
      layout = signature,
      demos = Vector.empty,
      inputs = Example(values = rec("q" := "?"), inputKeys = Set("q")),
      request = LmRequest(model = "x", mode = LmMode.Chat)
    )
    given RuntimeContext = RuntimeEnvironment.current
    val system = ChatAdapter().format(invocation).toOption.get.messages.head.text.get

    // The custom description should appear.
    assert(system.contains("The user's question to answer."), s"missing custom desc in: $system")
    // The placeholder default `${answer}` should NOT appear (it should be elided).
    assert(!system.contains("${answer}"), s"placeholder default leaked into system prompt: $system")
  }

  test("format renders field constraints, and nothing extra for unconstrained fields") {
    val signature = SignatureDsl.parse("q -> answer").toOption.get
      .withFields(
        Vector(
          dspy4s.core.contracts.FieldSpec(
            name = "q",
            role = dspy4s.core.contracts.FieldRole.Input
            // no constraints — must render identically to the unconstrained baseline
          ),
          dspy4s.core.contracts.FieldSpec(
            name        = "answer",
            role        = dspy4s.core.contracts.FieldRole.Output,
            description = Some("The model's answer."),
            constraints = Vector(
              dspy4s.core.contracts.FieldConstraints.gt(0),
              dspy4s.core.contracts.FieldConstraints.maxLength(10)
            )
          )
        )
      )
    val invocation = AdapterInvocation(
      layout = signature,
      demos = Vector.empty,
      inputs = Example(values = rec("q" := "?"), inputKeys = Set("q")),
      request = LmRequest(model = "x", mode = LmMode.Chat)
    )
    given RuntimeContext = RuntimeEnvironment.current
    val system = ChatAdapter().format(invocation).toOption.get.messages.head.text.get

    // Constraints render after the description, joined by ", ".
    assert(
      system.contains("Constraints: greater than: 0, maximum length: 10"),
      s"missing constraints line in: $system"
    )
    // The unconstrained input field must NOT carry any "Constraints:" suffix.
    val qLine = system.linesIterator.find(_.contains("`q`")).getOrElse("")
    assert(!qLine.contains("Constraints:"), s"unconstrained field leaked constraints: $qLine")
  }

  test("format renders constraints even when the field has no description") {
    val signature = SignatureDsl.parse("q -> answer").toOption.get
      .withFields(
        Vector(
          dspy4s.core.contracts.FieldSpec(name = "q", role = dspy4s.core.contracts.FieldRole.Input),
          dspy4s.core.contracts.FieldSpec(
            name        = "answer",
            role        = dspy4s.core.contracts.FieldRole.Output,
            constraints = Vector(dspy4s.core.contracts.FieldConstraints.ge(1))
          )
        )
      )
    val invocation = AdapterInvocation(
      layout = signature,
      demos = Vector.empty,
      inputs = Example(values = rec("q" := "?"), inputKeys = Set("q")),
      request = LmRequest(model = "x", mode = LmMode.Chat)
    )
    given RuntimeContext = RuntimeEnvironment.current
    val system = ChatAdapter().format(invocation).toOption.get.messages.head.text.get
    assert(
      system.contains("Constraints: greater than or equal to: 1"),
      s"missing constraints (no-description case) in: $system"
    )
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
    assertEquals(lookup(values, "answer"), Some("Brussels": Any))
    assertEquals(lookup(values, "score"), Some(0.91: Any))
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
      lookup(values, "reasoning"),
      Some("First I consider X.\nThen I weigh Y.\nFinally I conclude Z.": Any)
    )
    assertEquals(lookup(values, "answer"), Some("42": Any))
  }

  test("parse tolerates a missing completed marker") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    given RuntimeContext = RuntimeEnvironment.current
    val parsed = ChatAdapter().parse(signature, LmOutput(text = "[[ ## answer ## ]]\nBrussels"))
    assert(parsed.isRight)
    assertEquals(lookup(parsed.toOption.get.values, "answer"), Some("Brussels": Any))
  }

  test("parse falls back to full text for single output signatures when no markers present") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    given RuntimeContext = RuntimeEnvironment.current
    val parsed = ChatAdapter().parse(signature, LmOutput(text = "Brussels"))
    assert(parsed.isRight)
    assertEquals(lookup(parsed.toOption.get.values, "answer"), Some("Brussels": Any))
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
    assertEquals(lookup(parsed.toOption.get.values, "answer"), Some("Brussels": Any))
    assert(DynamicValues.recordGet(parsed.toOption.get.values, "thinking").isEmpty)
  }

  // C1 — empty/null LM response must be a parse error (dspy 3.2.1 base.py raises
  // AdapterParseError("The LM returned an empty or null response.") instead of
  // null-filling output fields). dspy4s never null-fills; an empty completion
  // yields no marker sections and the single-output text fallback only fires for
  // non-empty text, so the required field is missing -> ParseError.
  test("C1: parse of an empty response for a single-output signature is a ParseError") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    given RuntimeContext = RuntimeEnvironment.current
    for blank <- Vector("", "   ", "\n\n") do
      val parsed = ChatAdapter().parse(signature, LmOutput(text = blank))
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
      val parsed = ChatAdapter().parse(signature, LmOutput(text = blank))
      assert(parsed.isLeft, s"expected Left for blank text '${blank.replace("\n", "\\n")}', got $parsed")
      assert(
        parsed.left.toOption.get.isInstanceOf[ParseError],
        s"expected ParseError, got ${parsed.left.toOption.get}"
      )
  }

  // A1 — non-ASCII characters must be emitted literally (not \uXXXX escaped).
  // ChatAdapter renders demo/field values as plain text (no JSON escaping), so
  // diacritics and CJK must survive verbatim in the formatted prompt.
  test("A1: format emits non-ASCII demo/input values literally (no \\uXXXX escaping)") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    val invocation = AdapterInvocation(
      layout = signature,
      demos = Vector(
        Example(
          values = rec("question" := "Une boisson?", "answer" := "café"),
          inputKeys = Set("question")
        )
      ),
      inputs = Example(values = rec("question" := "naïve ou 日本語?"), inputKeys = Set("question")),
      request = LmRequest(model = "openai/test", mode = LmMode.Chat)
    )
    given RuntimeContext = RuntimeEnvironment.current
    val all = ChatAdapter().format(invocation).toOption.get.messages.flatMap(_.text).mkString("\n")

    assert(all.contains("café"), s"expected literal 'café' in prompt: $all")
    assert(all.contains("naïve"), s"expected literal 'naïve' in prompt: $all")
    assert(all.contains("日本語"), s"expected literal '日本語' in prompt: $all")
    assert(!all.contains("\\u"), s"non-ASCII was \\u-escaped in prompt: $all")
  }
