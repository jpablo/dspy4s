package dspy4s.programs

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.{ChatAdapter, JSONAdapter, TwoStepAdapter, XMLAdapter}
import dspy4s.core.contracts.{
  :=,
  CodeResult,
  DspyError,
  DynamicValues,
  History,
  ParseError,
  PythonText,
  ReplCodeInterpreter,
  RuntimeContext
}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.core.decisions.{Choice, DecisionSettings, Noul, Score}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.programs.contracts.{ToolFunction, description}
import dspy4s.programs.internal.ToolText
import dspy4s.programs.optimization.ProgramPersistence
import dspy4s.programs.strategies.{BestOfN, ChainOfThought, MultiChainComparison, Predict, RLM, ReActV2}
import dspy4s.signatures.{InputField, OutputField, Signature, Spec}
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, Modifier, PrimitiveValue, Schema}

import scala.collection.mutable.ArrayBuffer

// Top-level fixtures (Mirror derivation needs them outside the suite).
@Modifier.discriminator("kind")
sealed trait ApgShape
object ApgShape:
  @Modifier.alias("ring")
  final case class Circle(radius: Double) extends ApgShape
  case object Dot                         extends ApgShape
  given Schema[ApgShape] = Schema.derived

@Modifier.caseNaming("snake_case")
enum ApgLevel:
  case VeryHigh, VeryLow
object ApgLevel:
  given Schema[ApgLevel] = Schema.derived

sealed trait ApgTone
object ApgTone:
  @Modifier.rename("warm")
  case object Warm extends ApgTone
  @Modifier.alias("icy")
  case object Cold extends ApgTone
  given Schema[ApgTone] = Schema.derived

final case class ApgIn(@Modifier.rename("the_question") question: String)
object ApgIn:
  given Schema[ApgIn] = Schema.derived

final case class ApgOut(
    @Modifier.rename("full_name") name: String,
    @Modifier.rename("n") count       : Int,
    shape                             : ApgShape,
    level                             : ApgLevel
)
object ApgOut:
  given Schema[ApgOut] = Schema.derived

final case class ApgNamed(@Modifier.rename("full_name") name: String)
object ApgNamed:
  given Schema[ApgNamed] = Schema.derived

final case class ApgLabels(tone: ApgTone, level: ApgLevel)
object ApgLabels:
  given Schema[ApgLabels] = Schema.derived

final case class ApgMany(
    shapes                      : List[ApgShape],
    maybe                       : Option[ApgShape],
    level                       : Option[ApgLevel],
    @Modifier.alias("years") age: Int,
    @Modifier.transient() cached: Int = 0
)
object ApgMany:
  given Schema[ApgMany] = Schema.derived

final case class ApgWhy(@Modifier.rename("why") reasoning: String, answer: String)
object ApgWhy:
  given Schema[ApgWhy] = Schema.derived

final case class ApgHidden(
    question                        : String,
    @Modifier.encodeTransient() hint: String = "h",
    @Modifier.transient() cache     : Int    = 0
)
object ApgHidden:
  given Schema[ApgHidden] = Schema.derived

final case class ApgKept(answer: String, @Modifier.encodeTransient() secret: String = "s")
object ApgKept:
  given Schema[ApgKept] = Schema.derived

final case class ApgLost(answer: String, @Modifier.encodeTransient() tags: List[String])
object ApgLost:
  given Schema[ApgLost] = Schema.derived

final case class ApgPast(@Modifier.rename("the_question") question: String, history: History)
object ApgPast:
  given Schema[ApgPast] = Schema.derived

final case class ApgDecided(@Modifier.rename("verdict") urgent: Noul)
object ApgDecided:
  given Schema[ApgDecided] = Schema.derived

final case class ApgScored(@Modifier.rename("sev") severity: Score[ApgLevel], team: Choice[ApgLevel])
object ApgScored:
  given Schema[ApgScored] = Schema.derived

trait ApgSpec extends Spec:
  def question: InputField[String]
  def named: OutputField[ApgNamed]

object ApgMethods:
  def named(name: String): ApgNamed = ApgNamed(name)

  @description("Look up a person.")
  def lookup(query: ApgIn): ApgNamed = ApgNamed(s"found ${query.question}")

/** A renamed output through each program and each reader of a field name, with a scripted model. The prompt has the
  * wire name, and the typed output has the name in the code.
  */
class AnnotatedProgramsSuite extends FunSuite:

  /** A model that gives `replies` in order, and the last reply past the end. */
  private class Script(replies: String*) extends LanguageModel:
    override val id: String              = "scripted"
    override val mode: LmMode            = LmMode.Chat
    val requests: ArrayBuffer[LmRequest] = ArrayBuffer.empty

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      requests += request
      Right(LmResponse(outputs = Vector(LmOutput(text = replies(math.min(requests.size, replies.size) - 1)))))

  private def withModel[A](model: LanguageModel, adapter: Adapter = ChatAdapter())(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(model), adapter = Some(adapter))) {
      body(using RuntimeEnvironment.current)
    }

  private def signature: Signature[ApgIn, ApgOut] =
    Signature.derived[ApgIn, ApgOut](name = "Annotated", instructions = "Give the values.")

  private def expected: ApgOut = ApgOut("a", 3, ApgShape.Circle(1.5), ApgLevel.VeryLow)

  private def wireJson: String =
    """{"full_name": "a", "n": 3, "shape": {"kind": "Circle", "radius": 1.5}, "level": "very_low"}"""

  private def wireChat: String =
    "[[ ## full_name ## ]]\na\n\n[[ ## n ## ]]\n3\n\n" +
      "[[ ## shape ## ]]\n{\"kind\": \"Circle\", \"radius\": 1.5}\n\n[[ ## level ## ]]\nvery_low\n\n[[ ## completed ## ]]"

  private def promptOf(request: LmRequest): String = request.messages.flatMap(_.text).mkString("\n")

  private def jsonRecord(text: String): DynamicValue.Record =
    PythonText.parseJson(text) match
      case Right(found: DynamicValue.Record) => found
      case other                             => fail(s"expected a record, got $other")

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  // ── The allowed values, and the names of a reply ──────────────────────────────────────────────────────────────

  private def labels: Signature[ApgIn, ApgLabels] = Signature.derived[ApgIn, ApgLabels]("Labels", "Give the labels.")

  private def labelChat(tone: String, level: String): String =
    s"[[ ## tone ## ]]\n$tone\n\n[[ ## level ## ]]\n$level\n\n[[ ## completed ## ]]"

  test("an enum takes its wire names as allowed values") {
    val model = Script(labelChat("warm", "very_low"))
    assertEquals(
      withModel(model)(Predict(labels)(ApgIn("q"))).map(_.output),
      Right(ApgLabels(ApgTone.Warm, ApgLevel.VeryLow))
    )
    assert(promptOf(model.requests.head).contains("one of: warm; Cold"), promptOf(model.requests.head))
    assert(!promptOf(model.requests.head).contains("icy"), promptOf(model.requests.head))
  }

  test(
    "a chat reply with the alias of an enum case gives a parse error, and the chat adapter tries its JSON fallback"
  ) {
    val model = Script(labelChat("icy", "very_low"))
    assert(withModel(model)(Predict(labels)(ApgIn("q"))).left.exists(_.isInstanceOf[ParseError]))
    assertEquals(model.requests.size, 2)
  }

  test(
    "a chat reply with the name in the code of an enum case gives a parse error, and the JSON fallback can correct it"
  ) {
    val model = Script(labelChat("warm", "VeryLow"), """{"tone": "warm", "level": "very_low"}""")
    assertEquals(
      withModel(model)(Predict(labels)(ApgIn("q"))).map(_.output),
      Right(ApgLabels(ApgTone.Warm, ApgLevel.VeryLow))
    )
    assertEquals(model.requests.size, 2)
  }

  test("a JSON reply with the alias of an enum case gives a parse error too") {
    val model = Script("""{"tone": "icy", "level": "very_low"}""")
    withModel(model, JSONAdapter())(Predict(labels)(ApgIn("q"))) match
      case Left(error: ParseError) => assert(error.message.contains("allowed values"), error.message)
      case other                   => fail(s"expected a parse error, got $other")
  }

  test("a value of a sealed trait with fields reaches the decode, so the decode reads an alias of its case") {
    val reply = """{"full_name": "a", "n": 3, "shape": {"kind": "ring", "radius": 1.5}, "level": "very_low"}"""
    assertEquals(withModel(Script(reply), JSONAdapter())(Predict(signature)(ApgIn("q"))).map(_.output), Right(expected))
    val chat = "[[ ## full_name ## ]]\na\n\n[[ ## n ## ]]\n3\n\n" +
      "[[ ## shape ## ]]\n{\"ring\": {\"radius\": 1.5}}\n\n[[ ## level ## ]]\nvery_low\n\n[[ ## completed ## ]]"
    assertEquals(withModel(Script(chat))(Predict(signature)(ApgIn("q"))).map(_.output), Right(expected))
  }

  test("an adapter reads an output field only by its wire name, so a name in the code gives a parse error") {
    val reply = """{"name": "a", "n": 3, "shape": {"kind": "Dot"}, "level": "very_low"}"""
    withModel(Script(reply), JSONAdapter())(Predict(signature)(ApgIn("q"))) match
      case Left(error: ParseError) => assert(error.message.contains("full_name"), error.message)
      case other                   => fail(s"expected a parse error, got $other")
  }

  test("the XML format reads a discriminator and a wire name") {
    val reply = """<full_name>a</full_name><n>3</n><shape>{"kind": "Dot"}</shape><level>very_high</level>"""
    assertEquals(
      withModel(Script(reply), XMLAdapter())(Predict(signature)(ApgIn("q"))).map(_.output),
      Right(ApgOut("a", 3, ApgShape.Dot, ApgLevel.VeryHigh))
    )
  }

  test("an input that the encode does not write takes its default in the prompt, and a transient input is not there") {
    val hidden = Signature.derived[ApgHidden, ApgNamed]("Hidden", "Give the name.")
    assertEquals(hidden.layout.inputFields.map(_.name), Vector("question", "hint"))
    val model = Script("[[ ## full_name ## ]]\na\n\n[[ ## completed ## ]]")
    assertEquals(withModel(model)(Predict(hidden)(ApgHidden("q", "other"))).map(_.output), Right(ApgNamed("a")))
    val prompt = promptOf(model.requests.head)
    assert(prompt.contains("[[ ## hint ## ]]\nh"), prompt)
    assert(!prompt.contains("cache"), prompt)
  }

  // ── The strict response format ────────────────────────────────────────────────────────────────────────────────

  private class SchemaScript(replies: String*) extends Script(replies*):
    override def supportsResponseSchema: Boolean = true

  private def strictSchemaOf(model: Script): ujson.Value =
    val format = DynamicValues.recordGet(model.requests.head.options, "response_format").getOrElse(fail("no format"))
    val parsed = ujson.read(PythonText.json(format))
    assertEquals(parsed("type").str, "json_schema", parsed.toString)
    assertEquals(parsed("json_schema")("strict").bool, true, parsed.toString)
    parsed("json_schema")("schema")

  test("the strict format has a closed object with a typed const for each case, in anyOf, and no oneOf") {
    val model  = SchemaScript("""{"full_name": "a", "n": 3, "shape": {"kind": "Dot"}, "level": "very_high"}""")
    val _      = withModel(model, JSONAdapter())(Predict(signature)(ApgIn("q")))
    val schema = strictSchemaOf(model)
    assertEquals(schema("required"), ujson.read("""["full_name", "n", "shape", "level"]"""))
    val cases = schema("properties")("shape")("anyOf").arr.toVector
    assertEquals(
      cases.map(_("properties")("kind")),
      Vector(
        ujson.read("""{"const": "Circle", "type": "string"}"""),
        ujson.read("""{"const": "Dot", "type": "string"}""")
      )
    )
    assertEquals(cases.map(_("required")), Vector(ujson.read("""["kind", "radius"]"""), ujson.read("""["kind"]""")))
    assertEquals(cases.map(_("additionalProperties").bool), Vector(false, false))
    assert(!schema.toString.contains("oneOf"), schema.toString)
    assertEquals(
      ujson.read(schema("properties")("level").toString)("type").str,
      "string",
      schema("properties")("level").toString
    )
  }

  test("the strict format of a list, an Option, and an alias") {
    val many  = Signature.derived[ApgIn, ApgMany]("Many", "Give the values.")
    val model = SchemaScript("""{"shapes": [{"kind": "Dot"}], "maybe": null, "level": "very_low", "age": 1}""")
    assertEquals(
      withModel(model, JSONAdapter())(Predict(many)(ApgIn("q"))).map(_.output),
      Right(ApgMany(List(ApgShape.Dot), None, Some(ApgLevel.VeryLow), 1))
    )
    val schema = strictSchemaOf(model)
    assertEquals(schema("required"), ujson.read("""["shapes", "maybe", "level", "age"]"""))
    assert(schema("properties")("shapes")("items")("anyOf").arr.size == 2, schema.toString)
    assert(!schema.toString.contains("years"), schema.toString)
    assert(!schema.toString.contains("\"ring\""), schema.toString)
    assert(!schema.toString.contains("cached"), schema.toString)
    assert(!schema.toString.contains("allOf"), schema.toString)
  }

  // ── The entry points of a signature ───────────────────────────────────────────────────────────────────────────

  test("Signature.of with a renamed field in its output type") {
    val model  = Script("[[ ## named ## ]]\n{\"full_name\": \"a\"}\n\n[[ ## completed ## ]]")
    val result = withModel(model)(Predict(Signature.of[ApgSpec])((question = "q")))
    assertEquals(result.map(_.output.named), Right(ApgNamed("a")))
    assert(promptOf(model.requests.head).contains("[[ ## named ## ]]"), promptOf(model.requests.head))
    assert(promptOf(model.requests.head).contains("full_name"), promptOf(model.requests.head))
  }

  test("Signature.from with a renamed output: the input has the name in the code of the output") {
    val model  = Script("[[ ## full_name ## ]]\na\n\n[[ ## completed ## ]]")
    val result = withModel(model)(Predict(Signature.from(ApgMethods.named))((name = "q")))
    assertEquals(result.map(_.output.name), Right("a"))
    assert(promptOf(model.requests.head).contains("[[ ## full_name ## ]]"), promptOf(model.requests.head))
  }

  test(
    "ChainOfThought with a field renamed from reasoning: the model writes the two fields, and the output has the renamed"
  ) {
    val base  = Signature.derived[ApgIn, ApgWhy]("Why", "Give the answer.")
    val model =
      Script("[[ ## reasoning ## ]]\nr\n\n[[ ## why ## ]]\nw\n\n[[ ## answer ## ]]\na\n\n[[ ## completed ## ]]")
    val result = withModel(model)(ChainOfThought(base)(ApgIn("q")))
    assertEquals(result.map(found => (found.output.reasoning, found.output.answer)), Right(("w", "a")))
    assertEquals(model.requests.size, 1)
  }

  // ── A tool ────────────────────────────────────────────────────────────────────────────────────────────────────

  test("ToolFunction.fromMethod reads and shows a record argument with a rename, and its result has the wire name") {
    val tool             = ToolFunction.fromMethod(ApgMethods.lookup)
    given RuntimeContext = RuntimeContext()
    val forms            = Vector("""{"the_question": "x"}""", """{"question": "x"}""")
    forms.foreach { form =>
      val args = jsonRecord(s"""{"query": $form}""")
      assertEquals(tool.invoke(args).map(PythonText.json(_)), Right("""{"full_name": "found x"}"""))
    }
    val shown = PythonText.json(ToolText.args(tool))
    assert(shown.contains("the_question"), shown)
    assert(!shown.contains("\"question\""), shown)
  }

  // ── The readers of a name ─────────────────────────────────────────────────────────────────────────────────────

  test("ReActV2: the submit tool has the wire names, and the typed output has the value") {
    val args  = wireJson
    val model = Script(
      "[[ ## next_thought ## ]]\nt\n\n[[ ## tool_calls ## ]]\n" +
        s"""{"tool_calls": [{"name": "submit", "args": $args}]}""" +
        "\n\n[[ ## completed ## ]]"
    )
    val agent  = ReActV2(signature, Vector.empty)
    val result = withModel(model)(agent(ApgIn("q")))
    assertEquals(result.map(_.output), Right(expected))
    assertEquals(result.map(_.raw.values).map(PythonText.json(_)).map(_.contains("\"full_name\": \"a\"")), Right(true))
    val submit = agent.allTools.find(_.name == ReActV2.SubmitToolName).getOrElse(fail("no submit"))
    val shown  = PythonText.json(ToolText.args(submit))
    assert(shown.contains("full_name"), shown)
    assert(shown.contains("const"), shown)
    assert(promptOf(model.requests.head).contains("the_question"), promptOf(model.requests.head))
    assert(promptOf(model.requests.head).contains("`full_name`"), promptOf(model.requests.head))
  }

  private def submitReply(args: String): String =
    "[[ ## next_thought ## ]]\nt\n\n[[ ## tool_calls ## ]]\n" +
      s"""{"tool_calls": [{"name": "submit", "args": $args}]}""" +
      "\n\n[[ ## completed ## ]]"

  test(
    "ReActV2: submit encodes its values, so an encodeTransient output has its default, or the run ends with an error"
  ) {
    val kept  = Signature.derived[ApgIn, ApgKept]("Kept", "Give the values.")
    val model = Script(submitReply("""{"answer": "a", "secret": "other"}"""))
    assertEquals(withModel(model)(ReActV2(kept, Vector.empty)(ApgIn("q"))).map(_.output), Right(ApgKept("a", "s")))
    val lost   = Signature.derived[ApgIn, ApgLost]("Lost", "Give the values.")
    val failed = Script(submitReply("""{"answer": "a", "tags": ["t"]}"""))
    val result = withModel(failed)(ReActV2(lost, Vector.empty)(ApgIn("q")))
    assert(result.left.exists(_.message.contains("Missing required fields: tags")), result.toString)
  }

  test("a demo and a history message with a name in the code show no value of that field, with no error") {
    val demo  = Example("question" := "q0", "name" := "a0", "n" := 1)
    val model = Script(wireChat)
    val demos = Predict(signature, demos = Vector(demo))
    assertEquals(withModel(model)(demos(ApgIn("q"))).map(_.output), Right(expected))
    assert(!promptOf(model.requests.head).contains("a0"), promptOf(model.requests.head))
    val past = Signature.derived[ApgPast, ApgNamed]("Past", "Give the name.")
    val turn = History(Vector(DynamicValues.record("question" := "q1", "name" := "a1")))
    val next = Script("[[ ## full_name ## ]]\na\n\n[[ ## completed ## ]]")
    assertEquals(withModel(next)(Predict(past)(ApgPast("q", turn))).map(_.output), Right(ApgNamed("a")))
    assert(!promptOf(next.requests.head).contains("a1"), promptOf(next.requests.head))
  }

  test("RLM: the variables and the SUBMIT values have the wire names, and the typed output has the value") {
    val variables = ArrayBuffer.empty[Set[String]]
    final class Repl extends ReplCodeInterpreter:
      override def execute(code: String): Either[DspyError, CodeResult]                                    = execute(code, Map.empty)
      override def execute(code: String, values: Map[String, DynamicValue]): Either[DspyError, CodeResult] =
        variables += values.keySet
        Right(CodeResult("", "", 0, finalOutput = Some(wireJson)))
      override def close(): Unit = ()
    val outputs = ArrayBuffer.empty[Vector[String]]
    val rlm     = RLM(
      baseSignature = signature,
      interpreterFactory = Some { (_, found) =>
        outputs += found.map(_.name)
        new Repl
      }
    )
    val model = Script("[[ ## reasoning ## ]]\nr\n\n[[ ## code ## ]]\nSUBMIT()\n\n[[ ## completed ## ]]")
    assertEquals(withModel(model)(rlm(ApgIn("q"))).map(_.output), Right(expected))
    assertEquals(outputs.toVector, Vector(Vector("full_name", "n", "shape", "level")))
    assert(variables.exists(_.contains("the_question")), variables.toString)
  }

  test("BestOfN: the reward reads the name in the code, and the raw prediction has the wire name") {
    val model = Script(
      "[[ ## full_name ## ]]\na\n\n[[ ## completed ## ]]",
      "[[ ## full_name ## ]]\nb\n\n[[ ## completed ## ]]"
    )
    val named = Signature.derived[ApgIn, ApgNamed]("Named", "Give the name.")
    val best  = BestOfN(
      module = Predict(named),
      n = AttemptCount(2),
      rewardFn = (_, prediction) => if prediction.output.name == "b" then 1.0 else 0.0,
      threshold = 1.0
    )
    val result = withModel(model)(best(ApgIn("q")))
    assertEquals(result.map(_.output), Right(ApgNamed("b")))
    assertEquals(
      result.map(found => DynamicValues.recordGet(found.raw.values, "full_name")),
      Right(Some(DynamicValues.string("b")))
    )
  }

  test("MultiChainComparison: the answer of an attempt and the output have the wire name") {
    val named = Signature.derived[ApgIn, ApgNamed]("Named", "Give the name.")
    val mcc   = MultiChainComparison(baseSignature = named, m = AttemptCount(1))
    assertEquals(mcc.augmentedSignatureLayout.outputFields.map(_.name), Vector("rationale", "full_name"))
    assertEquals(mcc.augmentedSignatureLayout.inputFields.map(_.name), Vector("the_question", "reasoning_attempt_1"))
    val model   = Script("[[ ## rationale ## ]]\nr\n\n[[ ## full_name ## ]]\nb\n\n[[ ## completed ## ]]")
    val attempt = RawPrediction(DynamicValues.record("rationale" := "try", "full_name" := "first"))
    val result  = withModel(model)(mcc.compare(ApgIn("q"), Vector(attempt)))
    assertEquals(result.map(found => (found.output.rationale, found.output.name)), Right(("r", "b")))
    assert(promptOf(model.requests.head).contains("my prediction is first"), promptOf(model.requests.head))
  }

  test("Aggregation: the vote reads the wire name of the last output") {
    val shape = Signature.derived[ApgIn, ApgOut]("Annotated").outputShape
    val rows  = Vector(expected, expected, expected.copy(level = ApgLevel.VeryHigh)).map(shape.encode)
    val votes = Aggregation.majority(rows).map(found => DynamicValues.recordGet(found.values, "level"))
    assertEquals(votes, Right(Some(DynamicValues.string("very_low"))))
  }

  test("a decision output has its settings and its evidence by its wire name") {
    val decided = Signature.derived[ApgIn, ApgDecided]("Decided", "Decide.")
    val text    = DynamicValue.Primitive(PrimitiveValue.String("Is it urgent?"))
    val predict = Predict(decided)
    assert(predict.withDecision("urgent", DecisionSettings(instructions = Some(text))).isLeft)
    val configured = predict.withDecision("verdict", DecisionSettings(threshold = Some(0.5), instructions = Some(text)))
      .fold(error => fail(error.message), identity)
    val model = Script("[[ ## verdict ## ]]\n{\"noul\": 0.9}\n\n[[ ## completed ## ]]")
    assertEquals(withModel(model)(configured(ApgIn("q"))).map(_.output.urgent.value), Right(true))
    assert(promptOf(model.requests.head).contains("verdict"), promptOf(model.requests.head))
  }

  test("a Score and a Choice have the wire names of the enum as their levels and their options") {
    val scored     = Signature.derived[ApgIn, ApgScored]("Scored", "Decide.")
    val text       = DynamicValue.Primitive(PrimitiveValue.String("Which one?"))
    val configured = Vector(
      "sev"  -> DecisionSettings(cuts = Some(Vector(0.5)), instructions = Some(text)),
      "team" -> DecisionSettings(instructions = Some(text))
    ).foldLeft(Predict(scored)) { case (predict, (name, settings)) =>
      predict.withDecision(name, settings).fold(error => fail(s"$name: ${error.message}"), identity)
    }
    val model = Script(
      """[[ ## sev ## ]]
        |{"probabilities": {"0": 0.2, "1": 0.8}, "confidence": 0.7}
        |
        |[[ ## team ## ]]
        |{"probabilities": {"very_high": 0.3, "very_low": 0.7}, "confidence": 0.7}
        |
        |[[ ## completed ## ]]""".stripMargin
    )
    val output = withModel(model)(configured(ApgIn("q"))).fold(error => fail(error.message), _.output)
    assertEquals(output.severity.level, Some(ApgLevel.VeryLow))
    assertEquals(output.team.value, ApgLevel.VeryLow)
    assertEquals(output.team.probabilities, Some(Map("very_high" -> 0.3, "very_low" -> 0.7)))
  }

  test("TwoStepAdapter: the extraction model gets the wire names") {
    val extraction = Script(wireChat)
    val result     = withModel(Script("It is a circle."), TwoStepAdapter(extraction))(Predict(signature)(ApgIn("q")))
    assertEquals(result.map(_.output), Right(expected))
    assert(promptOf(extraction.requests.head).contains("[[ ## full_name ## ]]"), promptOf(extraction.requests.head))
    assert(promptOf(extraction.requests.head).contains("\"kind\""), promptOf(extraction.requests.head))
  }

  test("a saved demo has the wire names, and it shows the same prompt after a load") {
    val demo = Example(
      ("the_question" := "q") +: signature.outputShape.encode(expected).fields.toVector*
    )
    val trained = Predict(signature, demos = Vector(demo), name = Some("ask"))
    val loaded  = ProgramPersistence
      .loadJson(Predict(signature, name = Some("ask")), ProgramPersistence.dumpJson(trained))
      .fold(error => fail(error.message), identity)
    val before = Script(wireChat)
    val after  = Script(wireChat)
    assertEquals(withModel(before)(trained(ApgIn("q"))).map(_.output), Right(expected))
    assertEquals(withModel(after)(loaded(ApgIn("q"))).map(_.output), Right(expected))
    val prompt = promptOf(before.requests.head)
    assertEquals(promptOf(after.requests.head), prompt)
    assert(prompt.contains("[[ ## full_name ## ]]\na"), prompt)
    assert(prompt.contains("""{"kind": "Circle", "radius": 1.5}"""), prompt)
    assert(prompt.contains("[[ ## level ## ]]\nvery_low"), prompt)
  }
