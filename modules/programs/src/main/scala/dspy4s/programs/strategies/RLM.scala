package dspy4s.programs.strategies

import dspy4s.programs.{IterationLimit, LlmCallLimit, OutputCharLimit}
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.ReplCodeInterpreter
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SandboxTool
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.runtime.DenoPyodideInterpreter
import dspy4s.lm.contracts.LanguageModel
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ModuleLifecycle
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.contracts.Prediction
import dspy4s.signatures.{Shape, Signature}
import zio.blocks.schema.DynamicValue

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
    baseSignature     : Signature[I, O],
    maxIterations     : IterationLimit         = IterationLimit(20),
    maxLlmCalls       : LlmCallLimit           = LlmCallLimit(50),
    maxOutputChars    : OutputCharLimit        = OutputCharLimit(10_000),
    verbose           : Boolean                = false,
    tools             : Vector[ToolFunction]   = Vector.empty,
    subLm             : Option[LanguageModel]  = None,
    interpreterFactory: RLM.InterpreterFactory = RLM.defaultInterpreterFactory,
    actionProgramName : String                 = "rlm_action",
    extractProgramName: String                 = "rlm_extract",
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
  val actionSignature: SignatureLayout = baseLayout
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
  val extractSignature: SignatureLayout = baseLayout
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
  val actionPredict: Predict[RLM.ActionInputs, RLM.ActionStep] = actionPredictOverride.getOrElse(Predict(
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
  val extractPredict: Predict[RLM.ExtractInputs, O] = extractPredictOverride.getOrElse(Predict(
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

  override protected val lifecycle: ModuleLifecycle[I, O] = ModuleLifecycle.typed(baseSignature.inputShape)

  override protected def forward(call: ProgramCall[I])(using ctx: RuntimeContext): Either[DspyError, Prediction[O]] =
    new RLMExecution(this).run(call)

object RLM:
  // Source-compatible facade for the model types now housed in RLMModel.
  type ActionInputs = RLMModel.ActionInputs
  val ActionInputs: RLMModel.ActionInputs.type = RLMModel.ActionInputs

  type ExtractInputs = RLMModel.ExtractInputs
  val ExtractInputs: RLMModel.ExtractInputs.type = RLMModel.ExtractInputs

  type ActionStep = RLMModel.ActionStep
  val ActionStep: RLMModel.ActionStep.type = RLMModel.ActionStep

  type ReplAction = RLMModel.ReplAction
  val ReplAction: RLMModel.ReplAction.type = RLMModel.ReplAction

  type ReplExecution = RLMModel.ReplExecution
  val ReplExecution: RLMModel.ReplExecution.type = RLMModel.ReplExecution

  type ReplVariable = RLMModel.ReplVariable
  val ReplVariable: RLMModel.ReplVariable.type = RLMModel.ReplVariable

  type ReplEntry = RLMModel.ReplEntry
  val ReplEntry: RLMModel.ReplEntry.type = RLMModel.ReplEntry

  private[programs] val reasoningField: FieldSpec          = RLMModel.reasoningField
  private[programs] val codeField: FieldSpec               = RLMModel.codeField
  private[programs] val actionStepShape: Shape[ActionStep] = RLMModel.actionStepShape

  /** Builds the per-forward REPL from the sandbox tools and the typed-SUBMIT output fields. */
  type InterpreterFactory = (Vector[SandboxTool], Vector[DenoPyodideInterpreter.OutputField]) => ReplCodeInterpreter

  /** Fresh sandboxed [[DenoPyodideInterpreter]] per forward (closed by RLM afterwards) — upstream's default. */
  val defaultInterpreterFactory: InterpreterFactory =
    (tools, outputs) => new DenoPyodideInterpreter(tools = tools, outputFields = outputs)

  /** Names of built-in sandbox functions user tools must not shadow (upstream `_RESERVED_TOOL_NAMES`). */
  val ReservedToolNames: Set[String] = Set("llm_query", "llm_query_batched", "SUBMIT", "print")

  /** Upstream's `ACTION_INSTRUCTIONS_TEMPLATE`, ported verbatim with the same placeholders. */
  private[programs] def actionInstructionsTemplate(
      inputs          : String,
      outputFields    : String,
      finalOutputNames: String,
      maxLlmCalls     : LlmCallLimit
  ): String =
    RLMReplProtocol.actionInstructionsTemplate(inputs, outputFields, finalOutputNames, maxLlmCalls)

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
      finalJson       : String,
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
      subLm      : Option[LanguageModel],
      ctx        : RuntimeContext
  ): Vector[SandboxTool] =
    RLMSandboxTools.build(maxLlmCalls, subLm, ctx)

  // ── Rendering helpers ───────────────────────────────────────────────────────────────────────────────────────

  /** Python-style type name for the variable metadata (upstream `type(value).__name__`). */
  private[programs] def pythonTypeName(value: DynamicValue): String =
    RLMReplProtocol.pythonTypeName(value)
