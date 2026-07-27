package dspy4s.programs

import dspy4s.core.contracts.{:=, DspyError, DynamicValues, RuntimeContext}
import dspy4s.core.data.DynamicPrediction
import dspy4s.programs.contracts.{DynamicModule, Module, ModuleLifecycle, ProgramCall}
import dspy4s.typed.Prediction
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, Schema}

private final case class RunnerInput(value: String) derives Schema

class ProgramRunnerSuite extends FunSuite:

  private final class CapturingDynamic extends DynamicModule:
    var observed: Option[ProgramCall[DynamicValue.Record]] = None
    val moduleName                                         = "capturing_dynamic"

    protected def forwardDynamic(call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, DynamicPrediction] =
      observed = Some(call)
      Right(DynamicPrediction(DynamicValues.record("value" := "dynamic")))

  private final class CapturingTyped extends Module[RunnerInput, String]:
    var observed: Option[ProgramCall[RunnerInput]] = None
    val moduleName                                 = "capturing_typed"

    protected val lifecycle: ModuleLifecycle[RunnerInput, String] =
      ModuleLifecycle.typed(call => DynamicValues.record("value" := call.input.value))

    protected def forward(call: ProgramCall[RunnerInput])(using
        RuntimeContext
    ): Either[DspyError, Prediction[String]] =
      observed = Some(call)
      val raw = DynamicPrediction(DynamicValues.record("value" := call.input.value))
      Right(Prediction(call.input.value, raw))

  private val call = ProgramCall(
    input = DynamicValues.record("value" := "decoded"),
    config = DynamicValues.record("temperature" := 0.6),
    traceEnabled = false,
    rolloutId = Some(11)
  )

  test("the dynamic runner forwards the complete call envelope unchanged") {
    given RuntimeContext = RuntimeContext()
    val program          = CapturingDynamic()

    val result = ProgramRunner[CapturingDynamic].run(program, call)

    assertEquals(result.map(_.asString("value")), Right(Right("dynamic")))
    assertEquals(program.observed, Some(call))
  }

  test("the typed runner decodes only the input and preserves every execution control") {
    given RuntimeContext = RuntimeContext()
    val program          = CapturingTyped()

    val result = ProgramRunner[CapturingTyped].run(program, call)

    assertEquals(result.map(_.asString("value")), Right(Right("decoded")))
    assertEquals(program.observed.map(_.input), Some(RunnerInput("decoded")))
    assertEquals(program.observed.map(_.config), Some(call.config))
    assertEquals(program.observed.map(_.traceEnabled), Some(call.traceEnabled))
    assertEquals(program.observed.flatMap(_.rolloutId), call.rolloutId)
  }
