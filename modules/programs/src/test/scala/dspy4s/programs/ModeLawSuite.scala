package dspy4s.programs

import dspy4s.programs.optimization.*
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.algebra.{IsEq, Monoid, MonoidAction}
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ModuleLifecycle
import dspy4s.programs.contracts.ProgramCall
import dspy4s.typed.Prediction
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer

/** Laws for `mode` (Algebra 2's non-learnable control middleware monoid; see
  * `docs/refactor/algebra-2-program-composition.md`): the control transform reaches the wrapped program, the Mode
  * monoid (`mode(m1 ++ m2) = mode(m1) ∘ mode(m2)`, `mode(Mode.id) = id`), and addressability pass-through.
  */
class ModeLawSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private def predict(sig: String): DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse(sig).toOption.get)

  /** A typed program stub that records the per-call controls it received and echoes its input; `predict` is its single
    * learnable leaf (for the addressability law).
    */
  private final case class Recorder(predict: DynamicPredict) extends Module[Int, Int]:
    val seen: ArrayBuffer[Mode.Controls]                                                                             = ArrayBuffer.empty
    override val moduleName: String                                                                                  = "recorder"
    override protected val lifecycle: ModuleLifecycle[Int, Int]                                                      = ModuleLifecycle.typedWithoutInputs
    override protected def forward(call: ProgramCall[Int])(using RuntimeContext): Either[DspyError, Prediction[Int]] =
      seen += Mode.Controls(call.config, call.traceEnabled, call.rolloutId)
      Right(Prediction(call.input, RawPrediction.empty))

  private object Recorder:
    given recorderOptimizable: OptimizableLeaf[Recorder] with
      def get(program     : Recorder): OptimizableParameters               = program.predict.optimizableParameters
      def metadata(program: Recorder): OptimizableMetadata                 = program.predict.optimizableView.metadata
      def set(program: Recorder, updated: OptimizableParameters): Recorder =
        program.copy(predict = program.predict.withOptimizableParameters(updated))

  private given RuntimeContextProvider: RuntimeContext = RuntimeEnvironment.current

  private def temp(controls: Mode.Controls): Option[String] =
    DynamicValues.recordGet(controls.config, "temperature").map(DynamicValues.renderText)

  test("mode applies the control transform — the temperature reaches the wrapped program") {
    val r = Recorder(predict("a -> b"))
    val _ = r.mode(Mode.temperature(0.7))(ProgramCall(1))
    assertEquals(r.seen.size, 1)
    assertEquals(temp(r.seen.head), Some("0.7"))
  }

  /** Execute a stated Mode monoid law: two Modes are equal iff their transforms agree on every Controls. */
  private def assertModeLaw(eq: IsEq[Mode], samples: Vector[Mode.Controls]): Unit =
    samples.foreach(c => assertEquals(eq.lhs.transform(c), eq.rhs.transform(c)))

  test("Mode is a lawful monoid: the Monoid[Mode] instance's laws, executed") {
    val M       = Monoid[Mode]
    val m1      = Mode.temperature(0.5)
    val m2      = Mode.model("gpt-x")
    val m3      = Mode.rolloutId(3)
    val samples = Vector(
      Mode.Controls(DynamicValue.Record.empty, traceEnabled = true, rolloutId = None),
      Mode.Controls(DynamicValues.record("temperature" := 0.1), traceEnabled = false, rolloutId = Some(2))
    )
    assertModeLaw(M.associativity(m1, m2, m3), samples)
    assertModeLaw(M.identityLeft(m1), samples)
    assertModeLaw(M.identityRight(m1), samples)
  }

  test("Mode monoid: mode(m1 ++ m2) sees the same controls as mode(m1) ∘ mode(m2)") {
    val m1 = Mode.temperature(0.5)
    val m2 = Mode.temperature(0.9) // last-applied wins; both shapes must agree on the final controls
    val rA = Recorder(predict("a -> b"))
    val _  = Compose.mode(m1 ++ m2)(rA)(ProgramCall(1))
    val rB = Recorder(predict("a -> b"))
    val _  = Compose.mode(m1)(Compose.mode(m2)(rB))(ProgramCall(1))
    assertEquals(rA.seen.head.config, rB.seen.head.config)
    assertEquals(temp(rA.seen.head), Some("0.9"))
    assertEquals(temp(rB.seen.head), Some("0.9"))
  }

  test("the Module action executes its stated identity and compatibility laws") {
    val action = MonoidAction[Mode, Module[Int, Int]]
    val m1     = Mode.temperature(0.5)
    val m2     = Mode.temperature(0.9)

    def assertActionLaw(law: IsEq[Module[Int, Int]], recorder: Recorder): Unit =
      val left         = law.lhs(ProgramCall(1))
      val leftControls = recorder.seen.toVector
      recorder.seen.clear()
      val right         = law.rhs(ProgramCall(1))
      val rightControls = recorder.seen.toVector
      assertEquals(left, right)
      assertEquals(leftControls, rightControls)

    val identityRecorder = Recorder(predict("a -> b"))
    assertActionLaw(action.identity(identityRecorder), identityRecorder)

    val compositionRecorder = Recorder(predict("a -> b"))
    assertActionLaw(action.compatibility(m1, m2, compositionRecorder), compositionRecorder)
  }

  test("the Module action is a lawful functor from the opposite Mode delooping") {
    val action = MonoidAction[Mode, Module[Int, Int]]
    val F      = action.functor
    val m1     = Mode.temperature(0.5)
    val m2     = Mode.temperature(0.9)

    def assertFunctorLaw(
        law     : IsEq[Module[Int, Int] => Module[Int, Int]],
        recorder: Recorder
    ): Unit =
      val left         = law.lhs(recorder)(ProgramCall(1))
      val leftControls = recorder.seen.toVector
      recorder.seen.clear()
      val right         = law.rhs(recorder)(ProgramCall(1))
      val rightControls = recorder.seen.toVector
      assertEquals(left, right)
      assertEquals(leftControls, rightControls)

    assertFunctorLaw(F.identities[Unit], Recorder(predict("a -> b")))
    assertFunctorLaw(F.composition[Unit, Unit, Unit](m1, m2), Recorder(predict("a -> b")))
  }

  test("mode(Mode.id)(p) = p on the controls and the output (left/right unit)") {
    val r      = Recorder(predict("a -> b"))
    val result = Compose.mode(Mode.id)(r)(ProgramCall(42))
    assertEquals(result.map(_.output), Right(42))
    // The call's controls reach the program unchanged.
    assertEquals(r.seen.head, Mode.Controls(DynamicValue.Record.empty, traceEnabled = true, rolloutId = None))
  }

  test("mode is trace-transparent: only the wrapped program records a trace entry") {
    val r = Recorder(predict("a -> b"))
    val _ = Compose.mode(Mode.temperature(1.0))(r)(ProgramCall(1))
    // The recorder's own entry only — no extra "mode(...)" entry.
    assertEquals(RuntimeEnvironment.current.trace.map(_.component), Vector("recorder"))
  }

  test("OptimizableTraversal passes through the wrapped program (mode is non-learnable)") {
    val r     = Recorder(predict("a -> b"))
    val moded = Compose.mode(Mode.temperature(1.0))(r)
    val P     = summon[OptimizableTraversal[Moded[Int, Int, Recorder]]]
    assertEquals(P.read(moded), Vector(r.predict.optimizableParameters))
    assertEquals(P.readNamed(moded).map(_._1), Vector("self"))
    assertEquals(P.read(P.replace(moded, P.read(moded))), P.read(moded))
  }
