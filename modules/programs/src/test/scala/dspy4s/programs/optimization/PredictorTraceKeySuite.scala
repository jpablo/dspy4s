package dspy4s.programs.optimization

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, FormattedPrompt, ParsedOutput}
import dspy4s.core.contracts.{DspyError, DynamicValues, ParseError, RuntimeContext, SignatureLayout, TraceEntry, :=}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LmOutput, Message, MessageRole}
import dspy4s.programs.*
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.support.ScriptedLm
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.DynamicValue

/** Each predictor writes the key of its leaf metadata into its trace entries (UPGRADE-3.2.1-to-3.4.0, decision D2). An
  * optimizer depends on that equality to find the calls of a leaf.
  */
class PredictorTraceKeySuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private def record(entries: (String, DynamicValue)*): DynamicValue.Record = DynamicValues.recordFromEntries(entries)

  /** Gives each output field the text of the model. `failParse` makes each parse fail. */
  private final class FillAdapter(failParse: Boolean = false) extends Adapter:
    override val name: String                                                                                    = "fill"
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("ignored")))))
    override def parse(layout: SignatureLayout, output: LmOutput)(using
        RuntimeContext
    ): Either[DspyError, ParsedOutput] =
      if failParse then Left(ParseError("fill", "cannot parse", Some(output.text)))
      else Right(ParsedOutput(values = record(layout.outputFields.map(_.name := output.text)*)))

  private val qa      = Signature.fromString("question -> answer")
  private val rewrite = Signature.fromString("answer -> summary")
  private val inputs  = record("question" := "q")

  private def traced[P: ProgramRunner](
      program        : P,
      failParse      : Boolean = false,
      captureFailures: Boolean = false
  ): (Either[DspyError, ?], Vector[TraceEntry]) =
    val settings =
      RuntimeContext(lm = Some(new ScriptedLm(Vector.fill(16)("v"))), adapter = Some(FillAdapter(failParse)))
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      TracedRun.run(program, ProgramCall(inputs), captureFailures)
    }

  private def leavesOf[P](program: P)(using structure: OptimizableStructure[P]): Vector[IdentifiedOptimizable] =
    structure.readIdentified(program)

  private def successfulTrace[P: ProgramRunner](program: P): Vector[TraceEntry] =
    val (result, trace) = traced(program)
    assert(result.isRight, result.toString)
    trace

  test("Predict records one entry with the key of its leaf metadata") {
    val program = Predict(qa)
    val trace   = successfulTrace(program)
    assertEquals(trace.map(_.predictor), Vector(Some(program.optimizableView.metadata.key)))
    assertEquals(trace.map(_.withinPredictor), Vector(false))
  }

  test("DynamicPredict records one entry with the key of its leaf metadata") {
    val program = DynamicPredict(layout = SignatureLayout.parse("question -> answer").toOption.get)
    val trace   = successfulTrace(program)
    assertEquals(trace.map(_.predictor), Vector(Some(program.optimizableView.metadata.key)))
  }

  test("ChainOfThought records its key on its own entry, and its inner Predict records none") {
    val program = ChainOfThought(qa)
    val trace   = successfulTrace(program)
    assertEquals(trace.map(_.component), Vector("predict", "chain_of_thought"))
    assertEquals(trace.map(_.predictor), Vector(None, Some(program.optimizableView.metadata.key)))
    assertEquals(trace.map(_.withinPredictor), Vector(true, false))
  }

  test("the key does not change when an optimizer writes new parameters") {
    val program = Predict(qa)
    val updated = program.withOptimizableParameters(OptimizableParameters(instructions = Some("Answer in one word.")))
    assertEquals(successfulTrace(updated).flatMap(_.predictor), Vector(program.optimizableView.metadata.key))
  }

  test("a name gives a predictor a different key") {
    val plain = Predict(qa)
    val named = Predict(qa, name = Some("second_pass"))
    assertNotEquals(named.optimizableView.metadata.key, plain.optimizableView.metadata.key)
    assertEquals(successfulTrace(named).flatMap(_.predictor), Vector(named.optimizableView.metadata.key))
  }

  test("a failed predictor call records its key when failure traces are on") {
    val program         = Predict(qa)
    val (result, trace) = traced(program, failParse = true, captureFailures = true)
    assert(result.isLeft, result.toString)
    assertEquals(trace.map(_.failure.isDefined), Vector(true))
    assertEquals(trace.map(_.predictor), Vector(Some(program.optimizableView.metadata.key)))
  }

  test("a sequential program gives one keyed entry for each leaf, and byLeaf groups them by leaf") {
    val first   = Predict(qa)
    val second  = Predict(rewrite)
    val program = first >>> second
    val leaves  = leavesOf(program)
    val trace   = successfulTrace(program)

    assertEquals(PredictorTraces.requireDistinctKeys(leaves), Right(()))
    val groups = PredictorTraces.byLeaf(leaves, trace).toOption.get
    assertEquals(
      groups.map(_.map(_.predictor)),
      Vector(Vector(Some(first.optimizableView.metadata.key)), Vector(Some(second.optimizableView.metadata.key)))
    )
    assertEquals(
      groups.map(_.map(e => DynamicValues.recordKeys(e.outputs))),
      Vector(Vector(Vector("answer")), Vector(Vector("summary")))
    )
  }

  test("the same predictor two times in one program fails the distinct-key check") {
    val predict = Predict(qa)
    val program = predict &&& predict
    assert(PredictorTraces.requireDistinctKeys(leavesOf(program)).isLeft)
  }

  test("TracedRun.runWith runs its continuation in the isolated context of the run") {
    val settings = RuntimeContext(lm = Some(new ScriptedLm(Vector.fill(4)("v"))), adapter = Some(FillAdapter()))
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val seen             = TracedRun.runWith(Predict(qa), ProgramCall(inputs)) { (result, trace) =>
        // A metric that calls a predictor appends to the current trace. That entry must stay in the isolated context.
        RuntimeEnvironment.appendTrace(TraceEntry("metric", record(), record()))
        (result.isRight, trace.size, RuntimeEnvironment.current.trace.size)
      }
      assertEquals(seen, (true, 1, 2))
      assertEquals(RuntimeEnvironment.current.trace, Vector.empty)
    }
  }
