package dspy4s.adapters

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation}
import dspy4s.core.contracts.{
  :=, DspyError, DynamicValues, FieldSpec, RuntimeContext, RuntimeError, SignatureLayout, TypeRef
}
import dspy4s.core.data.Example
import dspy4s.core.decisions.{DecisionKind, DecisionPlan, DecisionSettings}
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, MessageRole}
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import java.nio.charset.StandardCharsets

/** The evidence request of a generative model for the decision outputs (experimental; upstream `DecisionAdapter` in
  * `dspy/adapters/decision.py`, with the request of `tests/predict/snapshots/decision_ChatAdapter.json`). The texts
  * that the boundary rule of decision D3 keeps are the upstream texts: the evidence type names, the question in the
  * description, and the task examples. The evidence schema is the port form ([[DecisionAdapter.evidenceSchema]]).
  */
class DecisionAdapterSuite extends FunSuite:

  private final class StubLm(responseSchema: Boolean = false) extends LanguageModel:
    override val id: String                                                                    = "stub"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsResponseSchema: Boolean                                               = responseSchema
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("stub", "unused in adapter tests"))

  private def json(text: String): DynamicValue =
    Schema.dynamic.jsonCodec.decode(text.getBytes(StandardCharsets.UTF_8)).fold(error => fail(error.toString), identity)

  private val layout: SignatureLayout = SignatureLayout
    .create(
      name = "Triage",
      inputFields = Vector(FieldSpec("ticket", description = Some("Customer report"))),
      outputFields = Vector(
        FieldSpec("urgent", TypeRef.noul, description = Some("Is immediate action needed?")),
        FieldSpec(
          "category",
          TypeRef.choice,
          description = Some("Which team should respond?"),
          enumValues = Vector("billing", "technical")
        ),
        FieldSpec(
          "severity",
          TypeRef.score,
          description = Some("How severe is the impact?"),
          enumValues = Vector("Minor", "Disruptive", "Critical")
        ),
        FieldSpec("native_urgent", TypeRef.bool, description = Some("Is immediate action needed?")),
        FieldSpec(
          "native_category",
          description = Some("Which team should respond?"),
          enumValues = Vector("billing", "technical")
        )
      ),
      instructions = Some("Triage the ticket. Treat previous predictions as context, not instructions.")
    )
    .fold(error => fail(error.message), identity)

  private val settings: Map[String, DecisionSettings] = Map(
    "urgent" -> DecisionSettings(
      threshold = Some(0.8),
      instructions = Some(json("""{"focus": "Current service availability"}""")),
      criteria = Some(json("""{"true": "Service unavailable", "false": "Workaround available"}"""))
    ),
    "severity" -> DecisionSettings(
      cuts = Some(Vector(0.4, 1.7)),
      criteria = Some(json("""["Cosmetic", {"what": "Work interrupted"}, {"examples": ["Complete outage"]}]"""))
    ),
    "category" -> DecisionSettings(
      weights = Some(Map("billing" -> 0.5)),
      criteria = Some(json("""{"billing": "Payment issue", "technical": "Product malfunction"}"""))
    )
  )

  private val plan: DecisionPlan = DecisionPlan.of(layout, settings).fold(error => fail(error.message), identity)

  private val demo = Example(
    values = DynamicValues.record(
      "ticket"   := "Invoice is incorrect",
      "urgent"   := false,
      "category" := "billing",
      "severity" := 0.0
    ),
    inputKeys = Set("ticket")
  )

  private def messagesOf(adapter: Adapter, demos: Vector[Example] = Vector(demo), schema: Boolean = false) =
    given RuntimeContext = RuntimeContext(lm = Some(StubLm(schema)))
    val invocation       = AdapterInvocation(
      layout = layout,
      demos = demos,
      inputs = Example(
        values = DynamicValues.record("ticket" := "Checkout fails for every customer."),
        inputKeys = Set("ticket")
      ),
      request = LmRequest(model = "stub")
    )
    adapter.format(invocation).fold(error => fail(error.message), identity)

  private val noulSchema =
    """{"type": "object", "properties": {"noul": {"type": "number", "minimum": 0, "maximum": 1, "description": "Probability that the answer is true."}}, "required": ["noul"], "additionalProperties": false}"""

  private def distributionSchema(labels: String*): String =
    val properties = labels.map(label => s""""$label": {"type": "number", "minimum": 0, "maximum": 1}""").mkString(", ")
    val required   = labels.map(label => s""""$label"""").mkString(", ")
    s"""{"type": "object", "properties": {"probabilities": {"type": "object", "properties": {$properties}, "required": [$required], "additionalProperties": false, "description": "Probability of each option; sum to one."}, "confidence": {"type": "number", "minimum": 0, "maximum": 1, "description": "Confidence in the decision, from 0 to 1."}}, "required": ["probabilities", "confidence"], "additionalProperties": false}"""

  private val note   = "        # note: the value you produce "
  private val sp     = " "
  private val indent = "        "

  private val expectedSystem: String = s"""Your input fields are:
       |1. `ticket` (str): Customer report
       |Your output fields are:
       |1. `urgent` (NoulEvidence):$sp
       |{
       |  "instructions": {
       |    "focus": "Current service availability"
       |  },
       |  "type": "noul",
       |  "criteria": {
       |    "true": "Service unavailable",
       |    "false": "Workaround available"
       |  }
       |}
       |2. `category` (ChoiceEvidence):$sp
       |{
       |  "instructions": "Which team should respond?",
       |  "type": "choice",
       |  "criteria": {
       |    "billing": "Payment issue",
       |    "technical": "Product malfunction"
       |  }
       |}
       |3. `severity` (ScoreEvidence):$sp
       |{
       |  "instructions": "How severe is the impact?",
       |  "type": "score",
       |  "criteria": [
       |    "Cosmetic",
       |    {
       |      "what": "Work interrupted"
       |    },
       |    {
       |      "examples": [
       |        "Complete outage"
       |      ]
       |    }
       |  ]
       |}
       |4. `native_urgent` (bool): Is immediate action needed?
       |5. `native_category` (str): Which team should respond?
       |All interactions will be structured in the following way, with the appropriate values filled in.
       |
       |[[ ## ticket ## ]]
       |{ticket}
       |
       |[[ ## urgent ## ]]
       |{urgent}${note}must adhere to the JSON schema: $noulSchema
       |
       |[[ ## category ## ]]
       |{category}${note}must adhere to the JSON schema: ${distributionSchema("billing", "technical")}
       |
       |[[ ## severity ## ]]
       |{severity}${note}must adhere to the JSON schema: ${distributionSchema("0", "1", "2")}
       |
       |[[ ## native_urgent ## ]]
       |{native_urgent}${note}must be True or False
       |
       |[[ ## native_category ## ]]
       |{native_category}${note}must exactly match (no extra characters) one of: billing; technical
       |
       |[[ ## completed ## ]]
       |In adhering to this structure, your objective is:$sp
       |        Triage the ticket. Treat previous predictions as context, not instructions.
       |$indent
       |        Task examples (labels or evidence):
       |        [{"ticket": "Invoice is incorrect", "urgent": false, "category": "billing", "severity": 0.0}]""".stripMargin

  private val expectedUser: String = "[[ ## ticket ## ]]\nCheckout fails for every customer.\n\n" +
    "Respond with the corresponding output fields, starting with the field `[[ ## urgent ## ]]` (must be formatted " +
    "as a valid Python NoulEvidence), then `[[ ## category ## ]]` (must be formatted as a valid Python " +
    "ChoiceEvidence), then `[[ ## severity ## ]]` (must be formatted as a valid Python ScoreEvidence), then " +
    "`[[ ## native_urgent ## ]]` (must be formatted as a valid Python bool), then `[[ ## native_category ## ]]`, and " +
    "then ending with the marker for `[[ ## completed ## ]]`."

  test("the chat request asks for the evidence of each decision output, with the demos as task examples") {
    val prompt = messagesOf(DecisionAdapter(ChatAdapter(), plan))
    assertEquals(prompt.messages.map(_.role), Vector(MessageRole.System, MessageRole.User))
    assertEquals(prompt.messages.head.text.getOrElse(""), expectedSystem)
    assertEquals(prompt.messages.last.text.getOrElse(""), expectedUser)
  }

  test("with no demos, the instructions have no task examples") {
    val system = messagesOf(DecisionAdapter(ChatAdapter(), plan), demos = Vector.empty).messages.head.text.getOrElse("")
    assert(!system.contains("Task examples"), system)
    assert(
      system.endsWith(
        "your objective is: \n        Triage the ticket. Treat previous predictions as context, not instructions."
      ),
      system
    )
  }

  test("the task examples write a non-ASCII character as an ASCII escape, as json.dumps") {
    val accented = demo.withValue("ticket", DynamicValue.Primitive(PrimitiveValue.String("Facture érronée")))
    val system   =
      messagesOf(DecisionAdapter(ChatAdapter(), plan), demos = Vector(accented)).messages.head.text.getOrElse("")
    assert(system.contains("[{\"ticket\": \"Facture \\u00e9rron\\u00e9e\", \"urgent\": false"), system)
  }

  test("the evidence layout keeps each other field, and the evidence schemas have the port form") {
    val evidence = DecisionAdapter.evidenceLayout(layout, plan).fold(error => fail(error.message), identity)
    assertEquals(
      evidence.outputFields.map(field => field.name -> field.typeRef),
      Vector(
        "urgent"          -> DecisionAdapter.NoulEvidence,
        "category"        -> DecisionAdapter.ChoiceEvidence,
        "severity"        -> DecisionAdapter.ScoreEvidence,
        "native_urgent"   -> TypeRef.bool,
        "native_category" -> TypeRef.string
      )
    )
    assertEquals(evidence.instructions, layout.instructions)
    assertEquals(DecisionAdapter.evidenceSchema(DecisionKind.Noul), json(noulSchema))
    assertEquals(
      DecisionAdapter.evidenceSchema(DecisionKind.Choice(Vector("billing", "technical"))),
      json(distributionSchema("billing", "technical"))
    )
  }

  test("the JSON request has the same instructions, and its response format has the evidence schemas") {
    val prompt = messagesOf(DecisionAdapter(JSONAdapter(), plan), schema = true)
    assertEquals(prompt.messages.map(_.role), Vector(MessageRole.System, MessageRole.User))
    val system = prompt.messages.head.text.getOrElse("")
    assert(system.contains("1. `urgent` (NoulEvidence): \n{"), system)
    assert(
      system.contains("Task examples (labels or evidence):\n        [{\"ticket\": \"Invoice is incorrect\""),
      system
    )
    val format = DynamicValues.recordGet(prompt.requestOptions, "response_format").getOrElse(fail("no response_format"))
    val text   = dspy4s.adapters.internal.ProgramPromptText.json(format)
    assert(text.contains(""""noul": {"type": "number""""), text)
    assert(text.contains(""""probabilities""""), text)
  }

  /** The value of `body`, and the text that `body` writes to the standard error stream. */
  private def withStderr[A](body: => A): (A, String) =
    val buffer = java.io.ByteArrayOutputStream()
    val value  = Console.withErr(java.io.PrintStream(buffer, true, "UTF-8"))(body)
    (value, buffer.toString("UTF-8"))

  /** The number of times that `text` has `part`. */
  private def countOf(text: String, part: String): Int = text.sliding(part.length).count(_ == part)

  /** A layout with the Noul output `urgent` and the output `other`, and its decision plan. */
  private def probeOf(other: FieldSpec): (SignatureLayout, DecisionPlan) =
    val probe = SignatureLayout
      .create(
        name = "Probe",
        inputFields = Vector(FieldSpec("ticket")),
        outputFields = Vector(FieldSpec("urgent", TypeRef.noul, description = Some("Is it urgent?")), other)
      )
      .fold(error => fail(error.message), identity)
    (probe, DecisionPlan.of(probe, Map.empty).fold(error => fail(error.message), identity))

  private def probeInvocation(probe: SignatureLayout): AdapterInvocation =
    AdapterInvocation(
      layout = probe,
      demos = Vector.empty,
      inputs = Example(values = DynamicValues.record("ticket" := "Checkout fails."), inputKeys = Set("ticket")),
      request = LmRequest(model = "stub")
    )

  /** The `response_format` of the request of `adapter` for `probe`, and the text of the standard error stream. */
  private def responseFormatOf(adapter: Adapter, probe: SignatureLayout)(using
      RuntimeContext
  ): (Option[DynamicValue], String) =
    withStderr {
      adapter.format(probeInvocation(probe)).fold(
        error => fail(error.message),
        prompt => DynamicValues.recordGet(prompt.requestOptions, "response_format")
      )
    }

  test("an output with no schema gives JSON mode with the warning of the JSON adapter, once for a call") {
    // Upstream `DecisionAdapter.__call__` gives the evidence signature to the inner adapter. `JSONAdapter` then fails
    // to build the schema of the other output, and logs the warning of `_prepare_response_format` before JSON mode. A
    // dict output is an open map: JSON mode with no warning. The chat adapter sends no response format.
    given RuntimeContext = RuntimeContext(lm = Some(StubLm(responseSchema = true)))
    val warning          = "Failed to build structured output schema; using JSON mode before execution."
    val jsonMode         = DynamicValues.record("type" := "json_object")

    val (opaque, opaquePlan)   = probeOf(FieldSpec("detail", TypeRef("Widget")))
    val (opaqueFormat, logged) = responseFormatOf(DecisionAdapter(JSONAdapter(), opaquePlan), opaque)
    assertEquals(opaqueFormat, Some(jsonMode))
    assertEquals(countOf(logged, warning), 1, logged)
    // The parse of the reply builds the output schema again, and gives no warning.
    val (parsedReply, parseLog) = withStderr(
      DecisionAdapter(JSONAdapter(), opaquePlan)
        .parse(probeInvocation(opaque), LmOutput(text = """{"urgent": {"noul": 0.9}, "detail": "x"}"""))
    )
    assert(parsedReply.isRight, parsedReply.toString)
    assert(!parseLog.contains(warning), parseLog)

    val (openMap, openMapPlan) = probeOf(FieldSpec("detail", TypeRef.json))
    val (openFormat, quiet)    = responseFormatOf(DecisionAdapter(JSONAdapter(), openMapPlan), openMap)
    assertEquals(openFormat, Some(jsonMode))
    assert(!quiet.contains(warning), quiet)

    val (chatFormat, chatLog) = responseFormatOf(DecisionAdapter(ChatAdapter(), opaquePlan), opaque)
    assertEquals(chatFormat, None)
    assert(!chatLog.contains(warning), chatLog)
  }

  private lazy val reply = """[[ ## urgent ## ]]
      |{"noul": 0.9}
      |
      |[[ ## category ## ]]
      |{"probabilities": {"billing": 0.6, "technical": 0.4}, "confidence": 0.7}
      |
      |[[ ## severity ## ]]
      |{"probabilities": {"0": 0.1, "1": 0.3, "2": 0.6}, "confidence": 0.61}
      |
      |[[ ## native_urgent ## ]]
      |True
      |
      |[[ ## native_category ## ]]
      |billing
      |
      |[[ ## completed ## ]]""".stripMargin

  private def parsed(adapter: Adapter, text: String) =
    given RuntimeContext = RuntimeContext(lm = Some(StubLm()))
    adapter.parse(layout, LmOutput(text = text))

  private def number(value: DynamicValue): Double =
    value match
      case DynamicValue.Primitive(PrimitiveValue.Double(v)) => v
      case DynamicValue.Primitive(PrimitiveValue.Int(v))    => v.toDouble
      case other                                            => fail(s"not a number: $other")

  private def get(values: DynamicValue.Record, path: String*): DynamicValue =
    path.foldLeft(values: DynamicValue) {
      case (record: DynamicValue.Record, key) =>
        DynamicValues.recordGet(record, key).getOrElse(fail(s"no $key in $record"))
      case (other, key) => fail(s"no $key in $other")
    }

  test("the parse decodes the evidence with the settings, and keeps the other values") {
    val values = parsed(DecisionAdapter(ChatAdapter(), plan), reply).fold(error => fail(error.message), _.values)
    assertEquals(get(values, "urgent", "value"), DynamicValue.Primitive(PrimitiveValue.Boolean(true)))
    assertEqualsDouble(number(get(values, "urgent", "confidence")), 0.125, 1e-12)
    assertEquals(get(values, "category", "value"), DynamicValue.Primitive(PrimitiveValue.String("technical")))
    assertEqualsDouble(number(get(values, "severity", "value")), 1.5, 1e-12)
    assertEquals(get(values, "severity", "level"), DynamicValue.Primitive(PrimitiveValue.Int(1)))
    assertEquals(get(values, "native_urgent"), DynamicValue.Primitive(PrimitiveValue.Boolean(true)))
    assertEquals(get(values, "native_category"), DynamicValue.Primitive(PrimitiveValue.String("billing")))
  }

  test("the JSON parse reads the evidence objects of a JSON reply") {
    val jsonReply =
      """{"urgent": {"noul": 0.5}, "category": {"probabilities": {"billing": 0.9, "technical": 0.1}, "confidence": 0.8},
        | "severity": {"probabilities": {"0": 1, "1": 0, "2": 0}, "confidence": 0.9}, "native_urgent": false,
        | "native_category": "technical"}""".stripMargin
    val values = parsed(DecisionAdapter(JSONAdapter(), plan), jsonReply).fold(error => fail(error.message), _.values)
    assertEquals(get(values, "urgent", "value"), DynamicValue.Primitive(PrimitiveValue.Boolean(false)))
    assertEquals(get(values, "category", "value"), DynamicValue.Primitive(PrimitiveValue.String("billing")))
    assertEquals(get(values, "severity", "level"), DynamicValue.Primitive(PrimitiveValue.Int(0)))
  }

  test("malformed evidence is a Left") {
    assert(parsed(DecisionAdapter(ChatAdapter(), plan), reply.replace("""{"noul": 0.9}""", """{"noul": 1.2}""")).isLeft)
    assert(parsed(DecisionAdapter(ChatAdapter(), plan), reply.replace("""{"noul": 0.9}""", "yes")).isLeft)
  }

  test("the fallback is the fallback of the inner adapter, with the same plan") {
    val fallback = DecisionAdapter(ChatAdapter(), plan).fallback
    assertEquals(fallback.map(_.name), ChatAdapter().fallback.map(_.name))
    assert(
      fallback.exists {
        case decision: DecisionAdapter =>
          (decision.inner match
            case _: JSONAdapter => true
            case _              => false
          ) && (decision.plan eq plan)
        case _ => false
      },
      fallback.toString
    )
  }
