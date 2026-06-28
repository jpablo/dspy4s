package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.Prediction
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer

/** Laws for `mode` (Algebra 2's non-learnable control middleware monoid; see
  * `docs/refactor/algebra-2-program-composition.md`): the control transform reaches the wrapped program, the
  * Mode monoid (`mode(m1 ++ m2) = mode(m1) ∘ mode(m2)`, `mode(Mode.id) = id`), and addressability pass-through. */
class ModeLawSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  private def predict(sig: String): DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse(sig).toOption.get)

  /** A typed program stub that records the per-call controls it received and echoes its input; `predict` is its
    * single learnable leaf (for the addressability law). */
  private final case class Recorder(predict: DynamicPredict) extends Module[TypedCall[Int], Prediction[Int]]:
    val seen: ArrayBuffer[Mode.Controls] = ArrayBuffer.empty
    override val moduleName: String = "recorder"
    override protected def callInputs(call: TypedCall[Int]): DynamicValue.Record       = DynamicValue.Record.empty
    override protected def callTraceEnabled(call: TypedCall[Int]): Boolean             = call.traceEnabled
    override protected def tracePayload(p: Prediction[Int]): DynamicValue.Record       = p.raw.values
    override protected def forward(call: TypedCall[Int])(using RuntimeContext): Either[DspyError, Prediction[Int]] =
      seen += Mode.Controls(call.config, call.traceEnabled, call.rolloutId)
      Right(Prediction(call.input, DynamicPrediction.empty))

  private object Recorder:
    given recorderPredictor: Predictor[Recorder] with
      def get(program: Recorder): DynamicPredict                      = program.predict
      def set(program: Recorder, updated: DynamicPredict): Recorder   = program.copy(predict = updated)

  private given RuntimeContextProvider: RuntimeContext = RuntimeEnvironment.current

  private def temp(controls: Mode.Controls): Option[String] =
    DynamicValues.recordGet(controls.config, "temperature").map(DynamicValues.renderText)

  test("mode applies the control transform — the temperature reaches the wrapped program") {
    val r = Recorder(predict("a -> b"))
    val _ = Compose.mode(Mode.temperature(0.7))(r).apply(TypedCall(1))
    assertEquals(r.seen.size, 1)
    assertEquals(temp(r.seen.head), Some("0.7"))
  }

  test("Mode monoid: mode(m1 ++ m2) sees the same controls as mode(m1) ∘ mode(m2)") {
    val m1 = Mode.temperature(0.5)
    val m2 = Mode.temperature(0.9) // last-applied wins; both shapes must agree on the final controls
    val rA = Recorder(predict("a -> b"))
    val _  = Compose.mode(m1 ++ m2)(rA).apply(TypedCall(1))
    val rB = Recorder(predict("a -> b"))
    val _  = Compose.mode(m1)(Compose.mode(m2)(rB)).apply(TypedCall(1))
    assertEquals(rA.seen.head.config, rB.seen.head.config)
    assertEquals(temp(rA.seen.head), Some("0.9"))
    assertEquals(temp(rB.seen.head), Some("0.9"))
  }

  test("mode(Mode.id)(p) = p on the controls and the output (left/right unit)") {
    val r      = Recorder(predict("a -> b"))
    val result = Compose.mode(Mode.id)(r).apply(TypedCall(42))
    assertEquals(result.map(_.output), Right(42))
    // The call's controls reach the program unchanged.
    assertEquals(r.seen.head, Mode.Controls(DynamicValue.Record.empty, traceEnabled = true, rolloutId = None))
  }

  test("mode is trace-transparent: only the wrapped program records a trace entry") {
    val r = Recorder(predict("a -> b"))
    val _ = Compose.mode(Mode.temperature(1.0))(r).apply(TypedCall(1))
    // The recorder's own entry only — no extra "mode(...)" entry.
    assertEquals(RuntimeEnvironment.current.trace.map(_.component), Vector("recorder"))
  }

  test("Predictors passes through the wrapped program (mode is non-learnable)") {
    val r     = Recorder(predict("a -> b"))
    val moded = Compose.mode(Mode.temperature(1.0))(r)
    val P     = summon[Predictors[Moded[Int, Int, Recorder]]]
    assertEquals(P.read(moded), Vector(r.predict))
    assertEquals(P.readNamed(moded).map(_._1), Vector("self"))
    assertEquals(P.read(P.replace(moded, P.read(moded))), P.read(moded))
  }
