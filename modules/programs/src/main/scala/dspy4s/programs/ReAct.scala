package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Prediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.lm.contracts.ToolCall
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
    toolHistoryField: String = "tool_history",
    toolResultsField: String = "tool_results"
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

          extractToolRequests(prediction) match
            case Left(error) =>
              return Left(error)
            case Right(requests) if requests.isEmpty =>
              return Right(prediction)
            case Right(requests) =>
              var idx = 0
              var lastToolResult: Option[Any] = None
              val iterationSteps = Vector.newBuilder[Map[String, Any]]

              while idx < requests.size do
                val request = requests(idx)
                ToolExecutor.invoke(request, tools) match
                  case Left(error) =>
                    return Left(error)
                  case Right(callResult) =>
                    callResult.result match
                      case Left(error) =>
                        return Left(error)
                      case Right(value) =>
                        lastToolResult = Some(value)
                        iterationSteps += Map(
                          "tool_name" -> request.name,
                          "tool_args" -> request.args,
                          "result" -> value,
                          "index" -> idx
                        )
                idx += 1

              val batch = iterationSteps.result()
              toolHistory = toolHistory ++ batch
              currentInputs = currentInputs
                .updated(toolHistoryField, toolHistory)
                .updated(toolResultsField, batch)
              lastToolResult.foreach { result =>
                currentInputs = currentInputs.updated(toolResultField, result)
              }
      iteration += 1

    Left(RuntimeError("react", s"Reached max iterations ($maxIterations) without producing an answer"))

  private def hasAnswer(prediction: Prediction): Boolean =
    prediction.values.get(answerField) match
      case Some(text: String) => text.trim.nonEmpty
      case Some(_)            => true
      case None               => false

  private def extractToolRequests(prediction: Prediction): Either[DspyError, Vector[ToolCallRequest]] =
    extractNativeToolCalls(prediction) match
      case Right(requests) if requests.nonEmpty => Right(requests)
      case Left(error)          => Left(error)
      case Right(_)             => extractLegacyToolRequest(prediction).map(_.toVector)

  private def extractLegacyToolRequest(prediction: Prediction): Either[DspyError, Option[ToolCallRequest]] =
    prediction.values.get(toolNameField) match
      case None => Right(None)
      case Some(name: String) if name.trim.isEmpty =>
        Right(None)
      case Some(name: String) =>
        Right(Some(ToolCallRequest(name = name.trim, args = parseToolArgs(prediction.values.get(toolArgsField)))))
      case Some(other) =>
        Left(RuntimeError("react", s"Tool name must be a non-empty string, found: $other"))

  private def extractNativeToolCalls(prediction: Prediction): Either[DspyError, Vector[ToolCallRequest]] =
    prediction.values.get("tool_calls") match
      case None => Right(Vector.empty)
      case Some(calls: Vector[?]) =>
        parseToolCallsSequence(calls)
      case Some(calls: Seq[?]) =>
        parseToolCallsSequence(calls.toVector)
      case Some(other) =>
        Left(RuntimeError("react", s"tool_calls must be an array, found: $other"))

  private def parseToolCallsSequence(calls: Vector[?]): Either[DspyError, Vector[ToolCallRequest]] =
    calls.foldLeft[Either[DspyError, Vector[ToolCallRequest]]](Right(Vector.empty)) { (acc, entry) =>
      for
        soFar <- acc
        parsed <- parseToolCallEntry(entry)
      yield soFar :+ parsed
    }

  private def parseToolCallEntry(entry: Any): Either[DspyError, ToolCallRequest] =
    entry match
      case call: ToolCall =>
        Right(ToolCallRequest(call.name, call.args))
      case call: collection.Map[?, ?] =>
        parseToolCallMap(call)
      case other =>
        Left(RuntimeError("react", s"Unsupported tool_calls entry: $other"))

  private def parseToolCallMap(raw: collection.Map[?, ?]): Either[DspyError, ToolCallRequest] =
    val map = raw.iterator.collect { case (key: String, value) => key -> value }.toMap
    map.get("name") match
      case Some(name: String) if name.trim.nonEmpty =>
        val args = map.get("args").orElse(map.get("arguments"))
        Right(ToolCallRequest(name.trim, parseToolArgs(args)))
      case Some(other) =>
        Left(RuntimeError("react", s"Tool call name must be non-empty string, found: $other"))
      case None =>
        Left(RuntimeError("react", "Tool call payload is missing 'name'"))

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
