package dspy4s.programs.contracts

import dspy4s.adapters.contracts.Adapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Module
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.lm.contracts.LanguageModel

final case class ProgramCall(
    inputs: Map[String, Any],
    config: Map[String, Any] = Map.empty,
    traceEnabled: Boolean = true
)

trait ProgramRuntime:
  def resolveModel(using RuntimeContext): Either[DspyError, LanguageModel]
  def resolveAdapter(using RuntimeContext): Either[DspyError, Adapter]

trait PredictProgram extends Module[ProgramCall, DynamicPrediction]:
  /** Convenience overload of `run` so call sites can write:
    *
    *   predict.run("comment" -> comment, "lang" -> "en")
    *
    * instead of `run(ProgramCall(inputs = Map(...)))`. The inherited
    * `run(input: ProgramCall)` remains available when `config` or
    * `traceEnabled` need to be customized. */
  def run(inputs: (String, Any)*)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    run(ProgramCall(inputs = inputs.toMap))

trait ToolFunction:
  def name: String
  def invoke(args: Map[String, Any])(using RuntimeContext): Either[DspyError, Any]

final case class ToolCallRequest(name: String, args: Map[String, Any])
final case class ToolCallResult(name: String, result: Either[DspyError, Any])
