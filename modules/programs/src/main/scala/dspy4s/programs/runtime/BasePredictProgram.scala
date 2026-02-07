package dspy4s.programs.runtime

import dspy4s.adapters.contracts.Adapter
import dspy4s.core.contracts.ConfigurationError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.Prediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SettingKeys
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.runtime.CallbackDispatcher
import dspy4s.core.runtime.ContextPropagation
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.programs.contracts.PredictProgram
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ProgramRuntime

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

trait SettingsProgramRuntime extends ProgramRuntime:
  override def resolveModel(using RuntimeContext): Either[DspyError, LanguageModel] =
    summon[RuntimeContext].settings.entries.get(SettingKeys.languageModel.name) match
      case Some(model: LanguageModel) =>
        Right(model)
      case Some(other) =>
        Left(
          ConfigurationError(
            s"Configured '${SettingKeys.languageModel.name}' must be a LanguageModel, found: ${other.getClass.getSimpleName}"
          )
        )
      case None =>
        Left(ConfigurationError(s"Missing '${SettingKeys.languageModel.name}' in runtime settings"))

  override def resolveAdapter(using RuntimeContext): Either[DspyError, Adapter] =
    summon[RuntimeContext].settings.entries.get(SettingKeys.adapter.name) match
      case Some(adapter: Adapter) =>
        Right(adapter)
      case Some(other) =>
        Left(
          ConfigurationError(
            s"Configured '${SettingKeys.adapter.name}' must be an Adapter, found: ${other.getClass.getSimpleName}"
          )
        )
      case None =>
        Left(ConfigurationError(s"Missing '${SettingKeys.adapter.name}' in runtime settings"))

abstract class BasePredictProgram(
    override val moduleName: String
) extends PredictProgram
    with SettingsProgramRuntime:

  protected def execute(call: ProgramCall)(using RuntimeContext): Either[DspyError, Prediction]

  protected def tracePayload(prediction: Prediction): Map[String, Any] =
    prediction.values

  override final def run(input: ProgramCall)(using runtime: RuntimeContext): Either[DspyError, Prediction] =
    CallbackDispatcher.withModule(moduleName, input.inputs) {
      val result = execute(input)
      if input.traceEnabled then
        result match
          case Right(prediction) =>
            val outputs = tracePayload(prediction)
            RuntimeEnvironment.appendTrace(
              TraceEntry(component = moduleName, inputs = input.inputs, outputs = outputs)
            )
            RuntimeEnvironment.appendHistory(
              HistoryEntry(component = moduleName, payload = Map("inputs" -> input.inputs, "outputs" -> outputs))
            )
          case Left(_) => ()
      result
    }

  override def arun(input: ProgramCall)(using RuntimeContext, ExecutionContext): Future[Either[DspyError, Prediction]] =
    ContextPropagation.future(run(input))
