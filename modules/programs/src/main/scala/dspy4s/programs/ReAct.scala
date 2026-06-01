package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.lm.contracts.ToolCall
import dspy4s.programs.contracts.PredictProgram
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ToolCallRequest
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.runtime.BasePredictProgram
import dspy4s.programs.runtime.ToolExecutor
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

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

  override protected def execute(call: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    var currentInputs = call.inputs
    var toolHistory   = Vector.empty[Map[String, Any]]
    var iteration = 0

    while iteration < maxIterations do
      val stepCall = call.copy(
        inputs = DynamicValues.recordUpdated(currentInputs, toolHistoryField, DynamicValues.fromAny(toolHistory))
      )
      module.run(stepCall) match
        case Left(error) =>
          return Left(error)
        case Right(prediction) =>
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
                          "result"    -> value,
                          "index"     -> idx
                        )
                idx += 1

              val batch = iterationSteps.result()
              toolHistory = toolHistory ++ batch
              currentInputs = DynamicValues.recordUpdated(
                DynamicValues.recordUpdated(currentInputs, toolHistoryField, DynamicValues.fromAny(toolHistory)),
                toolResultsField,
                DynamicValues.fromAny(batch)
              )
              lastToolResult.foreach { result =>
                currentInputs = DynamicValues.recordUpdated(currentInputs, toolResultField, DynamicValues.fromAny(result))
              }
      iteration += 1

    Left(RuntimeError("react", s"Reached max iterations ($maxIterations) without producing an answer"))

  private def hasAnswer(prediction: DynamicPrediction): Boolean =
    prediction.get(answerField) match
      case Some(DynamicValue.Primitive(PrimitiveValue.String(text))) => text.trim.nonEmpty
      case Some(DynamicValue.Null)                                    => false
      case Some(_)                                                    => true
      case None                                                       => false

  private def extractToolRequests(prediction: DynamicPrediction): Either[DspyError, Vector[ToolCallRequest]] =
    extractNativeToolCalls(prediction) match
      case Right(requests) if requests.nonEmpty => Right(requests)
      case Left(error)          => Left(error)
      case Right(_)             => extractLegacyToolRequest(prediction).map(_.toVector)

  private def extractLegacyToolRequest(prediction: DynamicPrediction): Either[DspyError, Option[ToolCallRequest]] =
    prediction.get(toolNameField) match
      case None                                                                    => Right(None)
      case Some(DynamicValue.Primitive(PrimitiveValue.String(name))) if name.trim.isEmpty => Right(None)
      case Some(DynamicValue.Primitive(PrimitiveValue.String(name))) =>
        Right(Some(ToolCallRequest(
          name = name.trim,
          args = parseToolArgs(prediction.get(toolArgsField))
        )))
      case Some(other) =>
        Left(RuntimeError("react", s"Tool name must be a non-empty string, found: $other"))

  private def extractNativeToolCalls(prediction: DynamicPrediction): Either[DspyError, Vector[ToolCallRequest]] =
    prediction.get("tool_calls") match
      case None                       => Right(Vector.empty)
      case Some(DynamicValue.Null)    => Right(Vector.empty)
      case Some(seq: DynamicValue.Sequence) =>
        parseToolCallsSequence(seq.elements.iterator.toVector)
      case Some(other) =>
        Left(RuntimeError("react", s"tool_calls must be an array, found: $other"))

  private def parseToolCallsSequence(calls: Vector[DynamicValue]): Either[DspyError, Vector[ToolCallRequest]] =
    calls.foldLeft[Either[DspyError, Vector[ToolCallRequest]]](Right(Vector.empty)) { (acc, entry) =>
      for
        soFar  <- acc
        parsed <- parseToolCallEntry(entry)
      yield soFar :+ parsed
    }

  private def parseToolCallEntry(entry: DynamicValue): Either[DspyError, ToolCallRequest] =
    entry match
      case rec: DynamicValue.Record => parseToolCallRecord(rec)
      case other =>
        // Fallback: an already-realized ToolCall lifted into DynamicValue is round-tripped through toAny.
        DynamicValues.toAny(other) match
          case call: ToolCall =>
            Right(ToolCallRequest(call.name, call.args))
          case unsupported =>
            Left(RuntimeError("react", s"Unsupported tool_calls entry: $unsupported"))

  private def parseToolCallRecord(rec: DynamicValue.Record): Either[DspyError, ToolCallRequest] =
    DynamicValues.recordGet(rec, "name") match
      case Some(DynamicValue.Primitive(PrimitiveValue.String(name))) if name.trim.nonEmpty =>
        val argsDv = DynamicValues.recordGet(rec, "args").orElse(DynamicValues.recordGet(rec, "arguments"))
        Right(ToolCallRequest(name.trim, parseToolArgs(argsDv)))
      case Some(other) =>
        Left(RuntimeError("react", s"Tool call name must be non-empty string, found: $other"))
      case None =>
        Left(RuntimeError("react", "Tool call payload is missing 'name'"))

  private def parseToolArgs(raw: Option[DynamicValue]): Map[String, Any] =
    raw match
      case Some(rec: DynamicValue.Record) =>
        rec.fields.iterator.map((k, v) => k -> DynamicValues.toAny(v)).toMap
      case Some(DynamicValue.Primitive(PrimitiveValue.String(value))) if value.trim.nonEmpty =>
        Map("input" -> value)
      case Some(DynamicValue.Null) | None =>
        Map.empty
      case Some(other) =>
        Map("value" -> DynamicValues.toAny(other))
