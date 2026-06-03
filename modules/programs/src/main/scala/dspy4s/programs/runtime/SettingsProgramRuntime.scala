package dspy4s.programs.runtime

import dspy4s.adapters.contracts.Adapter
import dspy4s.core.contracts.ConfigurationError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.lm.contracts.LanguageModel
import dspy4s.programs.contracts.ProgramRuntime

/** The default [[ProgramRuntime]]: resolve the LM and adapter from the ambient `RuntimeContext` settings. */
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
