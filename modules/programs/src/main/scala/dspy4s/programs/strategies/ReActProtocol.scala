package dspy4s.programs.strategies

import dspy4s.adapters.internal.ProgramPromptText
import dspy4s.core.contracts.{:=, DspyError, DynamicValues, FieldSpec, RuntimeContext, SignatureLayout, TypeRef}
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.runtime.SettingsProgramRuntime
import dspy4s.signatures.Shape
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** ReAct's model-facing step schema, synthetic finish tool, and rendered trajectory protocol. The fields, the finish
  * tool, and the trajectory text follow upstream `ReAct` in `dspy/predict/react.py`.
  */
private[programs] object ReActProtocol:

  /** The `trajectory` input of the loop and of the extractor (upstream `dspy.InputField()`, with no description). */
  val trajectoryField: FieldSpec = FieldSpec(name = ReActKeys.trajectory, typeRef = TypeRef.string)

  /** The outputs of the loop have no descriptions, as upstream `dspy.OutputField()`. */
  val nextThoughtField: FieldSpec = FieldSpec(name = ReActKeys.nextThought, typeRef = TypeRef.string)

  /** The tool name of the step. Upstream gives it the type `Literal[<tool names>]`, so the port gives it the tool names
    * as enum values. The parsers thus reject a name that is not a tool, and an empty name (decision D7).
    */
  def nextToolNameField(toolNames: Vector[String]): FieldSpec =
    FieldSpec(name = ReActKeys.nextToolName, typeRef = TypeRef.string, enumValues = toolNames)

  /** The arguments of the tool (upstream type `dict[str, Any]`). */
  val nextToolArgsField: FieldSpec = FieldSpec(name = ReActKeys.nextToolArgs, typeRef = TypeRef.json)

  /** Hand-written LENIENT output shape: a missing `next_thought` / `next_tool_name` renders as "" (a step that names no
    * tool ends the loop rather than failing the call), and `next_tool_args` accepts a JSON object (JSONAdapter), a
    * JSON-object string (ChatAdapter has no `json` coercion), or nothing (empty record). Decode never fails;
    * `jsonSchema` stays `None`. The parsers of the port give a parse error before this decode for an empty or an
    * unknown tool name (decision D7).
    */
  val reactStepShape: Shape[ReAct.ReactStep] = new Shape[ReAct.ReactStep]:
    val fieldSpecs: Vector[FieldSpec] = Vector(nextThoughtField, nextToolNameField(Vector.empty), nextToolArgsField)

    def encode(value: ReAct.ReactStep): DynamicValue.Record =
      DynamicValues.record(
        ReActKeys.nextThought  := value.nextThought,
        ReActKeys.nextToolName := value.nextToolName,
        ReActKeys.nextToolArgs -> value.nextToolArgs
      )

    def decode(raw: DynamicValue.Record): Either[DspyError, ReAct.ReactStep] =
      Right(ReAct.ReactStep(
        nextThought = DynamicValues.recordGet(raw, ReActKeys.nextThought).map(DynamicValues.renderText).getOrElse(""),
        nextToolName = DynamicValues.recordGet(raw, ReActKeys.nextToolName).map(DynamicValues.renderText).getOrElse(""),
        nextToolArgs = toolArgsRecord(DynamicValues.recordGet(raw, ReActKeys.nextToolArgs))
      ))

  /** Build the no-op tool whose selection terminates the action loop and starts extraction (upstream `finish`). It does
    * no work: the selection signals "I have enough to produce the outputs", the observation is a fixed marker, and the
    * extractor then produces the real outputs.
    */
  def finishTool(baseLayout: SignatureLayout): ToolFunction =
    val outputs = baseLayout.outputFields.map(field => s"`${field.name}`").mkString(", ")
    ToolFunction.of(
      ReAct.FinishToolName,
      s"Marks the task as complete. That is, signals that all information for producing the outputs, i.e. $outputs, " +
        "are now available to be extracted."
    )(_ => "Completed.")

  /** The text of the observation `value` in the trajectory: the text of upstream `format_field_value` for a `str`
    * field, the type of each field of the trajectory signature.
    */
  def observationText(value: DynamicValue): String =
    ProgramPromptText.fieldValue(trajectoryField, value)

  /** The trajectory text (upstream `ReAct._format_trajectory`): the user message content of the adapter of the run (the
    * adapter of the runtime context, else a `ChatAdapter`) for the signature `thought_0, tool_name_0, tool_args_0,
    * observation_0, ... -> x`. The number of each key is the iteration of its step, so a truncated trajectory keeps the
    * numbers of its steps. An empty trajectory has no fields.
    */
  def renderTrajectory(entries: Vector[ReAct.TrajectoryEntry])(using context: RuntimeContext): String =
    val values = entries.flatMap { entry =>
      val index = entry.iteration
      Vector(
        s"thought_$index"     -> DynamicValues.string(entry.thought),
        s"tool_name_$index"   -> DynamicValues.string(entry.toolName),
        s"tool_args_$index"   -> entry.toolArgs,
        s"observation_$index" -> DynamicValues.string(entry.observation)
      )
    }
    val layout = SignatureLayout.of(
      name = "StringSignature",
      inputFields = values.map((name, _) => FieldSpec(name = name, typeRef = TypeRef.string)),
      outputFields = Vector(FieldSpec(name = "x", typeRef = TypeRef.string)),
      instructions = None
    )
    SettingsProgramRuntime.adapterOrChat(context).formatUserMessageContent(
      layout,
      DynamicValues.recordFromEntries(values)
    )

  /** Normalize tool arguments into the record consumed by ToolFunction. */
  private def toolArgsRecord(value: Option[DynamicValue]): DynamicValue.Record =
    value match
      case Some(rec: DynamicValue.Record)                         => rec
      case Some(DynamicValue.Primitive(PrimitiveValue.String(s))) => parseJsonRecord(s)
      case _                                                      => DynamicValue.Record.empty

  private def parseJsonRecord(text: String): DynamicValue.Record =
    if text.trim.isEmpty then DynamicValue.Record.empty
    else DynamicValues.parseJsonRecord(text).getOrElse(DynamicValue.Record.empty)

/** Names ReAct hard-codes in module identities and augmented signatures. */
private[programs] object ReActKeys:
  val reactModule: String   = "react"
  val extractModule: String = "react_extract"

  val trajectory: String   = "trajectory"
  val nextThought: String  = "next_thought"
  val nextToolName: String = "next_tool_name"
  val nextToolArgs: String = "next_tool_args"
