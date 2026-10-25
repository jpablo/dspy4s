package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.internal.ProgramPromptText
import dspy4s.core.contracts.{:=, DspyError, DynamicValues, FieldSpec, RuntimeContext, SignatureLayout, TypeRef}
import dspy4s.core.data.Example
import dspy4s.core.decisions.{DecisionPlan, DecisionSettings}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{DecisionRequests, LanguageModel, LmMode, LmRequest, LmResponse}
import dspy4s.lm.runtime.{CacheCapacity, InMemoryLmCache, ManagedLanguageModel}
import dspy4s.programs.strategies.DynamicPredict
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import java.nio.charset.StandardCharsets

/** `Predict` with a System One model such as TypeSafe Jev (experimental; upstream `DecisionAdapter` with
  * `system_one=True`, and the cases of `tests/clients/test_typesafe.py` and
  * `tests/predict/test_decision_parameters.py`). The request has the state and the questions; the threshold, the cuts,
  * and the weights stay local.
  */
class PredictSystemOneSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private def json(text: String): DynamicValue =
    Schema.dynamic.jsonCodec.decode(text.getBytes(StandardCharsets.UTF_8)).fold(error => fail(error.toString), identity)

  /** A System One model that answers each question as the upstream `FakeClient`: P(true) 0.8 for a Noul, the
    * distribution 0.1, 0.3, 0.6 for a Score, and 0.8 for the label "2" of a Choice.
    */
  private final class FakeSystemOne extends LanguageModel:
    @volatile var requests: Vector[LmRequest]                                                  = Vector.empty
    override val id: String                                                                    = "jev-test"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsDecisionRequests: Boolean                                             = true
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      requests = requests :+ request
      DecisionRequests.parts(request).map { (_, questions) =>
        val answers = DynamicValues.recordKeys(questions).map { name =>
          val kind = DynamicValues.recordGet(questions, name).collect { case q: DynamicValue.Record => q }
            .flatMap(DynamicValues.recordGet(_, "type")).map(DynamicValues.renderText).getOrElse("")
          name ->
            (kind match
              case "noul"  => json("""{"type": "noul", "noul": 0.8}""")
              case "score" => json(
                  """{"type": "score", "score": 1.5, "confidence": 0.61, "probabilities": {"0": 0.1, "1": 0.3, "2": 0.6}}"""
                )
              case _ => json(
                  """{"type": "choice", "choice": "2", "confidence": 0.73, "probabilities": {"2": 0.8, "other": 0.2}}"""
                ))
        }
        DecisionRequests.response(
          DynamicValues.recordFromEntries(answers),
          Some(dspy4s.core.contracts.LmUsage(totalTokens = 12, promptTokens = 10, completionTokens = 2)),
          Some("jev-test")
        )
      }

  private val layout: SignatureLayout = SignatureLayout
    .create(
      name = "Assess",
      inputFields = Vector(FieldSpec("text")),
      outputFields = Vector(
        FieldSpec("flag", TypeRef.noul, description = Some("Is it relevant?")),
        FieldSpec(
          "rating",
          TypeRef.score,
          description = Some("Rate usefulness."),
          enumValues = Vector("bad", "fair", "great")
        ),
        FieldSpec(
          "label",
          TypeRef.choice,
          description = Some("Classify the document."),
          enumValues = Vector("2", "other")
        )
      ),
      instructions = Some("Assess the document.")
    )
    .fold(error => fail(error.message), identity)

  private def run[A](lm: LanguageModel)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      body(using RuntimeEnvironment.current)
    }

  private def get(values: DynamicValue.Record, path: String*): DynamicValue =
    path.foldLeft(values: DynamicValue) {
      case (record: DynamicValue.Record, key) =>
        DynamicValues.recordGet(record, key).getOrElse(fail(s"no $key in $record"))
      case (other, key) => fail(s"no $key in $other")
    }

  private def number(value: DynamicValue): Double =
    value match
      case DynamicValue.Primitive(PrimitiveValue.Double(v)) => v
      case DynamicValue.Primitive(PrimitiveValue.Int(v))    => v.toDouble
      case other                                            => fail(s"not a number: $other")

  private def call(program: DynamicPredict, lm: LanguageModel, text: String = "x"): DynamicValue.Record =
    run(lm)(program(DynamicValues.record("text" := text))).fold(error => fail(error.message), _.raw.values)

  test("the request has the state and the questions, and no setting goes to the model") {
    val lm                 = FakeSystemOne()
    val _                  = call(DynamicPredict(layout), lm)
    val (state, questions) = DecisionRequests.parts(lm.requests.headOption.getOrElse(fail("no request")))
      .fold(error => fail(error.message), identity)
    assertEquals(
      state,
      json("""{"instructions": "Assess the document.", "input_fields": "1. `text` (str):", "inputs": {"text": "x"}}""")
    )
    assertEquals(
      questions,
      json(
        """{"flag": {"instructions": "Is it relevant?", "type": "noul"},
          | "rating": {"instructions": "Rate usefulness.", "type": "score", "criteria": ["bad", "fair", "great"]},
          | "label": {"instructions": "Classify the document.", "type": "choice", "criteria": {"2": null, "other": null}}}""".stripMargin
      )
    )
    val text = ProgramPromptText.json(lm.requests.head.options)
    assert(!text.contains("threshold") && !text.contains("cuts") && !text.contains("weights"), text)
  }

  test("the settings decode the evidence locally, and a cached answer serves new settings") {
    val fake    = FakeSystemOne()
    val managed = ManagedLanguageModel(fake, cache = Some(new InMemoryLmCache(CacheCapacity(8))))
    val first   = call(DynamicPredict(layout), managed)
    val tuned   = DynamicPredict(layout).copy(decisions =
      Map(
        "flag"   -> DecisionSettings(threshold = Some(0.9)),
        "rating" -> DecisionSettings(cuts = Some(Vector(0.5, 1.6))),
        "label"  -> DecisionSettings(weights = Some(Map("2" -> 0.1)))
      )
    )
    val second = call(tuned, managed)
    assertEquals(get(first, "flag", "value"), DynamicValue.Primitive(PrimitiveValue.Boolean(true)))
    assertEqualsDouble(number(get(first, "flag", "confidence")), 0.6, 1e-12)
    assertEquals(get(second, "flag", "value"), DynamicValue.Primitive(PrimitiveValue.Boolean(false)))
    assertEqualsDouble(number(get(second, "flag", "confidence")), 1.0 / 9, 1e-12)
    assertEqualsDouble(number(get(first, "rating", "value")), 1.5, 1e-12)
    assertEquals(get(first, "rating", "level"), DynamicValue.Primitive(PrimitiveValue.String("great")))
    assertEquals(get(second, "rating", "level"), DynamicValue.Primitive(PrimitiveValue.String("fair")))
    assertEquals(get(first, "label", "value"), DynamicValue.Primitive(PrimitiveValue.String("2")))
    assertEquals(get(second, "label", "value"), DynamicValue.Primitive(PrimitiveValue.String("other")))
    assertEquals(fake.requests.size, 1, "the second call is a cache hit")
  }

  test("the demos go into the state, with the fields of the layout only, and an empty demo list adds no key") {
    val lm   = FakeSystemOne()
    val demo = Example(
      values =
        DynamicValues.record("text" := "d", "flag" := false, "rating" := 0.0, "label" := "other", "extra" := "no"),
      inputKeys = Set("text")
    )
    val _           = call(DynamicPredict(layout, demos = Vector(demo)), lm)
    val (state, _)  = DecisionRequests.parts(lm.requests.head).fold(error => fail(error.message), identity)
    val stateRecord = state match
      case record: DynamicValue.Record => record
      case other                       => fail(s"not a record: $other")
    assertEquals(get(stateRecord, "demos"), json("""[{"text": "d", "flag": false, "rating": 0.0, "label": "other"}]"""))
    val _          = call(DynamicPredict(layout), lm)
    val (plain, _) = DecisionRequests.parts(lm.requests.last).fold(error => fail(error.message), identity)
    val plainKeys  = plain match
      case record: DynamicValue.Record => DynamicValues.recordKeys(record)
      case other                       => fail(s"not a record: $other")
    assertEquals(plainKeys, Vector("instructions", "input_fields", "inputs"))
  }

  test("a native Boolean or enum output is a decision output with no settings entry, and gives the plain value") {
    val native = SignatureLayout
      .create(
        name = "Native",
        inputFields = Vector(FieldSpec("text")),
        outputFields = Vector(
          FieldSpec("flag", TypeRef.bool, description = Some("Is it relevant?")),
          FieldSpec("label", enumValues = Vector("2", "other"), description = Some("Classify the document."))
        )
      )
      .fold(error => fail(error.message), identity)
    val values = call(DynamicPredict(native), FakeSystemOne())
    assertEquals(get(values, "flag"), DynamicValue.Primitive(PrimitiveValue.Boolean(true)))
    assertEquals(get(values, "label"), DynamicValue.Primitive(PrimitiveValue.String("2")))
  }

  test("an output that cannot be a decision output fails before the request") {
    val withText = SignatureLayout
      .create(
        "Mixed",
        Vector(FieldSpec("text")),
        Vector(FieldSpec("flag", TypeRef.noul, Some("Is it?")), FieldSpec("summary"))
      )
      .fold(error => fail(error.message), identity)
    val lm     = FakeSystemOne()
    val result = run(lm)(DynamicPredict(withText)(DynamicValues.record("text" := "x")))
    assert(result.left.exists(_.message.contains("Unsupported System One output 'summary'")), result.toString)
    assertEquals(lm.requests.size, 0)
    assert(DecisionPlan.forSystemOne(withText, Map.empty).isLeft)
  }

  test("a generation setting fails before the request") {
    val lm     = FakeSystemOne()
    val tuned  = DynamicPredict(layout, config = DynamicValues.record("temperature" := 0.5))
    val result = run(lm)(tuned(DynamicValues.record("text" := "x")))
    assert(result.left.exists(_.message.contains("temperature")), result.toString)
    assertEquals(lm.requests.size, 0)
  }

  test("the prediction keeps the usage of the reply") {
    val lm     = FakeSystemOne()
    val result =
      run(lm)(DynamicPredict(layout)(DynamicValues.record("text" := "x"))).fold(error => fail(error.message), identity)
    assertEquals(result.raw.lmUsage.map(_.promptTokens), Some(10L))
  }
