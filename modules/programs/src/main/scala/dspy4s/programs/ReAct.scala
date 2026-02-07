package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Prediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.programs.contracts.PredictProgram
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ToolCallRequest
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.runtime.BasePredictProgram
import dspy4s.programs.runtime.ToolExecutor

final case class ReAct(
    module: PredictProgram,
    tools: Vector[ToolFunction],
    maxIterations: Int = 5,
    answerField: String = "answer",
    toolNameField: String = "tool_name",
    toolArgsField: String = "tool_args",
    toolResultField: String = "tool_result",
    toolHistoryField: String = "tool_history"
) extends BasePredictProgram(moduleName = "react"):
  require(maxIterations > 0, "maxIterations must be greater than 0")

  override protected def execute(call: ProgramCall)(using RuntimeContext): Either[DspyError, Prediction] =
    var currentInputs = call.inputs
    var toolHistory = Vector.empty[Map[String, Any]]
    var lastPrediction: Option[Prediction] = None
    var iteration = 0

    while iteration < maxIterations do
      val stepCall = call.copy(inputs = currentInputs.updated(toolHistoryField, toolHistory))
      module.run(stepCall) match
        case Left(error) =>
          return Left(error)
        case Right(prediction) =>
          lastPrediction = Some(prediction)
          if hasAnswer(prediction) then return Right(prediction)

          extractToolRequest(prediction) match
            case Left(error) =>
              return Left(error)
            case Right(None) =>
              return Right(prediction)
            case Right(Some(request)) =>
              ToolExecutor.invoke(request, tools) match
                case Left(error) =>
                  return Left(error)
                case Right(callResult) =>
                  callResult.result match
                    case Left(error) =>
                      return Left(error)
                    case Right(value) =>
                      val toolStep = Map("tool_name" -> request.name, "tool_args" -> request.args, "result" -> value)
                      toolHistory = toolHistory :+ toolStep
                      currentInputs = currentInputs
                        .updated(toolResultField, value)
                        .updated(toolHistoryField, toolHistory)
      iteration += 1

    Left(RuntimeError("react", s"Reached max iterations ($maxIterations) without producing an answer"))

  private def hasAnswer(prediction: Prediction): Boolean =
    prediction.values.get(answerField) match
      case Some(text: String) => text.trim.nonEmpty
      case Some(_)            => true
      case None               => false

  private def extractToolRequest(prediction: Prediction): Either[DspyError, Option[ToolCallRequest]] =
    prediction.values.get(toolNameField) match
      case None => Right(None)
      case Some(name: String) if name.trim.isEmpty =>
        Right(None)
      case Some(name: String) =>
        Right(Some(ToolCallRequest(name = name.trim, args = parseToolArgs(prediction.values.get(toolArgsField)))))
      case Some(other) =>
        Left(RuntimeError("react", s"Tool name must be a non-empty string, found: $other"))

  private def parseToolArgs(raw: Option[Any]): Map[String, Any] =
    raw match
      case Some(value: collection.Map[?, ?]) =>
        value.iterator.collect { case (k: String, v) => k -> v }.toMap
      case Some(value: String) if value.trim.nonEmpty =>
        Map("input" -> value)
      case Some(value) =>
        Map("value" -> value)
      case None =>
        Map.empty
