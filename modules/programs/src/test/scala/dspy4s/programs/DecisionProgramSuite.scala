package dspy4s.programs

import dspy4s.adapters.{ChatAdapter, DecisionAdapter, JSONAdapter}
import dspy4s.core.contracts.{
  :=,
  DspyError,
  DynamicValues,
  FieldSpec,
  RuntimeContext,
  SignatureLayout,
  TypeRef
}
import dspy4s.core.decisions.{Choice, DecisionKind, DecisionSettings, Noul}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.programs.optimization.OptimizableLeaf
import dspy4s.programs.strategies.{ChainOfThought, DynamicPredict, Predict, RLM}
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

import java.io.{ByteArrayOutputStream, PrintStream}

final case class DpTriage(urgent: Noul, category: Choice[PdTeam], native_urgent: Boolean)
    derives zio.blocks.schema.Schema
final case class DpNested(labels: List[Noul], maybe: Option[Noul], answer: String) derives zio.blocks.schema.Schema

/** The decision outputs in the programs around `Predict` (experimental; upstream `dspy/adapters/decision.py` and
  * `dspy/predict/rlm.py`): ChainOfThought, the fallback adapter, the typed output schema, and the warnings for a nested
  * decision output and for RLM.
  */
class DecisionProgramSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** Gives the replies in order and repeats the last one. Records each request. */
  private final class Replay(replies: String*)(responseSchema: Boolean = false) extends LanguageModel:
    @volatile var calls: Vector[LmRequest]                                                     = Vector.empty
    override val id: String                                                                    = "replay"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsResponseSchema: Boolean                                               = responseSchema
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      calls = calls :+ request
      Right(LmResponse(outputs = Vector(LmOutput(text = replies(math.min(calls.size - 1, replies.size - 1))))))

  private def text(value: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(value))

  private def run[A](lm: LanguageModel, adapter: dspy4s.adapters.contracts.Adapter = ChatAdapter())(
      body: RuntimeContext ?=> A
  ): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(adapter))) {
      body(using RuntimeEnvironment.current)
    }

  /** The value of `thunk`, and the text that it writes to `Console.err`. */
  private def captureErr[A](thunk: => A): (A, String) =
    val buffer = new ByteArrayOutputStream()
    val value  = Console.withErr(new PrintStream(buffer))(thunk)
    (value, buffer.toString("UTF-8"))

  private def systemText(request: LmRequest): String = request.messages.headOption.flatMap(_.text).getOrElse("")

  private val signature = Signature.derived[PdTicket, DpTriage]("Triage", "Triage the ticket.")

  private val questions: Vector[(String, DecisionSettings)] = Vector(
    "urgent"   -> DecisionSettings(threshold = Some(0.8), instructions = Some(text("Is immediate action needed?"))),
    "category" -> DecisionSettings(weights = Some(Map("billing" -> 0.5)), instructions = Some(text("Which team?")))
  )

  test("ChainOfThought keeps the reasoning as a text output and decodes the evidence of the decision outputs") {
    val program = questions.foldLeft(ChainOfThought(signature)) { case (cot, (name, settings)) =>
      cot.withDecision(name, settings).fold(error => fail(error.message), identity)
    }
    assert(program.withDecision("reasoning", DecisionSettings()).isLeft, "the reasoning is not a decision output")
    val lm = Replay("""[[ ## reasoning ## ]]
        |Checkout fails for each customer.
        |
        |[[ ## urgent ## ]]
        |{"noul": 0.9}
        |
        |[[ ## category ## ]]
        |{"probabilities": {"billing": 0.6, "technical": 0.4}, "confidence": 0.7}
        |
        |[[ ## native_urgent ## ]]
        |True
        |
        |[[ ## completed ## ]]""".stripMargin)()
    val output = run(lm)(program(PdTicket("Checkout fails."))).fold(error => fail(error.message), _.output)
    assertEquals(output.reasoning, "Checkout fails for each customer.")
    assertEquals(output.urgent.value, true)
    assertEquals(output.urgent.probability, Some(0.9))
    assertEquals(output.category.value, PdTeam.technical, "the weights apply")
    assertEquals(output.native_urgent, true)
    val system = systemText(lm.calls.head)
    assert(system.contains("1. `reasoning` (str):"), system)
    assert(system.contains("2. `urgent` (NoulEvidence): \n{"), system)
    assert(system.contains("[[ ## reasoning ## ]]\n{reasoning}\n"), system)
    val leaf = summon[OptimizableLeaf[ChainOfThought[PdTicket, DpTriage]]]
    assertEquals(leaf.get(program).decisions, program.decisions)
    assertEquals(leaf.set(ChainOfThought(signature), leaf.get(program)).decisions, program.decisions)
  }

  test("a typed Predict sends the evidence schema in place of the typed property, and keeps the other properties") {
    val program = questions.foldLeft(Predict(signature)) { case (predict, (name, settings)) =>
      predict.withDecision(name, settings).fold(error => fail(error.message), identity)
    }
    val lm = Replay(
      """{"urgent": {"noul": 0.2}, "category": {"probabilities": {"billing": 0.9, "technical": 0.1}, "confidence": 0.8},
        | "native_urgent": false}""".stripMargin
    )(responseSchema = true)
    val output = run(lm, JSONAdapter())(program(PdTicket("x"))).fold(error => fail(error.message), _.output)
    assertEquals(output.urgent.value, false)
    assertEquals(output.category.value, PdTeam.billing)
    val properties = DynamicValues.recordGet(lm.calls.head.options, "response_format")
      .flatMap(at(_, "json_schema", "schema", "properties"))
      .getOrElse(fail(s"no schema in ${lm.calls.head.options}"))
    assertEquals(at(properties, "urgent"), Some(DecisionAdapter.evidenceSchema(DecisionKind.Noul)))
    assertEquals(
      at(properties, "category"),
      Some(DecisionAdapter.evidenceSchema(DecisionKind.Choice(Vector("billing", "technical"))))
    )
    assertEquals(at(properties, "native_urgent"), Some(DynamicValues.record("type" := "boolean")))
    assertEquals(program.erase.decisions, program.decisions, "erase keeps the settings")
  }

  test("an evidence value that does not parse gives one more call with the fallback adapter, with evidence") {
    val layout = SignatureLayout
      .create(
        name = "Triage",
        inputFields = Vector(FieldSpec("ticket")),
        outputFields = Vector(
          FieldSpec("urgent", TypeRef.noul, description = Some("Is immediate action needed?")),
          FieldSpec("team", description = Some("Which team?"), enumValues = Vector("billing", "technical"))
        )
      )
      .fold(error => fail(error.message), identity)
    val program = DynamicPredict(layout).withDecision("team", DecisionSettings()).fold(e => fail(e.message), identity)
    val lm      = Replay(
      "[[ ## urgent ## ]]\nyes\n\n[[ ## team ## ]]\nbilling\n\n[[ ## completed ## ]]",
      """{"urgent": {"noul": 0.7}, "team": {"probabilities": {"billing": 0.3, "technical": 0.7}, "confidence": 0.9}}"""
    )()
    val output = run(lm)(program(DynamicValues.record("ticket" := "x"))).fold(error => fail(error.message), _.output)
    assertEquals(lm.calls.size, 2)
    assert(systemText(lm.calls(1)).contains("Outputs will be a JSON object"), systemText(lm.calls(1)))
    assert(systemText(lm.calls(1)).contains("`team` (ChoiceEvidence)"), systemText(lm.calls(1)))
    assertEquals(at(output, "urgent", "value"), Some(DynamicValue.Primitive(PrimitiveValue.Boolean(true))))
    assertEquals(DynamicValues.recordGet(output, "team"), Some(text("technical")))
  }

  test("a typed Predict warns about each output with a nested decision type, and runs") {
    val nested = Predict(Signature.derived[PdTicket, DpNested]("Label", "Label the ticket."))
    val lm     = Replay(
      """[[ ## labels ## ]]
        |[{"value": true, "confidence": 0.8}]
        |
        |[[ ## maybe ## ]]
        |None
        |
        |[[ ## answer ## ]]
        |ok
        |
        |[[ ## completed ## ]]""".stripMargin
    )()
    val (result, err)         = captureErr(run(lm)(nested(PdTicket("x"))).map(_.output))
    def warning(name: String) =
      s"Decision evidence decoding is not implemented for nested output '$name'. The LM generates values and " +
        "confidence directly; thresholds, cuts, and weights are not applied. Use top-level decision output fields " +
        "instead."
    assert(err.contains(warning("labels")), err)
    assert(err.contains(warning("maybe")), err)
    assert(!err.contains("'answer'"), err)
    val output = result.fold(error => fail(error.message), identity)
    assertEquals(output.labels.map(_.value), List(true))
    assertEquals(output.maybe, None)
  }

  test("an RLM with decision outputs warns that SUBMIT gets no evidence decoding") {
    val warning = "RLM support for Noul, Choice, and Score outputs is not implemented consistently: decision " +
      "evidence decoding is not guaranteed, including for nested output types. Use Predict with top-level decision " +
      "outputs instead."
    val (_, decision) = captureErr(RLM(baseSignature = signature))
    assert(decision.contains(warning), decision)
    val (_, nested) = captureErr(RLM(baseSignature = Signature.derived[PdTicket, DpNested]("Label", "Label it.")))
    assert(nested.contains(warning), nested)
    val (_, plain) = captureErr(RLM(baseSignature = Signature.fromString("context, query -> answer")))
    assert(!plain.contains("Noul"), plain)
  }

  test("an RLM writes the decision-output warning one time for a construction, and a copy does not write it again") {
    // Upstream `RLM.__init__` warns, and a deep copy does not run `__init__` (`dspy/predict/rlm.py`). An optimizer
    // makes a copy for each candidate.
    def count(text: String): Int = text.sliding("RLM support for Noul".length).count(_ == "RLM support for Noul")
    val (program, first)         = captureErr(RLM(baseSignature = signature))
    assertEquals(count(first), 1, first)
    val (copied, afterCopy) = captureErr(program.copy(maxIterations = IterationLimit(3)).copy(verbose = true))
    assertEquals(afterCopy, "", "a copy must not warn again")
    assertEquals(copied.maxIterations, IterationLimit(3))
    val (_, second) = captureErr(RLM(baseSignature = signature))
    assertEquals(count(second), 1, "a new construction warns again")
  }

  private def at(value: DynamicValue, path: String*): Option[DynamicValue] =
    path.foldLeft(Option(value)) {
      case (Some(record: DynamicValue.Record), key) => DynamicValues.recordGet(record, key)
      case _                                        => None
    }
