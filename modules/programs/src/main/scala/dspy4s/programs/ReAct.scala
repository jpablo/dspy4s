package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.updated
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ToolCallRequest
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.runtime.BasePredictProgram
import dspy4s.programs.runtime.ToolExecutor
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import java.nio.charset.StandardCharsets
import scala.annotation.tailrec

/** ReAct ("Reasoning and Acting"), the tool-using agent paradigm. Port of Python DSPy's `dspy.ReAct`, generalized
  * over any signature.
  *
  * Each iteration, the LM is shown the task inputs and the trajectory so far and emits three output fields —
  * `next_thought` (its reasoning), `next_tool_name` (the tool to call), and `next_tool_args` (the JSON arguments).
  * ReAct runs the named tool, appends the observation to the trajectory, and repeats until the LM selects the
  * injected `finish` tool (or `maxIterations` is reached). A separate reasoning-augmented extractor then reads the
  * full trajectory and produces the user-visible outputs declared in `baseSignature`.
  *
  * Tool selection is via output fields (the canonical DSPy mechanism) — not provider-native function-calling.
  * Tool failures (unknown tool, invocation error) are recorded as trajectory observations rather than failing the
  * program, mirroring Python; an LM-call failure in the react or extract step propagates as `Left`.
  */
final case class ReAct(
    baseSignature: SignatureLayout,
    tools: Vector[ToolFunction],
    maxIterations: Int = 5,
    reactProgramName: String = ReActKeys.reactModule,
    extractorProgramName: String = ReActKeys.extractModule
) extends BasePredictProgram(moduleName = ReActKeys.reactModule):
  require(maxIterations > 0, "maxIterations must be greater than 0")

  /** The supplied tools plus the injected `finish` tool the LM selects to end the loop. */
  private val allTools: Vector[ToolFunction] = tools :+ ReAct.finishTool(baseSignature)
  private val toolsByName: Map[String, ToolFunction] = allTools.map(tool => tool.name -> tool).toMap

  /** Per-iteration signature: base inputs + `trajectory` -> `next_thought` / `next_tool_name` / `next_tool_args`.
    * The base output fields are intentionally dropped here — they are produced by the extractor, not the loop. */
  val reactSignature: SignatureLayout =
    baseSignature
      .withFields(
        baseSignature.inputFields ++ Vector(
          FieldSpec(
            name = ReActKeys.trajectory,
            role = FieldRole.Input,
            typeRef = TypeRef.string,
            description = Some("The sequence of thoughts, tool calls, and observations so far.")
          ),
          FieldSpec(
            name = ReActKeys.nextThought,
            role = FieldRole.Output,
            typeRef = TypeRef.string,
            description = Some("Reasoning about the current situation and what to do next.")
          ),
          FieldSpec(
            name = ReActKeys.nextToolName,
            role = FieldRole.Output,
            typeRef = TypeRef.string,
            description = Some("The name of the tool to call next; use `finish` when ready to produce the outputs.")
          ),
          FieldSpec(
            name = ReActKeys.nextToolArgs,
            role = FieldRole.Output,
            typeRef = TypeRef.json,
            description = Some("Arguments for the next tool, as a JSON object.")
          )
        )
      )
      .withInstructions(Some(buildInstructions))

  /** Final extractor signature: base inputs + base outputs + `trajectory`; reasoning is added by ChainOfThought. */
  val extractorSignature: SignatureLayout =
    baseSignature.append(
      FieldSpec(
        name = ReActKeys.trajectory,
        role = FieldRole.Input,
        typeRef = TypeRef.string,
        description = Some("The completed sequence of thoughts, tool calls, and observations.")
      )
    )

  /** System-prompt instructions for the react step. Mirrors Python's shape: states the task I/O, explains the
    * next_thought / next_tool_name / next_tool_args protocol, and lists the selectable tools (name + description). */
  private def buildInstructions: String =
    val inputs = baseSignature.inputFields.map(field => s"`${field.name}`").mkString(", ")
    val outputs = baseSignature.outputFields.map(field => s"`${field.name}`").mkString(", ")
    val taskPrelude = baseSignature.instructions.fold("")(_ + "\n")
    val toolList = allTools.zipWithIndex.map { case (tool, idx) =>
      val desc = if tool.description.nonEmpty then s": ${tool.description}" else ""
      s"(${idx + 1}) `${tool.name}`$desc"
    }.mkString("\n")
    s"""${taskPrelude}You are an Agent. In each episode you receive the fields $inputs as input, along with your past trajectory.
       |Your goal is to use one or more of the supplied tools to collect the information needed to produce $outputs.
       |Each turn, emit next_thought (your reasoning), next_tool_name (the tool to call), and next_tool_args (its arguments as a JSON object).
       |After each tool call you receive an observation, which is appended to your trajectory.
       |Select `finish` as next_tool_name once you have everything needed to produce the outputs.
       |next_tool_name must be one of:
       |$toolList""".stripMargin

  override protected def forward(call: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    val reactPredict = DynamicPredict(layout = reactSignature, name = Some(reactProgramName))
    for
      extractorLayout <- ChainOfThought.augmentLayout(extractorSignature)
      extractor = DynamicPredict(layout = extractorLayout, name = Some(extractorProgramName))
      trajectory <- runIterations(call, reactPredict, Vector.empty, iteration = 0)
      rendered = DynamicValue.Primitive(PrimitiveValue.String(ReAct.renderTrajectory(trajectory)))
      extracted <- extractor.apply(call.copy(inputs = call.inputs.updated(ReActKeys.trajectory, rendered)))
    yield DynamicPrediction(
      // Attach the trajectory to the extracted prediction so callers can inspect the agent's reasoning.
      values = extracted.values.updated(ReActKeys.trajectory, rendered),
      completions = extracted.completions,
      lmUsage = extracted.lmUsage
    )

  @tailrec
  private def runIterations(
      call: ProgramCall,
      reactPredict: DynamicPredict,
      trajectory: Vector[ReAct.TrajectoryEntry],
      iteration: Int
  )(using RuntimeContext): Either[DspyError, Vector[ReAct.TrajectoryEntry]] =
    if iteration >= maxIterations then Right(trajectory)
    else
      val rendered = DynamicValue.Primitive(PrimitiveValue.String(ReAct.renderTrajectory(trajectory)))
      reactPredict.apply(call.copy(inputs = call.inputs.updated(ReActKeys.trajectory, rendered))) match
        case Left(error) => Left(error)
        case Right(prediction) =>
          val thought = prediction.get(ReActKeys.nextThought).map(DynamicValues.renderText).getOrElse("")
          val toolName = prediction.get(ReActKeys.nextToolName).map(DynamicValues.renderText).getOrElse("").trim
          val toolArgs = toolArgsRecord(prediction.get(ReActKeys.nextToolArgs))
          val observation = runTool(toolName, toolArgs)
          val entry = ReAct.TrajectoryEntry(iteration, thought, toolName, toolArgs, observation)
          // `finish` (or a step that named no tool) ends the loop; otherwise gather more.
          if toolName == ReAct.FinishToolName || toolName.isEmpty then Right(trajectory :+ entry)
          else runIterations(call, reactPredict, trajectory :+ entry, iteration + 1)

  /** Execute the named tool and render its result as an observation. Tool problems never fail the program: an
    * unknown tool or an invocation error becomes an error observation the LM sees on the next turn (as in Python). */
  private def runTool(name: String, args: DynamicValue.Record)(using RuntimeContext): String =
    if name.isEmpty then "No tool was selected."
    else if !toolsByName.contains(name) then s"Execution error: tool `$name` does not exist."
    else
      ToolExecutor.invoke(ToolCallRequest(name, args), allTools) match
        case Right(callResult) =>
          callResult.result match
            case Right(value) => DynamicValues.renderText(value)
            case Left(error)  => s"Execution error in `$name`: ${error.message}"
        case Left(error) => s"Execution error in `$name`: ${error.message}"

  /** Normalize the `next_tool_args` output into the `Record` a tool receives. JSONAdapter yields a `Record`
    * directly; ChatAdapter yields the raw JSON text as a `String` (it has no `json` coercion), so parse that. */
  private def toolArgsRecord(value: Option[DynamicValue]): DynamicValue.Record =
    value match
      case Some(rec: DynamicValue.Record)                        => rec
      case Some(DynamicValue.Primitive(PrimitiveValue.String(s))) => ReAct.parseJsonRecord(s)
      case _                                                      => DynamicValue.Record.empty

object ReAct:
  val FinishToolName: String = "finish"

  private val dynamicJsonCodec = Schema.dynamic.jsonCodec

  /** Parse a JSON-object string (as ChatAdapter surfaces a `json` field) into a `Record`; non-objects / blanks /
    * parse failures yield the empty record. */
  private def parseJsonRecord(text: String): DynamicValue.Record =
    if text.trim.isEmpty then DynamicValue.Record.empty
    else
      dynamicJsonCodec.decode(text.getBytes(StandardCharsets.UTF_8)) match
        case Right(rec: DynamicValue.Record) => rec
        case _                               => DynamicValue.Record.empty

  /** The injected tool the model selects to end the loop. It does no work — selecting it signals "I have enough to
    * produce the outputs"; the observation is a fixed marker and the extractor then produces the real outputs. */
  private def finishTool(baseSignature: SignatureLayout): ToolFunction =
    val outputs = baseSignature.outputFields.map(field => s"`${field.name}`").mkString(", ")
    new ToolFunction:
      override val name: String = FinishToolName
      override val description: String =
        s"Marks the task complete: signals that all information needed to produce $outputs is now available."
      override def invoke(args: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicValue] =
        Right(ToolFunction.result("Completed."))

  /** One step of the agent's trajectory: its thought, the tool it chose with arguments, and the observation. */
  final case class TrajectoryEntry(
      iteration: Int,
      thought: String,
      toolName: String,
      toolArgs: DynamicValue.Record,
      observation: String
  )

  private[programs] def renderTrajectory(entries: Vector[TrajectoryEntry]): String =
    if entries.isEmpty then "(empty)"
    else
      entries.iterator.map { entry =>
        s"""## Step ${entry.iteration + 1}
           |thought: ${entry.thought}
           |tool_name: ${entry.toolName}
           |tool_args: ${DynamicValues.renderText(entry.toolArgs)}
           |observation: ${entry.observation}""".stripMargin
      }.mkString("\n\n")

/** Names ReAct hard-codes: its module / sub-predict names, and the field-name keys it adds to the augmented
  * signatures and reads back from predictions. Named rather than scattered as string literals. (Prose — field
  * descriptions, instructions, observations — stays inline; only the keys/identifiers are constants.) */
private object ReActKeys:
  val reactModule: String   = "react"
  val extractModule: String = "react_extract"

  val trajectory: String   = "trajectory"
  val nextThought: String  = "next_thought"
  val nextToolName: String = "next_tool_name"
  val nextToolArgs: String = "next_tool_args"
