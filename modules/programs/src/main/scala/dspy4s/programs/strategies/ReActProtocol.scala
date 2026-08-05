package dspy4s.programs.strategies

import dspy4s.core.contracts.{DspyError, DynamicValues, FieldSpec, RuntimeContext, SignatureLayout, TypeRef}
import dspy4s.programs.contracts.ToolFunction
import dspy4s.signatures.Shape
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import java.nio.charset.StandardCharsets

/** ReAct's model-facing step schema, synthetic finish tool, and rendered trajectory protocol. */
private[programs] object ReActProtocol:
  val loopTrajectoryField: FieldSpec = FieldSpec(
    name = ReActKeys.trajectory,
    typeRef = TypeRef.string,
    description = Some("The sequence of thoughts, tool calls, and observations so far.")
  )

  val extractTrajectoryField: FieldSpec = FieldSpec(
    name = ReActKeys.trajectory,
    typeRef = TypeRef.string,
    description = Some("The completed sequence of thoughts, tool calls, and observations.")
  )

  val nextThoughtField: FieldSpec = FieldSpec(
    name = ReActKeys.nextThought,
    typeRef = TypeRef.string,
    description = Some("Reasoning about the current situation and what to do next.")
  )

  val nextToolNameField: FieldSpec = FieldSpec(
    name = ReActKeys.nextToolName,
    typeRef = TypeRef.string,
    description = Some("The name of the tool to call next; use `finish` when ready to produce the outputs.")
  )

  val nextToolArgsField: FieldSpec = FieldSpec(
    name = ReActKeys.nextToolArgs,
    typeRef = TypeRef.json,
    description = Some("Arguments for the next tool, as a JSON object.")
  )

  /** Leniently decode fields produced through either JSONAdapter or ChatAdapter. */
  val reactStepShape: Shape[ReAct.ReactStep] = new Shape[ReAct.ReactStep]:
    val fieldSpecs: Vector[FieldSpec] = Vector(nextThoughtField, nextToolNameField, nextToolArgsField)

    def encode(value: ReAct.ReactStep): DynamicValue.Record =
      DynamicValue.Record(Chunk.from(Seq(
        ReActKeys.nextThought  -> DynamicValue.Primitive(PrimitiveValue.String(value.nextThought)),
        ReActKeys.nextToolName -> DynamicValue.Primitive(PrimitiveValue.String(value.nextToolName)),
        ReActKeys.nextToolArgs -> (value.nextToolArgs: DynamicValue)
      )))

    def decode(raw: DynamicValue.Record): Either[DspyError, ReAct.ReactStep] =
      Right(ReAct.ReactStep(
        nextThought = DynamicValues.recordGet(raw, ReActKeys.nextThought).map(DynamicValues.renderText).getOrElse(""),
        nextToolName = DynamicValues.recordGet(raw, ReActKeys.nextToolName).map(DynamicValues.renderText).getOrElse(""),
        nextToolArgs = toolArgsRecord(DynamicValues.recordGet(raw, ReActKeys.nextToolArgs))
      ))

  /** Build the no-op tool whose selection terminates the action loop and starts extraction. */
  def finishTool(baseLayout: SignatureLayout): ToolFunction =
    val outputs = baseLayout.outputFields.map(field => s"`${field.name}`").mkString(", ")
    new ToolFunction:
      override val name: String        = ReAct.FinishToolName
      override val description: String =
        s"Marks the task complete: signals that all information needed to produce $outputs is now available."
      override def invoke(args: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicValue] =
        Right(ToolFunction.result("Completed."))

  def renderTrajectory(entries: Vector[ReAct.TrajectoryEntry]): String =
    if entries.isEmpty then "(empty)"
    else
      entries.iterator.map { entry =>
        s"""## Step ${entry.iteration + 1}
           |thought: ${entry.thought}
           |tool_name: ${entry.toolName}
           |tool_args: ${DynamicValues.renderText(entry.toolArgs)}
           |observation: ${entry.observation}""".stripMargin
      }.mkString("\n\n")

  /** Normalize tool arguments into the record consumed by ToolFunction. */
  private def toolArgsRecord(value: Option[DynamicValue]): DynamicValue.Record =
    value match
      case Some(rec: DynamicValue.Record)                         => rec
      case Some(DynamicValue.Primitive(PrimitiveValue.String(s))) => parseJsonRecord(s)
      case _                                                      => DynamicValue.Record.empty

  private val dynamicJsonCodec = Schema.dynamic.jsonCodec

  private def parseJsonRecord(text: String): DynamicValue.Record =
    if text.trim.isEmpty then DynamicValue.Record.empty
    else
      dynamicJsonCodec.decode(text.getBytes(StandardCharsets.UTF_8)) match
        case Right(rec: DynamicValue.Record) => rec
        case _                               => DynamicValue.Record.empty

/** Names ReAct hard-codes in module identities and augmented signatures. */
private[programs] object ReActKeys:
  val reactModule: String   = "react"
  val extractModule: String = "react_extract"

  val trajectory: String   = "trajectory"
  val nextThought: String  = "next_thought"
  val nextToolName: String = "next_tool_name"
  val nextToolArgs: String = "next_tool_args"
