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

import scala.annotation.tailrec

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
    @tailrec
    def loop(
        iteration: Int,
        currentInputs: DynamicValue.Record,
        toolHistory: Vector[DynamicValue]
    ): Either[DspyError, DynamicPrediction] =
      if iteration >= maxIterations then
        Left(RuntimeError("react", s"Reached max iterations ($maxIterations) without producing an answer"))
      else
        val stepCall =
          call.copy(inputs = DynamicValues.recordUpdated(currentInputs, toolHistoryField, sequenceOf(toolHistory)))
        module
          .run(stepCall)
          .flatMap(prediction => processStep(prediction, currentInputs, toolHistory)) match
          case Left(error)                               => Left(error)
          case Right(StepOutcome.Done(prediction))       => Right(prediction)
          case Right(StepOutcome.Continue(ins, history)) => loop(iteration + 1, ins, history)

    loop(iteration = 0, currentInputs = call.inputs, toolHistory = Vector.empty)

  /** Handle one model step: a final answer or an empty tool-request set ends the loop; otherwise run the requested
    * tools and fold their results into the next iteration's inputs.
    */
  private def processStep(
      prediction: DynamicPrediction,
      currentInputs: DynamicValue.Record,
      toolHistory: Vector[DynamicValue]
  )(using RuntimeContext): Either[DspyError, StepOutcome] =
    if hasAnswer(prediction) then
      Right(StepOutcome.Done(prediction))
    else
      extractToolRequests(prediction).flatMap: requests =>
        if requests.isEmpty then
          Right(StepOutcome.Done(prediction))
        else
          runRequests(requests).map(steps => continueWith(steps, currentInputs, toolHistory))

  /** Run each requested tool in order, short-circuiting on the first tool-lookup or execution failure. */
  private def runRequests(
      requests: Vector[ToolCallRequest]
  )(using RuntimeContext): Either[DspyError, Vector[ReActStep]] =
    requests.zipWithIndex.foldLeft[Either[DspyError, Vector[ReActStep]]](Right(Vector.empty)):
      case (acc, (request, idx)) =>
        for
          soFar      <- acc
          callResult <- ToolExecutor.invoke(request, tools)
          value      <- callResult.result
        yield soFar :+ ReActStep(toolName = request.name, toolArgs = request.args, result = value, index = idx)

  /** Fold an executed batch into the next iteration's inputs: append it to the tool history, expose the batch as
    * `tool_results`, and the final tool's value as `tool_result`.
    */
  private def continueWith(
      steps: Vector[ReActStep],
      currentInputs: DynamicValue.Record,
      toolHistory: Vector[DynamicValue]
  ): StepOutcome =
    val batch      = steps.map(_.toRecord)
    val newHistory = toolHistory ++ batch
    val withResults = DynamicValues.recordUpdated(
      DynamicValues.recordUpdated(currentInputs, toolHistoryField, sequenceOf(newHistory)),
      toolResultsField,
      sequenceOf(batch)
    )
    val newInputs = steps.lastOption
      .map(_.result)
      .fold(withResults)(result => DynamicValues.recordUpdated(withResults, toolResultField, result))
    StepOutcome.Continue(newInputs, newHistory)

  private def hasAnswer(prediction: DynamicPrediction): Boolean =
    prediction.get(answerField) match
      case Some(DynamicValue.Primitive(PrimitiveValue.String(text))) => text.trim.nonEmpty
      case Some(_: DynamicValue.Null.type)                           => false
      case Some(_)                                                   => true
      case None                                                      => false

  private def extractToolRequests(prediction: DynamicPrediction): Either[DspyError, Vector[ToolCallRequest]] =
    extractNativeToolCalls(prediction) match
      case Right(requests) if requests.nonEmpty => Right(requests)
      case Left(error)                          => Left(error)
      case Right(_)                             => extractLegacyToolRequest(prediction).map(_.toVector)

  private def extractLegacyToolRequest(prediction: DynamicPrediction): Either[DspyError, Option[ToolCallRequest]] =
    prediction.get(toolNameField) match
      case None                                                                           => Right(None)
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
      case None                            => Right(Vector.empty)
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
        val argsDv =
          DynamicValues.recordGet(rec, ReActKeys.args).orElse(DynamicValues.recordGet(rec, ReActKeys.arguments))
        Right(ToolCallRequest(name.trim, parseToolArgs(argsDv)))
      case Some(other) =>
        Left(RuntimeError("react", s"Tool call name must be non-empty string, found: $other"))
      case None =>
        Left(RuntimeError("react", "Tool call payload is missing 'name'"))

  /** Normalize the raw `args` DynamicValue into the `Record` a tool receives. A record passes through verbatim (no
    * lossy `toAny` projection); a bare string is wrapped as `{input: ...}`; anything else as `{value: ...}`;
    * absent/null becomes the empty record.
    */
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
    * tool-results fields.
    */
  private def sequenceOf(items: Vector[DynamicValue]): DynamicValue =
    DynamicValue.Sequence(Chunk.from(items))

/** Result of processing one ReAct model step: a final prediction, or the inputs/history to continue the loop with. */
private enum StepOutcome:
  case Done(prediction: DynamicPrediction)
  case Continue(inputs: DynamicValue.Record, toolHistory: Vector[DynamicValue])

/** One executed tool step recorded in ReAct's `tool_history` / `tool_results`. Modeled as a datatype and serialized
  * through [[ReActKeys]] so the history record carries no literal string keys.
  */
private final case class ReActStep(toolName: String, toolArgs: DynamicValue, result: DynamicValue, index: Int):
  def toRecord: DynamicValue.Record =
    DynamicValues.record(
      ReActKeys.toolName := toolName,
      ReActKeys.toolArgs -> toolArgs,
      ReActKeys.result   -> result,
      ReActKeys.index    := index
    )

/** Field-name keys for the prediction/tool-call structures ReAct reads and the records it builds, named rather than
  * scattered as string literals. The I/O field names are also the defaults of `ReAct`'s `*Field` parameters.
  */
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
