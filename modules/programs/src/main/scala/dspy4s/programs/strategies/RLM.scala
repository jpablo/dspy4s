package dspy4s.programs.strategies

import dspy4s.programs.{IterationLimit, LlmCallLimit, OutputCharLimit}
import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.ReplCodeInterpreter
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SandboxTool
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.updated
import dspy4s.core.runtime.DenoPyodideInterpreter
import dspy4s.lm.contracts.LanguageModel
import dspy4s.programs.contracts.{ActionInterpreter, ActionOutcome}
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ModuleLifecycle
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.runtime.AgentLoop
import dspy4s.programs.runtime.SandboxToolBridge
import dspy4s.typed.{Prediction, Shape, Signature}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

/** RLM — Recursive Language Model (a port of `dspy.RLM`, upstream-`@experimental`; PORT_GAPS G-20 part 2).
  *
  * An inference strategy where LONG CONTEXTS never enter the prompt: input fields are injected into a sandboxed Python
  * REPL as VARIABLES, and the LM iteratively writes code to explore them — printing findings, calling
  * `llm_query(prompt)` / `llm_query_batched(prompts)` (sub-LM calls made FROM INSIDE the generated code) for semantic
  * analysis, and finally `SUBMIT(field=value, ...)` to terminate with the structured outputs. The prompt sees only
  * variable METADATA (type, length, preview) plus the running REPL history. If `maxIterations` is exhausted without a
  * SUBMIT, an extract predict produces the outputs from the trajectory (the fallback). Reference: "Recursive Language
  * Models" (Zhang, Kraska, Khattab, 2025).
  *
  * `RLM[I, O]` is a `Module[I, O]` — SUBMIT's payload (or the extract's reply) is decoded into the typed outputs `O`;
  * the rendered trajectory and `final_reasoning` ride on `.raw`.
  *
  * ==Deltas from Python==
  *   - `llm_query_batched` runs its prompts SEQUENTIALLY (upstream uses an 8-worker thread pool); per-prompt failures
  *     still yield `[ERROR] …` entries in the result list, like upstream.
  *   - Output-type validation is the signature's `Schema` decode over the whole SUBMIT payload, not per-field pydantic
  *     `parse_value`; a failed decode becomes a `[Type Error] …` observation and the loop continues.
  *   - Upstream's `SandboxSerializable` custom-serialization path (bespoke setup/assignment code per value) is not
  *     ported — inputs are plain `DynamicValue`s injected by the interpreter.
  *   - `verbose` logging and the async path are omitted.
  *
  * @param baseSignature
  *   the task signature; inputs become REPL variables, outputs the SUBMIT fields
  * @param maxIterations
  *   REPL interaction budget before the extract fallback
  * @param maxLlmCalls
  *   total `llm_query`(+batched) sub-LM calls allowed per forward
  * @param maxOutputChars
  *   head+tail cap on each REPL output shown in the prompt
  * @param verbose
  *   log each iteration's reasoning/code and step output to stderr as it happens (upstream's `verbose` flag;
  *   `logger.info` there, `Console.err` here per the PredictEngine diagnostics precedent). The surviving record when a
  *   run fails mid-loop.
  * @param tools
  *   extra [[ToolFunction]]s callable from generated code (documented in the prompt); names must not collide with the
  *   built-ins (`llm_query`, `llm_query_batched`, `SUBMIT`, `print`)
  * @param subLm
  *   LM for `llm_query` — defaults to the ambient context's LM (pass a cheaper model here)
  * @param interpreterFactory
  *   builds the per-forward REPL from (tools, output fields); defaults to a fresh [[DenoPyodideInterpreter]]. RLM
  *   closes what it builds after each forward.
  */
final case class RLM[I, O](
    baseSignature: Signature[I, O],
    maxIterations: IterationLimit = IterationLimit(20),
    maxLlmCalls: LlmCallLimit = LlmCallLimit(50),
    maxOutputChars: OutputCharLimit = OutputCharLimit(10_000),
    verbose: Boolean = false,
    tools: Vector[ToolFunction] = Vector.empty,
    subLm: Option[LanguageModel] = None,
    interpreterFactory: RLM.InterpreterFactory = RLM.defaultInterpreterFactory,
    actionProgramName: String = "rlm_action",
    extractProgramName: String = "rlm_extract",
    /** Optional override for the per-iteration action predict (tunable; see ReAct/CodeAct's same pattern) — a TYPED
      * `Predict` over the three declared meta inputs, producing a lenient [[RLM.ActionStep]].
      */
    actionPredictOverride: Option[Predict[RLM.ActionInputs, RLM.ActionStep]] = None,
    /** Optional override for the max-iterations extract-fallback predict — a TYPED `Predict` over the two declared meta
      * inputs, producing the base outputs `O` directly (the base output shape's decode, as before).
      */
    extractPredictOverride: Option[Predict[RLM.ExtractInputs, O]] = None
) extends Module[I, O]:

  override val moduleName: String = "rlm"
  tools.foreach { tool =>
    require(
      !RLM.ReservedToolNames.contains(tool.name),
      s"Tool name '${tool.name}' conflicts with a built-in sandbox function"
    )
  }

  private val baseLayout: SignatureLayout      = baseSignature.layout
  private val outputFieldNames: Vector[String] = baseLayout.outputFields.map(_.name)

  /** Per-iteration action signature: `variables_info, repl_history, iteration -> reasoning, code`, instructed with the
    * REPL protocol (upstream's `ACTION_INSTRUCTIONS_TEMPLATE` + user-tool docs).
    */
  val actionSignature: SignatureLayout =
    baseLayout
      .withInputFields(Vector(
        FieldSpec(
          "variables_info",
          typeRef = TypeRef.string,
          description = Some("Metadata about the variables available in the REPL")
        ),
        FieldSpec(
          "repl_history",
          typeRef = TypeRef.string,
          description = Some("Previous REPL code executions and their outputs")
        ),
        FieldSpec(
          "iteration",
          typeRef = TypeRef.string,
          description = Some("Current iteration number (1-indexed) out of max_iterations")
        )
      ))
      .withOutputFields(Vector(RLM.reasoningField, RLM.codeField))
      .withInstructions(Some(buildActionInstructions))

  /** Extract-fallback signature: `variables_info, repl_history -> <base outputs>`. */
  val extractSignature: SignatureLayout =
    baseLayout
      .withInputFields(Vector(
        FieldSpec(
          "variables_info",
          typeRef = TypeRef.string,
          description = Some("Metadata about the variables available in the REPL")
        ),
        FieldSpec(
          "repl_history",
          typeRef = TypeRef.string,
          description = Some("Your REPL interactions so far")
        )
      ))
      .withInstructions(Some(buildExtractInstructions))

  /** The per-iteration action predict (addressable + tunable, like ReAct's `reactPredict`) — a TYPED
    * `Predict[ActionInputs, ActionStep]`: the action signature's I/O is fully synthetic (base inputs reach the LM only
    * as REPL variable metadata), so both shapes are static. Output decode is lenient (see [[RLM.actionStepShape]]).
    */
  val actionPredict: Predict[RLM.ActionInputs, RLM.ActionStep] =
    actionPredictOverride.getOrElse(Predict(
      signature = Signature(
        name = baseSignature.name,
        layout = actionSignature,
        inputShape = Shape.derived[RLM.ActionInputs],
        outputShape = RLM.actionStepShape
      ),
      name = Some(actionProgramName)
    ))

  /** The extract-fallback predict — a TYPED `Predict[ExtractInputs, O]`: synthetic meta inputs, base outputs `O`
    * decoded through the base output shape (the same decode the dynamic path ran on `extracted.values`).
    */
  val extractPredict: Predict[RLM.ExtractInputs, O] =
    extractPredictOverride.getOrElse(Predict(
      signature = Signature(
        name = baseSignature.name,
        layout = extractSignature,
        inputShape = Shape.derived[RLM.ExtractInputs],
        outputShape = baseSignature.outputShape
      ),
      name = Some(extractProgramName)
    ))

  private def buildActionInstructions: String =
    val inputs           = baseLayout.inputFields.map(f => s"`${f.name}`").mkString(", ")
    val finalOutputNames = outputFieldNames.mkString(", ")
    val outputFields     = baseLayout.outputFields.map { f =>
      val desc = f.description.filterNot(_.startsWith("${")).fold("")(d => s": $d")
      s"- ${f.name} (${f.typeRef.repr})$desc"
    }.mkString("\n")
    val taskInstructions = baseLayout.instructions.fold("")(_ + "\n\n")
    val toolDocs         =
      if tools.isEmpty then ""
      else
        val lines = tools.map { tool =>
          val params = tool.argSchema.map { case (name, typeRef) => s"$name: ${typeRef.repr}" }.mkString(", ")
          val desc   = (if tool.description.nonEmpty then tool.description else "No description").replace("\n", "  ")
          s"- `${tool.name}($params)` - $desc"
        }
        "\n\nAdditional tools available (use these instead of standard library equivalents):\n" + lines.mkString("\n")
    taskInstructions + RLM.actionInstructionsTemplate(inputs, outputFields, finalOutputNames, maxLlmCalls) + toolDocs

  private def buildExtractInstructions: String =
    val taskInstructions = baseLayout.instructions
      .fold("")(t => s"The trajectory was generated with the following objective: \n$t\n\n")
    taskInstructions +
      "Based on the REPL trajectory, extract the final outputs now.\n\n" +
      "Review your trajectory to see what information you gathered and what values you computed, then provide the final outputs."

  override protected val lifecycle: ModuleLifecycle[I, O] =
    ModuleLifecycle.typed(baseSignature.inputShape)

  override protected def forward(call: ProgramCall[I])(using ctx: RuntimeContext): Either[DspyError, Prediction[O]] =
    val inputs                               = call.encodedInput(baseSignature.inputShape)
    val inputVars: Map[String, DynamicValue] =
      baseLayout.inputFields.map(f =>
        f.name -> DynamicValues.recordGet(inputs, f.name).getOrElse(DynamicValue.Null)
      ).toMap
    val variablesMeta = baseLayout.inputFields.map { f =>
      RLM.ReplVariable.fromValue(f.name, inputVars(f.name), Some(f))
    }

    val sandboxTools = RLM.makeLlmTools(maxLlmCalls, subLm, ctx) ++ SandboxToolBridge.fromToolFunctions(tools)
    val outputFields =
      baseLayout.outputFields.map(f => DenoPyodideInterpreter.OutputField(f.name, f.typeRef.pythonTypeName))
    val interpreter       = interpreterFactory(sandboxTools, outputFields)
    val actionInterpreter = replActionInterpreter(interpreter, inputVars)
    try iterate(call, actionInterpreter, variablesMeta)
    finally interpreter.close()

  /** The REPL interaction loop on the shared [[AgentLoop]] skeleton: carry the [[RLM.ReplEntry]] history, each step
    * writing+running code and either continuing or finishing on an accepted SUBMIT; on the iteration budget running
    * out, [[extractFallback]] produces the outputs from the trajectory.
    */
  private def iterate(
      call: ProgramCall[I],
      actionInterpreter: ActionInterpreter[RLM.ReplAction, RLM.ReplExecution],
      variablesMeta: Vector[RLM.ReplVariable]
  )(using RuntimeContext): Either[DspyError, Prediction[O]] =
    AgentLoop.run[Vector[RLM.ReplEntry], Prediction[O]](Vector.empty, 0, maxIterations)(
      onExhausted = history => extractFallback(call, variablesMeta, history)
    )(rlmStep(call, actionInterpreter, variablesMeta))

  /** One REPL iteration as an [[AgentLoop]] step. */
  private def rlmStep(
      call: ProgramCall[I],
      actionInterpreter: ActionInterpreter[RLM.ReplAction, RLM.ReplExecution],
      variablesMeta: Vector[RLM.ReplVariable]
  )(using
      RuntimeContext
  ): (Vector[RLM.ReplEntry], Int) => Either[DspyError, AgentLoop.Step[Vector[RLM.ReplEntry], Prediction[O]]] =
    (history, iteration) =>
      // Only the declared meta inputs — base inputs reach the LM solely as REPL variable metadata (upstream parity).
      val actionInputs = RLM.ActionInputs(
        variables_info = variablesMeta.map(_.format).mkString("\n\n"),
        repl_history = RLM.renderHistory(history, maxOutputChars),
        iteration = s"${iteration + 1}/$maxIterations"
      )
      actionPredict(call.mapInput(_ => actionInputs)).flatMap { action =>
        val reasoning = action.output.reasoning
        val rawCode   = action.output.code
        if verbose then
          Console.err.println(
            s"RLM iteration ${iteration + 1}/$maxIterations\nReasoning: $reasoning\nCode:\n$rawCode"
          )
        val execution: Either[DspyError, ActionOutcome[RLM.ReplExecution]] =
          RLM.stripCodeFences(rawCode) match
            case Left(fenceError) =>
              val entry = RLM.ReplEntry(reasoning, rawCode, s"[Error] $fenceError")
              Right(ActionOutcome.Failed(RLM.ReplExecution.Observed(entry)))
            case Right(code) =>
              actionInterpreter.execute(RLM.ReplAction(reasoning, code))
        execution.flatMap { outcome =>
          outcome.observation match
            case RLM.ReplExecution.Observed(entry) =>
              if verbose then Console.err.println(RLM.formatOutputBlock(entry.output, maxOutputChars))
              Right(AgentLoop.Step.Continue(history :+ entry))
            case RLM.ReplExecution.Submitted(entry, outputsRecord) =>
              finishWith(outputsRecord, reasoning, history :+ entry).map(AgentLoop.Step.Done(_))
        }
      }

  /** Adapt this call's stateful REPL to the shared action-interpreter algebra. User-code, interpreter, and invalid
    * SUBMIT failures are recoverable observations; a valid and type-correct SUBMIT is a distinct terminal observation.
    * The caller owns the REPL lifecycle and closes it after the agent loop.
    */
  private def replActionInterpreter(
      interpreter: ReplCodeInterpreter,
      inputVars: Map[String, DynamicValue]
  ): ActionInterpreter[RLM.ReplAction, RLM.ReplExecution] =
    new ActionInterpreter[RLM.ReplAction, RLM.ReplExecution]:
      override def execute(action: RLM.ReplAction)(using
          RuntimeContext
      ): Either[DspyError, ActionOutcome[RLM.ReplExecution]] =
        interpreter.execute(action.code, inputVars) match
          case Left(err) =>
            // Interpreter-level failure: upstream catches CodeInterpreterError into an [Error] observation and
            // keeps looping (our Deno interpreter restarts its process on the next execute).
            val entry = RLM.ReplEntry(action.reasoning, action.code, s"[Error] ${err.message}")
            Right(ActionOutcome.Failed(RLM.ReplExecution.Observed(entry)))
          case Right(result) =>
            result.finalOutput match
              case Some(finalJson) =>
                RLM.parseSubmitted(finalJson, outputFieldNames) match
                  case Left(problem) =>
                    val entry = RLM.ReplEntry(action.reasoning, action.code, problem)
                    Right(ActionOutcome.Failed(RLM.ReplExecution.Observed(entry)))
                  case Right(record) =>
                    baseSignature.outputShape.decode(record) match
                      case Left(decodeError) =>
                        val entry = RLM.ReplEntry(
                          action.reasoning,
                          action.code,
                          s"[Type Error] ${decodeError.message}"
                        )
                        Right(ActionOutcome.Failed(RLM.ReplExecution.Observed(entry)))
                      case Right(_) =>
                        val entry = RLM.ReplEntry(action.reasoning, action.code, s"FINAL: $finalJson")
                        Right(ActionOutcome.Succeeded(RLM.ReplExecution.Submitted(entry, record)))
              case None =>
                val output =
                  if result.exitCode == 0 then RLM.formatOutput(result.stdout.stripTrailing)
                  else s"[Error] ${result.stderr.stripTrailing}"
                val entry     = RLM.ReplEntry(action.reasoning, action.code, output)
                val execution = RLM.ReplExecution.Observed(entry)
                if result.exitCode == 0 then Right(ActionOutcome.Succeeded(execution))
                else Right(ActionOutcome.Failed(execution))

  private def finishWith(
      outputsRecord: DynamicValue.Record,
      finalReasoning: String,
      history: Vector[RLM.ReplEntry]
  ): Either[DspyError, Prediction[O]] =
    baseSignature.outputShape.decode(outputsRecord).map { output =>
      Prediction(
        output = output,
        raw = RawPrediction(values =
          outputsRecord
            .updated("trajectory", DynamicValues.fromAny(RLM.renderHistory(history, maxOutputChars)))
            .updated("final_reasoning", DynamicValues.fromAny(finalReasoning))
        )
      )
    }

  /** Max iterations exhausted: have the extract predict produce the outputs from the trajectory (upstream's
    * `_extract_fallback`).
    */
  private def extractFallback(
      call: ProgramCall[I],
      variablesMeta: Vector[RLM.ReplVariable],
      history: Vector[RLM.ReplEntry]
  )(using RuntimeContext): Either[DspyError, Prediction[O]] =
    // Unconditional like upstream's `logger.warning` — not gated on `verbose`.
    Console.err.println("WARN [dspy4s] RLM reached max iterations, using extract to get final output")
    // Only the declared meta inputs — base inputs reach the LM solely as REPL variable metadata (upstream parity).
    val extractInputs = RLM.ExtractInputs(
      variables_info = variablesMeta.map(_.format).mkString("\n\n"),
      repl_history = RLM.renderHistory(history, maxOutputChars)
    )
    extractPredict(call.mapInput(_ => extractInputs)).map { extracted =>
      Prediction(
        output = extracted.output,
        raw = RawPrediction(values =
          extracted.raw.values
            .updated("trajectory", DynamicValues.fromAny(RLM.renderHistory(history, maxOutputChars)))
            .updated("final_reasoning", DynamicValues.fromAny("Extract forced final output"))
        )
      )
    }

object RLM:

  // ── The action signature's hand-declared output fields (static; shared by the layout and the typed shape) ──
  private[programs] val reasoningField: FieldSpec = FieldSpec(
    "reasoning",
    typeRef = TypeRef.string,
    description = Some("Think step-by-step: what do you know? What remains? Plan your next action.")
  )
  private[programs] val codeField: FieldSpec = FieldSpec(
    "code",
    typeRef = TypeRef.string,
    description = Some("Python code to execute. Use markdown code block format: ```python\\n<code>\\n```")
  )

  /** The action predict's typed input: the three declared meta fields, names matching the layout exactly (base inputs
    * reach the LM only as REPL variable metadata).
    */
  final case class ActionInputs(variables_info: String, repl_history: String, iteration: String) derives Schema

  /** The extract-fallback predict's typed input. */
  final case class ExtractInputs(variables_info: String, repl_history: String) derives Schema

  /** The typed output of one action step. */
  final case class ActionStep(reasoning: String, code: String)

  /** A parsed Python action ready for the per-call REPL. */
  final case class ReplAction(reasoning: String, code: String)

  /** Post-execution control signal: ordinary observations continue the loop; a validated SUBMIT carries terminal
    * outputs back to the enclosing RLM program.
    */
  enum ReplExecution:
    case Observed(entry: ReplEntry)
    case Submitted(entry: ReplEntry, outputs: DynamicValue.Record)

  /** Hand-written LENIENT output shape mirroring the prior dynamic reads exactly: a missing `reasoning` / `code`
    * renders as "" (an empty code snippet becomes a fence-error observation, not a failed call). Decode never fails;
    * `jsonSchemaString` stays `None` for parity with the prior direct `DynamicPredict` construction.
    */
  private[programs] val actionStepShape: Shape[ActionStep] = new Shape[ActionStep]:
    val fieldSpecs: Vector[FieldSpec] = Vector(reasoningField, codeField)

    def encode(value: ActionStep): DynamicValue.Record =
      DynamicValue.Record(Chunk.from(Seq(
        "reasoning" -> DynamicValue.Primitive(PrimitiveValue.String(value.reasoning)),
        "code"      -> DynamicValue.Primitive(PrimitiveValue.String(value.code))
      )))

    def decode(raw: DynamicValue.Record): Either[DspyError, ActionStep] =
      Right(ActionStep(
        reasoning = DynamicValues.recordGet(raw, "reasoning").map(DynamicValues.renderText).getOrElse(""),
        code = DynamicValues.recordGet(raw, "code").map(DynamicValues.renderText).getOrElse("")
      ))

  /** Builds the per-forward REPL from the sandbox tools and the typed-SUBMIT output fields. */
  type InterpreterFactory =
    (Vector[SandboxTool], Vector[DenoPyodideInterpreter.OutputField]) => ReplCodeInterpreter

  /** Fresh sandboxed [[DenoPyodideInterpreter]] per forward (closed by RLM afterwards) — upstream's default. */
  val defaultInterpreterFactory: InterpreterFactory =
    (tools, outputs) => new DenoPyodideInterpreter(tools = tools, outputFields = outputs)

  /** Names of built-in sandbox functions user tools must not shadow (upstream `_RESERVED_TOOL_NAMES`). */
  val ReservedToolNames: Set[String] = Set("llm_query", "llm_query_batched", "SUBMIT", "print")

  /** Upstream's `ACTION_INSTRUCTIONS_TEMPLATE`, ported verbatim with the same placeholders. */
  private[programs] def actionInstructionsTemplate(
      inputs: String,
      outputFields: String,
      finalOutputNames: String,
      maxLlmCalls: LlmCallLimit
  ): String =
    RLMReplProtocol.actionInstructionsTemplate(inputs, outputFields, finalOutputNames, maxLlmCalls)

  // ── REPL prompt types (upstream repl_types.py) ──────────────────────────────────────────────────────────────

  /** Metadata about a REPL variable, shown to the LM instead of the value itself (the point of RLM). */
  final case class ReplVariable(
      name: String,
      typeName: String,
      desc: String,
      totalLength: Int,
      preview: String
  ):
    /** Upstream `REPLVariable.format`. */
    def format: String =
      val lines = Vector.newBuilder[String]
      lines += s"Variable: `$name` (access it in your code)"
      lines += s"Type: $typeName"
      if desc.nonEmpty then lines += s"Description: $desc"
      lines += s"Total length: ${groupDigits(totalLength)} characters"
      lines += s"Preview:\n```\n$preview\n```"
      lines.result().mkString("\n")

  object ReplVariable:
    /** Upstream `REPLVariable.from_value`: head+tail preview over the rendered value, Python-style type name, field
      * description (skipping `${...}` placeholders).
      */
    def fromValue(name: String, value: DynamicValue, field: Option[FieldSpec], previewChars: Int = 1000): ReplVariable =
      val rendered = renderValue(value)
      val preview  =
        if rendered.length > previewChars then
          val half = previewChars / 2
          rendered.take(half) + "..." + rendered.takeRight(half)
        else rendered
      ReplVariable(
        name = name,
        typeName = pythonTypeName(value),
        desc = field.flatMap(_.description).filterNot(_.startsWith("${")).getOrElse(""),
        totalLength = rendered.length,
        preview = preview
      )

  /** One REPL interaction (upstream `REPLEntry`). */
  final case class ReplEntry(reasoning: String, code: String, output: String):
    def format(index: Int, maxOutputChars: OutputCharLimit): String =
      val reasoningLine = if reasoning.nonEmpty then s"Reasoning: $reasoning\n" else ""
      s"=== Step ${index + 1} ===\n${reasoningLine}Code:\n```python\n$code\n```\n${formatOutputBlock(output, maxOutputChars)}"

  /** Upstream `REPLHistory.format`. */
  private[programs] def renderHistory(entries: Vector[ReplEntry], maxOutputChars: OutputCharLimit): String =
    RLMReplProtocol.renderHistory(entries, maxOutputChars)

  /** Upstream `REPLEntry.format_output`: head+tail truncation with the true length in the header. */
  private[programs] def formatOutputBlock(output: String, maxOutputChars: OutputCharLimit): String =
    RLMReplProtocol.formatOutputBlock(output, maxOutputChars)

  private[programs] def formatOutput(output: String): String =
    RLMReplProtocol.formatOutput(output)

  // ── SUBMIT payload + code-fence handling ────────────────────────────────────────────────────────────────────

  /** Parse a SUBMIT payload (the interpreter's `finalOutput` JSON) and verify every output field is present. Returns
    * the upstream-style `[Error] …` message on a problem.
    */
  private[programs] def parseSubmitted(
      finalJson: String,
      outputFieldNames: Vector[String]
  ): Either[String, DynamicValue.Record] =
    RLMReplProtocol.parseSubmitted(finalJson, outputFieldNames)

  /** Upstream `_strip_code_fences`: strip decorative outer fences, accept ```python/```py/bare fences, REJECT an
    * explicit non-Python language tag (the error becomes an `[Error]` observation).
    */
  private[programs] def stripCodeFences(raw: String): Either[String, String] =
    RLMReplProtocol.stripCodeFences(raw)

  // ── Built-in llm_query tools ────────────────────────────────────────────────────────────────────────────────

  /** Build `llm_query` / `llm_query_batched` as [[SandboxTool]]s with a SHARED call counter capped at `maxLlmCalls`
    * (upstream `_make_llm_tools`). The sub-LM is `subLm` or the captured context's LM; failures surface as in-sandbox
    * exceptions (`llm_query`) or per-item `[ERROR] …` strings (`llm_query_batched`).
    */
  private[programs] def makeLlmTools(
      maxLlmCalls: LlmCallLimit,
      subLm: Option[LanguageModel],
      ctx: RuntimeContext
  ): Vector[SandboxTool] =
    RLMSandboxTools.build(maxLlmCalls, subLm, ctx)

  // ── Rendering helpers ───────────────────────────────────────────────────────────────────────────────────────

  /** Python-style type name for the variable metadata (upstream `type(value).__name__`). */
  private[programs] def pythonTypeName(value: DynamicValue): String =
    RLMReplProtocol.pythonTypeName(value)

  /** Render a variable's value for length/preview: primitives as text, records/sequences as JSON (upstream
    * pretty-prints with indent=2; ours is compact — metadata-only delta).
    */
  private def renderValue(value: DynamicValue): String =
    RLMReplProtocol.renderValue(value)

  /** Digit grouping like Python's `{:,}` (locale-independent). */
  private def groupDigits(n: Int): String =
    RLMReplProtocol.groupDigits(n)
