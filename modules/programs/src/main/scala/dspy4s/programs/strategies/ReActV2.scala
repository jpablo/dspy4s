package dspy4s.programs.strategies

import dspy4s.adapters.contracts.{NativeFunctionCalling, ToolParameterSpec, ToolSpec}
import dspy4s.adapters.internal.ProgramPromptText
import dspy4s.core.contracts.{
  :=,
  ContextWindowExceededError,
  DspyError,
  DynamicValues,
  FieldSpec,
  History,
  ParseError,
  RuntimeContext,
  RuntimeError,
  SignatureLayout,
  ToolCall,
  ToolCallResult,
  TypeRef,
  ValidationError,
  updated
}
import dspy4s.core.data.RawPrediction
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.IterationLimit
import dspy4s.programs.contracts.{ModuleLifecycle, Prediction, ProgramCall, ToolFunction}
import dspy4s.programs.internal.ToolText
import dspy4s.programs.runtime.{RunUsage, ToolExecutor}
import dspy4s.signatures.Signature
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import scala.annotation.tailrec

/** Experimental. An agent that calls tools until it calls `submit` with the outputs of the signature (upstream
  * `dspy.ReActV2` in `dspy/predict/react_v2.py`; upgrade item P10, the six points of P10 in the upgrade plan). It is a
  * new program: [[ReAct]] does not change (G-7b).
  *
  * Each turn runs the step predictor [[reactPredict]] once. The step predictor has the layout [[reactLayout]]: it gets
  * the pending inputs, the [[History]] of the run, and the tools, and gives `next_thought` and `tool_calls`. The
  * adapter of the call decides the form of the tool calls: with native function calling (the adapter has
  * `useNativeFunctionCalling` and the model supports function calling), the request has the tools as provider tools;
  * else the prompt shows the tools as text, and the model writes the tool calls as JSON text.
  *
  * The loop (upstream `ReActV2.forward`), for each turn from 0 to `maxIterations - 1`:
  *   - Run the step predictor. A parse error (a [[dspy4s.core.contracts.ParseError]]) or a
  *     [[dspy4s.core.contracts.ContextWindowExceededError]] ends the loop with the reason `parse_error` or
  *     `context_window_exceeded`, and the forced submit follows. Each other error is the result of the call.
  *   - No tool calls end the loop with the reason `empty_tool_calls`.
  *   - Each tool call with no id gets the id `call_<turn>_<index>`.
  *   - The tool calls run in order. An unknown tool gives the error result `Unknown tool: <name>`. A tool error gives
  *     the error result `Execution error in <name>: <message>`. A tool error is a `Left` of the tool or a non-fatal
  *     exception of the tool (see [[dspy4s.programs.runtime.ToolExecutor.run]]). A fatal error of a tool goes up to the
  *     caller. A `submit` call that succeeds gives the final outputs.
  *   - The event of the turn goes at the end of the history, when it is not empty: the pending inputs, `next_thought`,
  *     `tool_calls`, the results of the calls under [[History.ToolCallResultsKey]] (the port form; upstream keeps the
  *     results inside its `ToolCalls` value), and the final outputs when the turn has them. The pending inputs are the
  *     inputs of the call on the first turn, and none after it.
  *   - The final outputs end the run with the termination reason [[ReActV2.TerminationReason.Submit]].
  *
  * The forced submit (upstream `_forced_submit`), after `maxIterations` turns (the reason `max_iters`) or a break:
  *   - Run the step predictor once more with the call config `tool_choice` set to the function `submit` and
  *     `reasoning_effort` set to null. The adapter drops `tool_choice`, `tools`, and `parallel_tool_calls` from the
  *     request when native function calling is not active (upstream `Adapter._call_preprocess`). The null removes a
  *     default reasoning effort of the model from the request (upstream `"reasoning_effort": None`, which `_canonical`
  *     drops). Each error of this step is the result of the call, also a parse error.
  *   - The tool calls get the ids `call_<maxIterations>_<index>`, with the index in the list of the model. Only the
  *     `submit` calls stay, and only they run.
  *   - No `submit` call is the error "ReActV2 failed to produce final outputs after <reason>: no submit call.".
  *   - The event goes at the end of the history, as in the loop.
  *   - The final outputs end the run with [[ReActV2.TerminationReason.ForcedSubmit]]. Else the error is "ReActV2 failed
  *     to produce final outputs after <reason>: submit failed.", a new line, and the text of each error result, one for
  *     each line.
  *
  * The result is a `Prediction[O]`: the output is the decode of the final outputs with the output shape of
  * `baseSignature`. The raw values have the final outputs, then [[ReActV2.HistoryKey]] (the encoded [[History]]) and
  * [[ReActV2.TerminationReasonKey]] (the wire name of the reason). [[ReActV2.history]] and
  * [[ReActV2.terminationReason]] read them. The LM usage of the result is the sum of the usage of each step call of the
  * run, the forced submit included (upstream `Module.__call__` puts the total usage of the call on the result,
  * `dspy/primitives/module.py`).
  *
  * The start history (point 5): an input field of `baseSignature` with the type
  * [[dspy4s.core.contracts.TypeRef.history]] gives the history at the start of the run (upstream: the `history`
  * argument of the call). It is not a pending input. The events of the run come after its messages.
  *
  * Construction fails with an `IllegalArgumentException` when an output of `baseSignature` has the name `history` or
  * `termination_reason` ("Output field name(s) `history` are reserved by ReActV2 and attached to every returned
  * Prediction. Rename these output fields on your signature."), or when a tool has the name `submit` ("`submit` is
  * reserved by ReActV2 as the final-output tool.").
  *
  * The upstream logger warnings go to [[dspy4s.core.runtime.RuntimeEnvironment.warn]]: "Ending ReActV2 loop after parse
  * failure: <message>", "Ending ReActV2 loop after context window exceeded.", and, for a parse error or a full context
  * window in the forced submit, "Forced submit failed: <message>".
  *
  * @param baseSignature
  *   the inputs and the outputs of the task
  * @param tools
  *   the tools of the agent. [[allTools]] adds `submit`.
  * @param maxIterations
  *   the maximum count of turns before the forced submit (upstream `max_iters`). It is fixed at construction.
  * @param reactPredictOverride
  *   the step predictor that an optimizer wrote (its instructions and demos); `None` gives [[reactPredict]] from
  *   [[reactLayout]]
  */
final case class ReActV2[I, O](
    baseSignature       : Signature[I, O],
    tools               : Vector[ToolFunction],
    maxIterations       : IterationLimit         = IterationLimit(20),
    reactPredictOverride: Option[DynamicPredict] = None
) extends dspy4s.programs.contracts.Module[I, O]:
  import ReActV2.*

  override val moduleName: String = ReActV2.ModuleName

  override protected val lifecycle: ModuleLifecycle[I, O] = ModuleLifecycle.typed(baseSignature.inputShape)

  private val baseLayout: SignatureLayout = baseSignature.layout

  checkNames(baseLayout, tools)

  /** The input field of `baseSignature` that gives the start history: the first input with [[TypeRef.history]]. */
  private val startHistoryField: Option[FieldSpec] = baseLayout.inputFields.find(_.typeRef == TypeRef.history)

  /** The base layout without the start-history field. It gives the default instructions and the input names of the
    * instructions of the step predictor.
    */
  private val taskLayout: SignatureLayout = baseLayout.withInputFields(baseLayout.inputFields.filterNot(field =>
    startHistoryField.exists(_.name == field.name)
  ))

  /** The inputs of the task that the step predictor gets. An input with the name of a field of the step predictor gives
    * its place to that field, as in the field dict of upstream `_make_react_signature`.
    */
  private val taskInputs: Vector[FieldSpec] = taskLayout.inputFields.filterNot(field => StepFieldNames(field.name))

  /** The tools of the loop: [[tools]], then the tool `submit` (upstream `_make_submit_tool`). `submit` has the
    * description "Submit the final outputs for the task." and one argument for each output of `baseSignature`, with the
    * JSON schema of the output in the port form (decision D3): the JSON type of its `TypeRef` for a plain output of a
    * primitive type (a `str`, `int`, `float`, or `bool` output that is not optional and has no enum values); else the
    * property of the output in the output JSON schema of the signature, else the JSON type of its `TypeRef`, else
    * `{"type": "string"}`. Its result is the record of the output values, in the order of the outputs. A missing output
    * is the error "Missing required final output field(s): <names>", with the names in the order of the outputs and
    * `, ` between them. A value that the output shape cannot decode is an error too (upstream: the argument types of
    * the tool).
    *
    * The list has one tool for each name, as the dict of upstream `ReActV2.__init__`: a later tool with a name takes
    * the place of the earlier one.
    */
  val allTools: Vector[ToolFunction] = (tools :+ submitTool(baseSignature)).foldLeft(Vector.empty[ToolFunction]) {
    (unique, tool) =>
      unique.indexWhere(_.name == tool.name) match
        case -1    => unique :+ tool
        case index => unique.updated(index, tool)
  }

  private val toolsByName: Map[String, ToolFunction] = allTools.map(tool => tool.name -> tool).toMap

  /** The value of the `tools` input: the text of each tool of [[allTools]]. */
  private val toolTexts: DynamicValue =
    DynamicValue.Sequence(Chunk.from(allTools.map(tool => text(ToolText.describe(tool)))))

  /** The layout of the step predictor (upstream `_make_react_signature`):
    *   - the inputs: each input of `baseSignature` except the start-history field, as an optional field with its
    *     description; then `history` ([[dspy4s.core.contracts.TypeRef.history]]) and `tools`
    *     ([[dspy4s.core.contracts.TypeRef.tools]]);
    *   - the outputs: `next_thought` (a string; upstream `dspy.Reasoning`, point 4) and `tool_calls`
    *     ([[dspy4s.core.contracts.TypeRef.toolCalls]]);
    *   - the instructions: these lines, with `\n` between them and the whole text stripped: the effective instructions
    *     of the base layout without the start-history field; "You are an Agent. Use the supplied tools to produce
    *     <outputs> from <inputs>."; "Call tools when more information is needed."; "When the final answer is ready,
    *     call `submit` with <outputs>."; "The available tools are: <tools>.". Each list has each name in backticks and
    *     `, ` between the names, <inputs> holds the inputs of the base layout without the start-history field, and
    *     <tools> holds the names of [[allTools]].
    *
    * An input of the task has no default value and no constraints here: upstream makes a new `InputField` with the
    * description only. Thus a later turn does not show the default value of an input that the turn does not have.
    */
  val reactLayout: SignatureLayout = SignatureLayout.of(
    name = baseLayout.name,
    inputFields = taskInputs.map(_.copy(defaultValue = None, constraints = Vector.empty, optional = true)) ++
      Vector(HistoryField, ToolsField),
    outputFields = Vector(NextThoughtField, ToolCallsField),
    instructions = Some(stepInstructions)
  )

  /** The step predictor: [[reactPredictOverride]], else a [[DynamicPredict]] over [[reactLayout]] with the name `react`
    * and the provider tool specs of [[allTools]]. It is the one part of the program that an optimizer can change (the
    * `OptimizableStructure` instance names it `react`). The value of its `tools` input is the list of the texts of
    * [[allTools]] (upstream `Tool.__str__`, in the port form of `ToolText.describe`).
    */
  val reactPredict: DynamicPredict = reactPredictOverride.getOrElse(
    DynamicPredict(layout = reactLayout, name = Some(ReactName), tools = allTools.map(toolSpec))
  )

  /** The instructions of [[reactLayout]] (upstream `_make_react_signature`). */
  private def stepInstructions: String =
    def names(all: Vector[String]): String = all.map(entry => s"`$entry`").mkString(", ")
    val inputs                             = names(taskLayout.inputFields.map(_.name))
    val outputs                            = names(baseLayout.outputFields.map(_.name))
    Vector(
      taskLayout.effectiveInstructions,
      s"You are an Agent. Use the supplied tools to produce $outputs from $inputs.",
      "Call tools when more information is needed.",
      s"When the final answer is ready, call `submit` with $outputs.",
      s"The available tools are: ${names(allTools.map(_.name))}."
    ).mkString("\n").strip

  override protected def forward(call: ProgramCall[I])(using
      RuntimeContext
  ): Either[DspyError, Prediction[O]] =
    val inputs = call.encodedInput(baseSignature.inputShape)
    startHistory(inputs).flatMap { history =>
      // The pending inputs are the inputs that the call gives, in the order of the signature (upstream `forward`).
      val pending = DynamicValues.recordFromEntries(
        taskInputs.flatMap(field => DynamicValues.recordGet(inputs, field.name).map(field.name -> _))
      )
      loop(call, history, pending, turn = 0, RunUsage())
    }

  /** The history at the start of the run (point 5; upstream `_coerce_history`): the value of the start-history field,
    * else an empty history. A value that is not a [[History]] is an error.
    */
  private def startHistory(inputs: DynamicValue.Record): Either[DspyError, History] =
    startHistoryField.flatMap(field => DynamicValues.recordGet(inputs, field.name)) match
      case None | Some(_: DynamicValue.Null.type) => Right(History.empty)
      case Some(value)                            => summon[Schema[History]].fromDynamicValue(value).left.map(error =>
          ValidationError(s"The start history of ReActV2 is not a History: $error")
        )

  /** The turns of the loop (upstream `ReActV2.forward`). See the rules of the loop in the class documentation. */
  @tailrec
  private def loop(
      call   : ProgramCall[I],
      history: History,
      pending: DynamicValue.Record,
      turn   : Int,
      usage  : RunUsage
  )(using RuntimeContext): Either[DspyError, Prediction[O]] =
    if turn >= maxIterations then forcedSubmit(call, history, pending, "max_iters", usage)
    else
      step(call, history, pending, call.config, usage) match
        case Left(error: ParseError) =>
          RuntimeEnvironment.warn(ModuleName, s"Ending ReActV2 loop after parse failure: ${error.message}")
          forcedSubmit(call, history, pending, "parse_error", usage)
        case Left(_: ContextWindowExceededError) =>
          RuntimeEnvironment.warn(ModuleName, "Ending ReActV2 loop after context window exceeded.")
          forcedSubmit(call, history, pending, "context_window_exceeded", usage)
        case Left(error)                         => Left(error)
        case Right(found) if found.calls.isEmpty => forcedSubmit(call, history, pending, "empty_tool_calls", usage)
        case Right(found)                        =>
          val calls              = withIds(found.calls, turn)
          val (results, outputs) = execute(calls)
          val next               = append(history, event(pending, found.thought, calls, results, outputs))
          outputs match
            case Some(values) => finish(values, next, TerminationReason.Submit, usage)
            case None         => loop(call, next, DynamicValue.Record.empty, turn + 1, usage)

  /** The forced submit after the loop (upstream `_forced_submit`). See the rules in the class documentation. */
  private def forcedSubmit(
      call   : ProgramCall[I],
      history: History,
      pending: DynamicValue.Record,
      reason : String,
      usage  : RunUsage
  )(using RuntimeContext): Either[DspyError, Prediction[O]] =
    val config = call.config.updated("tool_choice", SubmitToolChoice).updated("reasoning_effort", DynamicValue.Null)
    step(call, history, pending, config, usage) match
      case Left(error) =>
        error match
          case _: ParseError | _: ContextWindowExceededError =>
            RuntimeEnvironment.warn(ModuleName, s"Forced submit failed: ${error.message}")
          case _ => ()
        Left(error)
      case Right(found) =>
        val calls = withIds(found.calls, maxIterations).filter(_.name == SubmitToolName)
        if calls.isEmpty then
          Left(RuntimeError(ModuleName, s"ReActV2 failed to produce final outputs after $reason: no submit call."))
        else
          val (results, outputs) = execute(calls)
          outputs match
            case Some(values) =>
              val next = append(history, event(pending, found.thought, calls, results, outputs))
              finish(values, next, TerminationReason.ForcedSubmit, usage)
            case None =>
              val errors = results.filter(_.isError).map(result => textOf(result.value)).mkString("\n")
              Left(RuntimeError(
                ModuleName,
                s"ReActV2 failed to produce final outputs after $reason: submit failed.\n$errors"
              ))

  /** One run of the step predictor with the history and the tools, and the tool calls of its answer. The usage of the
    * call goes to `usage`.
    */
  private def step(
      call   : ProgramCall[I],
      history: History,
      pending: DynamicValue.Record,
      config : DynamicValue.Record,
      usage  : RunUsage
  )(using RuntimeContext): Either[DspyError, Step] =
    val input = pending
      .updated(HistoryField.name, summon[Schema[History]].toDynamicValue(history))
      .updated(ToolsField.name, toolTexts)
    reactPredict(ProgramCall(input, config, call.traceEnabled, call.rolloutId)).flatMap { prediction =>
      usage.add(prediction.raw.lmUsage)
      val thought = DynamicValues.recordGet(prediction.output, NextThoughtField.name).filter {
        case _: DynamicValue.Null.type => false
        case _                         => true
      }
      toolCallsOf(prediction.output).map(Step(thought, _))
    }

  /** Run the calls in order (upstream `_execute_tool_calls`). The result has one result for each call, and the final
    * outputs of the last `submit` call that succeeds. Each tool runs through [[ToolExecutor.run]], thus a non-fatal
    * exception of a tool gives an error result (upstream `except Exception`), and a fatal error goes up to the caller.
    */
  private def execute(calls: Vector[ToolCall])(using
      RuntimeContext
  ): (Vector[ToolCallResult], Option[DynamicValue.Record]) =
    calls.foldLeft((Vector.empty[ToolCallResult], Option.empty[DynamicValue.Record])) { (state, request) =>
      val (results, outputs) = state
      toolsByName.get(request.name) match
        case None       => (results :+ errorResult(request, s"Unknown tool: ${request.name}"), outputs)
        case Some(tool) => ToolExecutor.run(tool, request.args) match
            case Right(value) =>
              val submitted = value match
                case record: DynamicValue.Record if request.name == SubmitToolName => Some(record)
                case _                                                             => outputs
              (results :+ ToolCallResult(request.name, value, request.id), submitted)
            case Left(error) =>
              (results :+ errorResult(request, s"Execution error in ${request.name}: ${error.message}"), outputs)
    }

  /** The prediction of the final outputs, with the history and the termination reason as raw values. Its LM usage is
    * the sum of the usage of each step call of the run (upstream `Module.__call__` with `track_usage`).
    */
  private def finish(
      outputs: DynamicValue.Record,
      history: History,
      reason : TerminationReason,
      usage  : RunUsage
  ): Either[DspyError, Prediction[O]] =
    baseSignature.outputShape.decode(outputs).map { output =>
      Prediction(
        output,
        RawPrediction(values = outputs, lmUsage = usage.total)
          .withValue(HistoryKey, summon[Schema[History]].toDynamicValue(history))
          .withValue(TerminationReasonKey, text(reason.wireName))
      )
    }

object ReActV2:

  /** The name of the program in the callbacks and the trace. */
  val ModuleName: String = "react_v2"

  /** The name of the tool that ends the run. */
  val SubmitToolName: String = "submit"

  /** The raw value key of the history of the run. */
  val HistoryKey: String = "history"

  /** The raw value key of the termination reason. */
  val TerminationReasonKey: String = "termination_reason"

  /** How a run ended. */
  enum TerminationReason(val wireName: String) derives CanEqual:
    /** The model called `submit` in the loop. */
    case Submit extends TerminationReason("submit")

    /** The model called `submit` in the forced submit. */
    case ForcedSubmit extends TerminationReason("forced_submit")

  /** The history of the run of `prediction`, or `None` when the raw values have no history. */
  def history(prediction: Prediction[?]): Option[History] =
    History.fromInput(prediction.raw.values, HistoryKey)

  /** The termination reason of `prediction`, or `None` when the raw values have no known reason. */
  def terminationReason(prediction: Prediction[?]): Option[TerminationReason] =
    prediction.raw.get(TerminationReasonKey).flatMap(DynamicValues.asString).flatMap(name =>
      TerminationReason.values.find(_.wireName == name)
    )

  /** The name of the step predictor (upstream `ReActV2.react`). */
  private val ReactName: String = "react"

  /** The description of the tool `submit` (upstream `_make_submit_tool`). */
  private val SubmitDescription: String = "Submit the final outputs for the task."

  /** The outputs that each prediction holds as raw values (upstream `_RESERVED_PREDICTION_KEYS`). */
  private val ReservedOutputNames: Set[String] = Set(HistoryKey, TerminationReasonKey)

  private val HistoryField: FieldSpec     = FieldSpec.normalize(FieldSpec("history", TypeRef.history))
  private val ToolsField: FieldSpec       = FieldSpec.normalize(FieldSpec("tools", TypeRef.tools))
  private val NextThoughtField: FieldSpec = FieldSpec.normalize(FieldSpec("next_thought"))
  private val ToolCallsField: FieldSpec   = FieldSpec.normalize(FieldSpec("tool_calls", TypeRef.toolCalls))

  /** The names of the fields that the step predictor adds to the inputs of the task. */
  private val StepFieldNames: Set[String] =
    Vector(HistoryField, ToolsField, NextThoughtField, ToolCallsField).map(_.name).toSet

  /** The types of the outputs whose argument of `submit` has the JSON type of the `TypeRef`. */
  private val PrimitiveTypes: Set[TypeRef] = Set(TypeRef.string, TypeRef.int, TypeRef.double, TypeRef.bool)

  /** The call config `tool_choice` of the forced submit: the function `submit` (upstream `_forced_submit`). */
  private val SubmitToolChoice: DynamicValue.Record =
    DynamicValues.record("type" := "function", "function" -> DynamicValues.record("name" := SubmitToolName))

  /** The thought and the tool calls of one step. */
  private final case class Step(thought: Option[DynamicValue], calls: Vector[ToolCall])

  /** The construction checks (upstream `ReActV2.__init__`). Throws an `IllegalArgumentException`. */
  private def checkNames(layout: SignatureLayout, tools: Vector[ToolFunction]): Unit =
    val reserved = layout.outputFields.map(_.name).filter(ReservedOutputNames.contains).sorted
    require(
      reserved.isEmpty,
      s"Output field name(s) ${reserved.map(name => s"`$name`").mkString(", ")} are reserved by ReActV2 and attached " +
        "to every returned Prediction. Rename these output fields on your signature."
    )
    require(!tools.exists(_.name == SubmitToolName), "`submit` is reserved by ReActV2 as the final-output tool.")

  /** The tool `submit` of `signature` (upstream `_make_submit_tool`). See [[ReActV2.allTools]]. */
  private def submitTool[A](signature: Signature[?, A]): ToolFunction =
    val outputs    = signature.layout.outputFields
    val outputJson = signature.outputShape.jsonSchemaString
    val properties =
      DynamicValues.recordFromEntries(outputs.map(field => field.name -> argumentSchema(field, outputJson)))
    val schema = DynamicValues.record(
      "type"       := "object",
      "properties" -> properties,
      "required"   -> DynamicValue.Sequence(Chunk.from(outputs.map(field => text(field.name))))
    )
    new ToolFunction:
      override val name: String                             = SubmitToolName
      override val description: String                      = SubmitDescription
      override val argSchema: Vector[(String, TypeRef)]     = outputs.map(field => field.name -> field.typeRef)
      override val inputSchema: Option[DynamicValue.Record] = Some(schema)

      override def invoke(args: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicValue] =
        val missing = outputs.map(_.name).filter(output => DynamicValues.recordGet(args, output).isEmpty)
        if missing.nonEmpty then
          Left(ValidationError(s"Missing required final output field(s): ${missing.mkString(", ")}"))
        else
          val values = DynamicValues.recordFromEntries(
            outputs.flatMap(field => DynamicValues.recordGet(args, field.name).map(field.name -> _))
          )
          signature.outputShape.decode(values).map(signature.outputShape.encode)

  /** The JSON schema of the argument of `submit` for the output `field` (upstream `_json_schema_for_annotation`, in the
    * port form of decision D3). A plain output of a primitive type has the JSON type of its `TypeRef`, as the pydantic
    * schema of upstream (`{"type": "integer"}` for an `int`) and as the arguments of the other tools
    * ([[ToolText.args]]). Each other output has its property in the output JSON schema of the signature, else the JSON
    * type of its `TypeRef`, else `{"type": "string"}`.
    */
  private def argumentSchema(field: FieldSpec, outputJsonSchema: Option[String]): DynamicValue =
    def jsonType: DynamicValue = DynamicValues.record("type" := ToolText.jsonType(field.typeRef).getOrElse("string"))
    if isPlainPrimitive(field) then jsonType
    else ProgramPromptText.outputProperty(field.name, outputJsonSchema).getOrElse(jsonType)

  /** `true` for an output of a primitive type that is not optional and has no enum values. */
  private def isPlainPrimitive(field: FieldSpec): Boolean =
    PrimitiveTypes.contains(field.typeRef) && !field.optional && field.enumValues.isEmpty

  /** The provider tool of `tool` (upstream `Tool.format_as_litellm_function_call`): the input schema of the tool, else
    * an object schema with the arguments of [[ToolText.args]] and each argument in `required`.
    */
  private def toolSpec(tool: ToolFunction): ToolSpec =
    val args = ToolText.args(tool)
    ToolSpec(
      name = tool.name,
      description = Some(tool.description).filter(_.nonEmpty),
      parameters = tool.argSchema.map((argument, typeRef) => ToolParameterSpec(argument, typeRef)),
      inputSchema = Some(tool.inputSchema.getOrElse(
        DynamicValues.record(
          "type"       := "object",
          "properties" -> args,
          "required"   -> DynamicValue.Sequence(Chunk.from(DynamicValues.recordKeys(args).map(text)))
        )
      ))
    )

  /** The tool calls of the answer of a step (upstream `_coerce_tool_calls`): none for a null, and a parse error for a
    * value that is not a tool calls value.
    */
  private def toolCallsOf(output: DynamicValue.Record): Either[DspyError, Vector[ToolCall]] =
    DynamicValues.recordGet(output, ToolCallsField.name) match
      case None | Some(_: DynamicValue.Null.type) => Right(Vector.empty)
      case Some(value)                            => ToolCall.fromDynamic(value).toRight(
          ParseError(ModuleName, s"Received invalid value for tool calls: ${DynamicValues.renderText(value)}")
        )

  /** `calls`, where each call with no id gets the id `call_<turn>_<index>` (upstream `_ensure_tool_call_ids`). */
  private def withIds(calls: Vector[ToolCall], turn: Int): Vector[ToolCall] =
    calls.zipWithIndex.map((found, index) => found.copy(id = found.id.orElse(Some(s"call_${turn}_$index"))))

  /** The event of one turn (upstream `_history_event`): the pending inputs, `next_thought`, the tool calls and their
    * results, then the final outputs.
    */
  private def event(
      pending: DynamicValue.Record,
      thought: Option[DynamicValue],
      calls  : Vector[ToolCall],
      results: Vector[ToolCallResult],
      outputs: Option[DynamicValue.Record]
  ): DynamicValue.Record =
    val withThought = thought.fold(pending)(value => pending.updated(NextThoughtField.name, value))
    val withCalls   =
      if calls.isEmpty then withThought
      else withThought.updated(ToolCallsField.name, NativeFunctionCalling.encodeToolCalls(calls))
    val withResults = if results.isEmpty then withCalls else History.withToolCallResults(withCalls, results)
    outputs.fold(withResults)(_.fields.foldLeft(withResults)((record, entry) => record.updated(entry._1, entry._2)))

  /** `history` with `event` at its end, when the event is not empty (upstream `_append_history_event`). */
  private def append(history: History, event: DynamicValue.Record): History =
    if event.fields.isEmpty then history else history :+ event

  private def errorResult(call: ToolCall, message: String): ToolCallResult =
    ToolCallResult(call.name, text(message), call.id, isError = true)

  private def text(value: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(value))

  /** The text of a result value (upstream `str(result.value)`). */
  private def textOf(value: DynamicValue): String =
    DynamicValues.asString(value).getOrElse(DynamicValues.renderText(value))
