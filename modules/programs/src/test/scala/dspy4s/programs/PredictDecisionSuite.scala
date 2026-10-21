package dspy4s.programs

import dspy4s.adapters.{ChatAdapter, TwoStepAdapter}
import dspy4s.core.contracts.{:=, DspyError, DynamicValues, FieldSpec, RuntimeContext, SignatureLayout, TypeRef}
import dspy4s.core.decisions.{Choice, DecisionSettings, Noul, Score}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.programs.optimization.{OptimizableLeaf, OptimizableParameters}
import dspy4s.programs.strategies.{DynamicPredict, Predict}
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

enum PdSeverity derives Schema:
  case Minor, Disruptive, Critical

enum PdTeam derives Schema:
  case billing, technical

final case class PdTicket(ticket: String) derives Schema
final case class PdTriage(urgent: Noul, severity: Score[PdSeverity], category: Choice[PdTeam], native_urgent: Boolean)
    derives Schema

/** `Predict` with decision outputs and a generative model (experimental; upstream `Predict.fields` and the
  * `DecisionAdapter`, with the cases of `tests/predict/test_predict_decisions.py`). A typed signature has no field
  * descriptions, so each typed decision output takes its question text from the `instructions` of its settings.
  */
class PredictDecisionSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** A model that gives `reply` to each call and counts the calls. */
  private final class Replay(reply: String) extends LanguageModel:
    @volatile var calls: Vector[LmRequest]                                                     = Vector.empty
    override val id: String                                                                    = "replay"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      calls = calls :+ request
      Right(LmResponse(outputs = Vector(LmOutput(text = reply))))

  private def text(value: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(value))

  private val reply = """[[ ## urgent ## ]]
      |{"noul": 0.9}
      |
      |[[ ## severity ## ]]
      |{"probabilities": {"0": 0.1, "1": 0.3, "2": 0.6}, "confidence": 0.61}
      |
      |[[ ## category ## ]]
      |{"probabilities": {"billing": 0.6, "technical": 0.4}, "confidence": 0.7}
      |
      |[[ ## native_urgent ## ]]
      |{"noul": 0.3}
      |
      |[[ ## completed ## ]]""".stripMargin

  private val signature = Signature.derived[PdTicket, PdTriage]("Triage", "Triage the ticket.")

  private val settings: Vector[(String, DecisionSettings)] = Vector(
    "urgent"        -> DecisionSettings(threshold = Some(0.8), instructions = Some(text("Is immediate action needed?"))),
    "severity"      -> DecisionSettings(cuts = Some(Vector(0.4, 1.7)), instructions = Some(text("How severe is it?"))),
    "category"      -> DecisionSettings(weights = Some(Map("billing" -> 0.5)), instructions = Some(text("Which team?"))),
    "native_urgent" -> DecisionSettings(threshold = Some(0.2), instructions = Some(text("Is it urgent?")))
  )

  private def configured: Predict[PdTicket, PdTriage] =
    settings.foldLeft(Predict(signature)) { case (predict, (name, value)) =>
      predict.withDecision(name, value).fold(error => fail(s"$name: ${error.message}"), identity)
    }

  private def run[A](lm: Replay)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      body(using RuntimeEnvironment.current)
    }

  test("a typed Predict decodes the evidence into the decision values with its settings") {
    val lm     = Replay(reply)
    val result = run(lm)(configured(PdTicket("Checkout fails.")))
    val output = result.fold(error => fail(error.message), _.output)
    assertEquals(output.urgent.value, true)
    assertEqualsDouble(output.urgent.confidence, 0.125, 1e-12)
    assertEquals(output.urgent.probability, Some(0.9))
    assertEqualsDouble(output.severity.value, 1.5, 1e-12)
    assertEquals(output.severity.level, Some(1))
    assertEquals(output.severity.levelIn(PdSeverity.values.toIndexedSeq), Some(PdSeverity.Disruptive))
    assertEquals(output.category.value, PdTeam.technical)
    assertEquals(output.category.probabilities, Some(Map("billing" -> 0.6, "technical" -> 0.4)))
    assertEquals(output.native_urgent, true, "a native output with settings is a plain Boolean from its evidence")
    assertEquals(lm.calls.size, 1)
  }

  test("a decision output with no description and no instructions fails before the request") {
    val lm     = Replay(reply)
    val result = run(lm)(Predict(signature)(PdTicket("Checkout fails.")))
    assert(result.isLeft, result.toString)
    assertEquals(lm.calls.size, 0)
  }

  test("invalid settings fail before the request") {
    val invalid =
      configured.copy(decisions = configured.decisions.updated("urgent", DecisionSettings(threshold = Some(1.5))))
    val lm = Replay(reply)
    assert(run(lm)(invalid(PdTicket("x"))).isLeft)
    assertEquals(lm.calls.size, 0)
  }

  test("withDecision and withCriteria validate against the layout, and criteria gives the effective value") {
    val predict = Predict(signature)
    assert(predict.withDecision("missing", DecisionSettings()).isLeft)
    assert(predict.withDecision("urgent", DecisionSettings(cuts = Some(Vector(0.5)))).isLeft)
    assertEquals(
      predict.criteria("severity").map(_.map(dspy4s.adapters.internal.ProgramPromptText.json)),
      Right(Some("""["Minor", "Disruptive", "Critical"]"""))
    )
    assertEquals(predict.criteria("urgent"), Right(None))
    assert(predict.criteria("missing").isLeft)
    assert(predict.withCriteria("severity", DynamicValue.Sequence(zio.blocks.chunk.Chunk(text("a")))).isLeft)
    val updated = configured
      .withCriteria("severity", DynamicValue.Sequence(zio.blocks.chunk.Chunk(text("low"), text("mid"), text("high"))))
      .fold(error => fail(error.message), identity)
    assertEquals(updated.decisions("severity").cuts, Some(Vector(0.4, 1.7)), "the other settings stay")
    assertEquals(
      updated.criteria("severity").map(_.map(dspy4s.adapters.internal.ProgramPromptText.json)),
      Right(Some("""["low", "mid", "high"]"""))
    )
  }

  test("a DynamicPredict decodes the evidence into result records, with the field descriptions as questions") {
    val layout = SignatureLayout
      .create(
        name = "Triage",
        inputFields = Vector(FieldSpec("ticket")),
        outputFields = Vector(
          FieldSpec("urgent", TypeRef.noul, description = Some("Is immediate action needed?")),
          FieldSpec(
            "severity",
            TypeRef.score,
            description = Some("How severe?"),
            enumValues = Vector("Minor", "Disruptive", "Critical")
          ),
          FieldSpec(
            "category",
            TypeRef.choice,
            description = Some("Which team?"),
            enumValues = Vector("billing", "technical")
          ),
          FieldSpec("native_urgent", TypeRef.bool, description = Some("Is it urgent?"))
        )
      )
      .fold(error => fail(error.message), identity)
    val lm      = Replay(reply)
    val program = DynamicPredict(layout).withDecision(
      "native_urgent",
      DecisionSettings()
    ).fold(error => fail(error.message), identity)
    val raw    = run(lm)(program(DynamicValues.record("ticket" := "x"))).fold(error => fail(error.message), _.raw.values)
    val urgent =
      DynamicValues.recordGet(raw, "urgent").collect { case r: DynamicValue.Record => r }.getOrElse(fail("no urgent"))
    assertEquals(DynamicValues.recordGet(urgent, "value"), Some(DynamicValue.Primitive(PrimitiveValue.Boolean(true))))
    assertEquals(
      DynamicValues.recordGet(raw, "native_urgent"),
      Some(DynamicValue.Primitive(PrimitiveValue.Boolean(false)))
    )
  }

  test("with a TwoStepAdapter, the extraction prompt has the evidence schemas, and the evidence gives the decisions") {
    // Upstream `TwoStepAdapter.parse` builds the extractor signature from the output fields that the decision adapter
    // gives it, with their annotations (`dspy/adapters/decision.py`, `dspy/adapters/two_step_adapter.py`).
    val extraction = Replay(reply)
    val result     = RuntimeEnvironment.withSettings(
      RuntimeContext(lm = Some(Replay("It is urgent and severe.")), adapter = Some(TwoStepAdapter(extraction)))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      configured(PdTicket("Checkout fails."))
    }
    assertEquals(result.map(_.output.category.value), Right(PdTeam.technical))
    val prompt = extraction.calls.flatMap(_.messages).flatMap(_.text).mkString("\n")
    assert(prompt.contains("Confidence in the decision, from 0 to 1."), prompt)
  }

  test("the settings are an optimizable parameter, and the state record keeps them") {
    val leaf       = summon[OptimizableLeaf[Predict[PdTicket, PdTriage]]]
    val parameters = leaf.get(configured)
    assertEquals(parameters.decisions, configured.decisions)
    val state = parameters.dumpState
    assertEquals(OptimizableParameters.fromState(state).map(_.decisions), Right(configured.decisions))
    val changed =
      parameters.copy(decisions = parameters.decisions.updated("urgent", DecisionSettings(threshold = Some(0.6))))
    assertEquals(leaf.set(Predict(signature), changed).decisions("urgent").threshold, Some(0.6))
    val withoutKey = DynamicValues.recordFilterKeys(state, _ != "decisions")
    assertEquals(
      OptimizableParameters.fromState(withoutKey).map(_.decisions),
      Right(Map.empty[String, DecisionSettings])
    )
  }
