package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.:=
import dspy4s.programs.contracts.PredictProgram
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ToolCallRequest
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.runtime.BasePredictProgram
import dspy4s.programs.runtime.ToolExecutor
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

final case class ReAct(
    module: PredictProgram,
    tools: Vector[ToolFunction],
    maxIterations: Int = 5,
    answerField: String = ReActKeys.answer,
    toolNameField: String = ReActKeys.toolName,
    toolArgsField: String = ReActKeys.toolArgs,
    toolResultField: String = ReActKeys.toolResult,
    toolHistoryField: String = ReActKeys.toolHistory,
    toolResultsField: String = ReActKeys.toolResults
) extends BasePredictProgram(moduleName = "react"):
  require(maxIterations > 0, "maxIterations must be greater than 0")

  override protected def execute(call: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    var currentInputs = call.inputs
    var toolHistory   = Vector.empty[DynamicValue]
    var iteration = 0

    while iteration < maxIterations do
      val stepCall = call.copy(
        inputs = DynamicValues.recordUpdated(currentInputs, toolHistoryField, sequenceOf(toolHistory))
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
              var lastToolResult: Option[DynamicValue] = None
              val iterationSteps = Vector.newBuilder[DynamicValue]

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
                        iterationSteps += ReActStep(
                          toolName = request.name,
                          toolArgs = request.args,
                          result   = value,
                          index    = idx
                        ).toRecord
                idx += 1

              val batch = iterationSteps.result()
              toolHistory = toolHistory ++ batch
              currentInputs = DynamicValues.recordUpdated(
                DynamicValues.recordUpdated(currentInputs, toolHistoryField, sequenceOf(toolHistory)),
                toolResultsField,
                sequenceOf(batch)
              )
              lastToolResult.foreach { result =>
                currentInputs = DynamicValues.recordUpdated(currentInputs, toolResultField, result)
              }
      iteration += 1

    Left(RuntimeError("react", s"Reached max iterations ($maxIterations) without producing an answer"))

  private def hasAnswer(prediction: DynamicPrediction): Boolean =
    prediction.get(answerField) match
      case Some(DynamicValue.Primitive(PrimitiveValue.String(text))) => text.trim.nonEmpty
      case Some(_: DynamicValue.Null.type)                            => false
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
    prediction.get(ReActKeys.toolCalls) match
      case None                       => Right(Vector.empty)
      case Some(_: DynamicValue.Null.type) => Right(Vector.empty)
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
        Left(RuntimeError("react", s"Unsupported tool_calls entry: $other"))

  private def parseToolCallRecord(rec: DynamicValue.Record): Either[DspyError, ToolCallRequest] =
    DynamicValues.recordGet(rec, ReActKeys.name) match
      case Some(DynamicValue.Primitive(PrimitiveValue.String(name))) if name.trim.nonEmpty =>
        val argsDv = DynamicValues.recordGet(rec, ReActKeys.args).orElse(DynamicValues.recordGet(rec, ReActKeys.arguments))
        Right(ToolCallRequest(name.trim, parseToolArgs(argsDv)))
      case Some(other) =>
        Left(RuntimeError("react", s"Tool call name must be non-empty string, found: $other"))
      case None =>
        Left(RuntimeError("react", "Tool call payload is missing 'name'"))

  /** Normalize the raw `args` DynamicValue into the `Record` a tool receives. A record passes through verbatim
    * (no lossy `toAny` projection); a bare string is wrapped as `{input: ...}`; anything else as `{value: ...}`;
    * absent/null becomes the empty record. */
  private def parseToolArgs(raw: Option[DynamicValue]): DynamicValue.Record =
    raw match
      case Some(rec: DynamicValue.Record) =>
        rec
      case Some(prim @ DynamicValue.Primitive(PrimitiveValue.String(value))) if value.trim.nonEmpty =>
        DynamicValues.recordFromEntries(Seq(ReActKeys.input -> prim))
      case Some(_: DynamicValue.Null.type) | None =>
        DynamicValue.Record.empty
      case Some(other) =>
        DynamicValues.recordFromEntries(Seq(ReActKeys.value -> other))

  /** Wrap a vector of already-lifted `DynamicValue`s into a `DynamicValue.Sequence` for the tool-history and
    * tool-results fields. */
  private def sequenceOf(items: Vector[DynamicValue]): DynamicValue =
    DynamicValue.Sequence(Chunk.from(items))

/** One executed tool step recorded in ReAct's `tool_history` / `tool_results`. Modeled as a datatype and
  * serialized through [[ReActKeys]] so the history record carries no literal string keys. */
private final case class ReActStep(toolName: String, toolArgs: DynamicValue, result: DynamicValue, index: Int):
  def toRecord: DynamicValue.Record =
    DynamicValues.record(
      ReActKeys.toolName := toolName,
      ReActKeys.toolArgs -> toolArgs,
      ReActKeys.result   -> result,
      ReActKeys.index    := index
    )

/** Field-name keys for the prediction/tool-call structures ReAct reads and the records it builds, named rather
  * than scattered as string literals. The I/O field names are also the defaults of `ReAct`'s `*Field` parameters. */
private object ReActKeys:
  // Configurable I/O field names (also the ReAct constructor defaults)
  val answer      = "answer"
  val toolName    = "tool_name"
  val toolArgs    = "tool_args"
  val toolResult  = "tool_result"
  val toolHistory = "tool_history"
  val toolResults = "tool_results"

  // Native tool-call payload keys read off a prediction
  val toolCalls = "tool_calls"
  val name      = "name"
  val args      = "args"
  val arguments = "arguments"

  // Recorded tool-step keys
  val result = "result"
  val index  = "index"

  // Synthetic keys used to wrap tool arguments that aren't a JSON object
  val input = "input"
  val value = "value"
