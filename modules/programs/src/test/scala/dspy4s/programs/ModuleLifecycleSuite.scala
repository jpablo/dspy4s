package dspy4s.programs

import dspy4s.core.contracts.{:=, CallbackEvent, CallbackHandler, DspyError, DynamicValues, RuntimeContext}
import dspy4s.core.data.RawPrediction
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.{Module, ModuleLifecycle, ProgramCall}
import dspy4s.typed.Prediction
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer

class ModuleLifecycleSuite extends FunSuite:
  private final case class Echo(
      lifecycleStrategy: ModuleLifecycle[Int, Int]
  ) extends Module[Int, Int]:
    override val moduleName: String                                                      = "echo"
    override protected val lifecycle: ModuleLifecycle[Int, Int] = lifecycleStrategy

    override protected def forward(call: ProgramCall[Int])(using RuntimeContext): Either[DspyError, Prediction[Int]] =
      Right(Prediction(call.input, RawPrediction(DynamicValues.record("result" := call.input))))

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach): Unit   = RuntimeEnvironment.resetForTests()

  test("observation is a value-level policy for a shared call and result carrier") {
    given RuntimeContext = RuntimeEnvironment.current
    val encoded          = Echo(ModuleLifecycle.typed(call => DynamicValues.record("input" := call.input)))
    val opaque           = Echo(ModuleLifecycle.typedWithoutInputs)

    assertEquals(encoded(ProgramCall(1)).map(_.output), Right(1))
    assertEquals(opaque(ProgramCall(2)).map(_.output), Right(2))

    val trace = RuntimeEnvironment.current.trace
    assertEquals(trace.map(entry => DynamicValues.recordKeys(entry.inputs)), Vector(Vector("input"), Vector.empty))
    assertEquals(
      trace.map(entry => DynamicValues.recordKeys(entry.outputs)),
      Vector(Vector("result"), Vector("result"))
    )
  }

  test("transparent lifecycle contributes no callbacks, trace, or history") {
    val events = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = events += event

    RuntimeEnvironment.withCallbacks(Vector(callback)) {
      given RuntimeContext = RuntimeEnvironment.current
      val transparent      = Echo(ModuleLifecycle.transparent)

      assertEquals(transparent(ProgramCall(1)).map(_.output), Right(1))
      assertEquals(events.toVector, Vector.empty)
      assertEquals(RuntimeEnvironment.current.trace, Vector.empty)
      assertEquals(RuntimeEnvironment.current.history, Vector.empty)
    }
  }
