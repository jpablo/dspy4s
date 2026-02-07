package dspy4s.programs.contracts

import dspy4s.adapters.contracts.Adapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Module
import dspy4s.core.contracts.Prediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.lm.contracts.LanguageModel

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

final case class ProgramCall(
    inputs: Map[String, Any],
    config: Map[String, Any] = Map.empty,
    traceEnabled: Boolean = true
)

trait ProgramRuntime:
  def resolveModel(using RuntimeContext): Either[DspyError, LanguageModel]
  def resolveAdapter(using RuntimeContext): Either[DspyError, Adapter]

trait PredictProgram extends Module[ProgramCall, Prediction]

trait ToolFunction:
  def name: String
  def invoke(args: Map[String, Any])(using RuntimeContext): Either[DspyError, Any]

final case class ToolCallRequest(name: String, args: Map[String, Any])
final case class ToolCallResult(name: String, result: Either[DspyError, Any])

trait AsyncToolFunction extends ToolFunction:
  def invokeAsync(args: Map[String, Any])(using RuntimeContext, ExecutionContext): Future[Either[DspyError, Any]]
