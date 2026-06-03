package dspy4s.programs.runtime

import dspy4s.adapters.contracts.Adapter
import dspy4s.core.contracts.ConfigurationError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.runtime.CallbackDispatcher
import dspy4s.core.runtime.ContextPropagation
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.programs.contracts.PredictProgram
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ProgramRuntime
import zio.blocks.schema.DynamicValue

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

trait SettingsProgramRuntime extends ProgramRuntime:
  override def resolveModel(using RuntimeContext): Either[DspyError, LanguageModel] =
    summon[RuntimeContext].lm match
      case Some(model: LanguageModel) =>
        Right(model)
      case Some(other) =>
        Left(
          ConfigurationError(
            s"Configured 'lm' must be a LanguageModel, found: ${other.getClass.getSimpleName}"
          )
        )
      case None =>
        Left(ConfigurationError("Missing 'lm' in runtime context"))

  override def resolveAdapter(using RuntimeContext): Either[DspyError, Adapter] =
    summon[RuntimeContext].adapter match
      case Some(adapter: Adapter) =>
        Right(adapter)
      case Some(other) =>
        Left(
          ConfigurationError(
            s"Configured 'adapter' must be an Adapter, found: ${other.getClass.getSimpleName}"
          )
        )
      case None =>
        Left(ConfigurationError("Missing 'adapter' in runtime context"))

abstract class BasePredictProgram(
    override val moduleName: String
) extends PredictProgram
    with SettingsProgramRuntime:

  protected def forward(call: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction]

  protected def tracePayload(prediction: DynamicPrediction): DynamicValue.Record =
    prediction.values

  override final def apply(input: ProgramCall)(using runtime: RuntimeContext): Either[DspyError, DynamicPrediction] =
    val inputBag = input.inputs
    CallbackDispatcher.withModule(moduleName, inputBag) {
      val result = forward(input)
      if input.traceEnabled then
        result match
          case Right(prediction) =>
            val outputs = tracePayload(prediction)
            RuntimeEnvironment.appendTrace(
              TraceEntry(component = moduleName, inputs = inputBag, outputs = outputs)
            )
            RuntimeEnvironment.appendHistory(
              HistoryEntry(component = moduleName, payload = DynamicValues.record("inputs" -> inputBag, "outputs" -> outputs))
            )
          case Left(_) => ()
      result
    }

  override def applyAsync(input: ProgramCall)(using RuntimeContext, ExecutionContext): Future[Either[DspyError, DynamicPrediction]] =
    ContextPropagation.future(apply(input))
