package dspy4s.programs.strategies

import dspy4s.programs.{IterationLimit, LlmCallLimit, OutputCharLimit}
import dspy4s.adapters.internal.ProgramPromptText
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.ReplCodeInterpreter
import dspy4s.core.contracts.ReplInterpreterFactory
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SandboxOutputField
import dspy4s.core.contracts.SandboxTool
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.runtime.DenoPyodideInterpreter
import dspy4s.core.runtime.PythonNames
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ModuleLifecycle
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.contracts.Prediction
import dspy4s.programs.internal.ToolText
import dspy4s.programs.runtime.RunUsage
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
  * `RLM[I, O]` is a `Module[I, O]` — SUBMIT's payload (or the extract's reply) is decoded into the outputs `O`; the
  * rendered trajectory and `final_reasoning` ride on `.raw`.
  *
  * ==Deltas from Python==
  *   - Output-type validation is the signature's `Schema` decode over the whole SUBMIT payload, not per-field pydantic
  *     `parse_value`; a failed decode becomes a `[Type Error] …` observation and the loop continues.
  *   - Upstream's `SandboxSerializable` custom-serialization path (bespoke setup/assignment code per value) is not
  *     ported — inputs are plain `DynamicValue`s injected by the interpreter.
  *   - The async path is omitted.
  *   - The action prompt has no sub-agent text (upstream `SUB_AGENT_INSTRUCTIONS`): the port has no sandbox `dspy`
  *     facade (upgrade item P8). The interpreter rules hold the execution text of the factory only (I7).
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
  * ==Name checks==
  * The constructor rejects a namespace that upstream `_normalize_tools` and `_validate_namespace` reject, with an
  * `IllegalArgumentException`:
  *   - two tools with the same name;
  *   - a tool name that is not a Python identifier, or that is a Python keyword;
  *   - a tool name or an input field name that is reserved: `llm_query`, `llm_query_batched`, `SUBMIT`, `print`,
  *     `dspy`, or a name that starts with `_dspy` or `__dspy` (upstream `is_reserved_sandbox_name`);
  *   - an input field with the name of a tool;
  *   - an output field named `trajectory` or `final_reasoning` (the result keeps these two names on `.raw`).
  *
  * @param tools
  *   extra [[ToolFunction]]s callable from generated code (documented in the prompt); see the name checks
  * @param subLm
  *   LM for `llm_query` — defaults to the ambient context's LM (pass a cheaper model here)
  * @param interpreterFactory
  *   builds the per-forward REPL from (tools, output fields). `None` selects the factory of the runtime context
  *   (`RuntimeContext.interpreterFactory`), and a fresh [[DenoPyodideInterpreter]] when the context has none. RLM
  *   closes what the factory builds after each forward. The action prompt shows the
  *   [[ReplInterpreterFactory.executionInstructions]] of the factory of each call.
  */
final case class RLM[I, O](
    baseSignature     : Signature[I, O],
    maxIterations     : IterationLimit                 = IterationLimit(20),
    maxLlmCalls       : LlmCallLimit                   = LlmCallLimit(50),
    maxOutputChars    : OutputCharLimit                = OutputCharLimit(10_000),
    verbose           : Boolean                        = false,
    tools             : Vector[ToolFunction]           = Vector.empty,
    subLm             : Option[LanguageModel]          = None,
    interpreterFactory: Option[RLM.InterpreterFactory] = None,
    actionProgramName : String                         = "rlm_action",
    extractProgramName: String                         = "rlm_extract",
    /** Optional override for the per-iteration action predict (tunable; see ReAct's same pattern) — a `Predict` over
      * the three declared meta inputs, producing a lenient [[RLM.ActionStep]].
      */
    actionPredictOverride: Option[Predict[RLM.ActionInputs, RLM.ActionStep]] = None,
    /** Optional override for the max-iterations extract-fallback predict — a `Predict` over the two declared meta
      * inputs, producing the base outputs `O` directly (the base output shape's decode, as before).
      */
    extractPredictOverride: Option[Predict[RLM.ExtractInputs, O]] = None,
    /** The execution text of the factory of the runtime context at the first construction, or of
      * [[RLM.defaultInterpreterFactory]] when that context has no factory. The default value reads the context once,
      * and a `copy` keeps the value. Thus a copy in a context with another factory keeps the action instructions of the
      * first construction (upstream sets `_initial_execution_instructions` in `__init__`, and a deep copy keeps it,
      * `dspy/predict/rlm.py`). When [[interpreterFactory]] is set, its execution text wins over this text.
      */
    contextExecutionInstructions: String = RLM.contextExecutionInstructions
) extends Module[I, O]:

  override val moduleName: String = "rlm"
  RLM.checkNames(tools.map(_.name), baseSignature.layout)
  // Experimental decision outputs: `SUBMIT` gets no evidence decoding. Upstream `RLM.__init__` warns, and the program
  // runs.
  if RLM.hasDecisionOutputs(baseSignature) then RuntimeEnvironment.warn("RLM", RLM.DecisionOutputsWarning)

  private val baseLayout: SignatureLayout      = baseSignature.layout
  private val outputFieldNames: Vector[String] = baseLayout.outputFields.map(_.name)

  /** The execution text of the factory that RLM resolves at the first construction: the factory of the program, then
    * the factory of the ambient runtime context, then [[RLM.defaultInterpreterFactory]] (upstream `_build_signatures`).
    * The text of the context comes from [[contextExecutionInstructions]], so a `copy` does not resolve it again. The
    * action instructions hold its interpreter rules. A call on a factory with a different text changes these rules for
    * that call (see [[actionPredictFor]]).
    */
  private val initialExecutionInstructions: String =
    interpreterFactory.fold(contextExecutionInstructions)(_.executionInstructions)

  private val initialInterpreterRules: String = RLMReplProtocol.interpreterRules(initialExecutionInstructions)

  /** Per-iteration action signature: `variables_info, repl_history, iteration -> reasoning, code`, instructed with the
    * REPL protocol (upstream's `ACTION_INSTRUCTIONS_TEMPLATE` + user-tool docs).
    */
  val actionSignature: SignatureLayout = baseLayout
    .withInputFields(Vector(
      RLMModel.variablesInfoField,
      FieldSpec(
        "repl_history",
        typeRef = RLMModel.ReplHistoryType,
        description = Some("Previous REPL code executions and their outputs")
      ),
      FieldSpec(
        "iteration",
        typeRef = TypeRef.string,
        description = Some("Current iteration number (1-indexed) out of max_iters")
      )
    ))
    .withOutputFields(Vector(RLM.reasoningField, RLM.codeField))
    .withInstructions(Some(buildActionInstructions))

  /** Extract-fallback signature: `variables_info, repl_history -> <base outputs>`. */
  val extractSignature: SignatureLayout = baseLayout
    .withInputFields(Vector(
      RLMModel.variablesInfoField,
      FieldSpec(
        "repl_history",
        typeRef = RLMModel.ReplHistoryType,
        description = Some("Your REPL interactions so far")
      )
    ))
    .withInstructions(Some(RLMReplProtocol.extractInstructions(baseLayout.effectiveInstructions)))

  /** The per-iteration action predict (addressable + tunable, like ReAct's `reactPredict`) — a
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

  /** The extract-fallback predict — a `Predict[ExtractInputs, O]`: synthetic meta inputs, base outputs `O` decoded
    * through the base output shape (the same decode the dynamic path ran on `extracted.values`).
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

  /** The action instructions (upstream `RLM._build_signatures`): the effective instructions of the base signature, the
    * template with the interpreter rules of the factory of the construction, and the docs of the user tools. Each
    * output field is `- ` and its placeholder in the structure of the adapters (upstream `translate_field_type`).
    */
  private def buildActionInstructions: String =
    val inputs           = baseLayout.inputFields.map(f => s"`${f.name}`").mkString(", ")
    val finalOutputNames = outputFieldNames.mkString(", ")
    val outputSchema     = baseSignature.outputShape.jsonSchemaString
    val outputFields     =
      baseLayout.outputFields.map(f => s"- ${ProgramPromptText.outputPlaceholder(f, outputSchema)}").mkString("\n")
    val taskInstructions = Option(baseLayout.effectiveInstructions).filter(_.nonEmpty).fold("")(_ + "\n\n")
    val toolDocs         =
      if tools.isEmpty then ""
      else
        val lines = tools.map { tool =>
          val desc = (if tool.description.nonEmpty then tool.description else "No description").replace("\n", "  ")
          s"- `${tool.name}(${ToolText.parameters(tool)})` - $desc"
        }
        "\nAdditional tools available (use these instead of standard library equivalents):\n" + lines.mkString("\n")
    taskInstructions +
      RLM.actionInstructionsTemplate(inputs, outputFields, finalOutputNames, maxLlmCalls, initialInterpreterRules) +
      toolDocs

  /** The action predict for a call on `factory` (upstream `_action_signature_for_current_factory`). A factory with the
    * execution text of the construction gives [[actionPredict]] with no change, so the instructions of an optimizer
    * stay as they are. For a factory with another text, the predict has the interpreter rules of that text: they
    * replace the first copy of the rules of the construction in the instructions, or they come at the end when an
    * optimizer removed those rules. The other parts of the instructions stay.
    */
  private[programs] def actionPredictFor(factory: RLM.InterpreterFactory): Predict[RLM.ActionInputs, RLM.ActionStep] =
    val executionInstructions = factory.executionInstructions
    if executionInstructions == initialExecutionInstructions then actionPredict
    else
      val current = actionPredict.signature.layout.effectiveInstructions
      val rules   = RLMReplProtocol.interpreterRules(executionInstructions)
      val start   = if initialInterpreterRules.isEmpty then -1 else current.indexOf(initialInterpreterRules)
      val updated =
        if start >= 0 then
          current.substring(0, start) + rules + current.substring(start + initialInterpreterRules.length)
        else current + rules
      actionPredict.copy(signature = actionPredict.signature.withInstructions(Some(updated)))

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

  /** Builds the per-forward REPL from the sandbox tools and the schema-validated SUBMIT output fields. */
  type InterpreterFactory = ReplInterpreterFactory

  /** Fresh sandboxed [[DenoPyodideInterpreter]] per forward (closed by RLM afterwards) — upstream's default. Its
    * execution text is the text of upstream `PythonInterpreter.execution_instructions`
    * (`dspy/primitives/python_interpreter.py`, upgrade item I7).
    */
  val defaultInterpreterFactory: InterpreterFactory = new InterpreterFactory:
    override def apply(tools: Vector[SandboxTool], outputs: Vector[SandboxOutputField]): ReplCodeInterpreter =
      new DenoPyodideInterpreter(tools = tools, outputFields = outputs)

    override val executionInstructions: String =
      "Python runs in Pyodide/WebAssembly. State persists across executions, but subprocesses and native " +
        "extensions are unavailable. Python standard libraries such as re, json, collections, and math are " +
        "available. Host filesystem, environment, and network access require explicit permission."

  /** The factory for the next interpreter (upstream `resolve_interpreter_factory`): the factory of the program, then
    * the factory of the runtime context, then [[defaultInterpreterFactory]]. `RLMExecution` calls this for each
    * forward, so a factory that `RuntimeEnvironment.withSettings` installs applies to the calls in its scope.
    */
  def resolveInterpreterFactory(own: Option[InterpreterFactory])(using ctx: RuntimeContext): InterpreterFactory =
    own.orElse(ctx.interpreterFactory).getOrElse(defaultInterpreterFactory)

  /** The execution text of the factory of the current runtime context, or of [[defaultInterpreterFactory]] when the
    * context has no factory. It is the default value of the parameter `contextExecutionInstructions` of [[RLM]].
    */
  def contextExecutionInstructions: String =
    resolveInterpreterFactory(None)(using RuntimeEnvironment.current).executionInstructions

  /** Names of the built-in sandbox functions. A tool or an input field must not use them (upstream
    * `_RESERVED_SANDBOX_NAMES`).
    */
  val ReservedToolNames: Set[String] = Set("llm_query", "llm_query_batched", "SUBMIT", "print")

  /** Names that the result keeps on `.raw`. An output field must not use them (upstream `_RESERVED_RESULT_NAMES`). */
  val ReservedResultNames: Set[String] = Set("trajectory", "final_reasoning")

  /** True when the sandbox owns `name`: a built-in function, the `dspy` facade, or a name that starts with `_dspy` or
    * `__dspy` (upstream `is_reserved_sandbox_name`).
    */
  def isReservedSandboxName(name: String): Boolean =
    ReservedToolNames.contains(name) || name == "dspy" || name.startsWith("_dspy") || name.startsWith("__dspy")

  /** The warning of an RLM with decision outputs (upstream `RLM.__init__`). */
  private val DecisionOutputsWarning: String =
    "RLM support for Noul, Choice, and Score outputs is not implemented consistently: decision evidence decoding is " +
      "not guaranteed, including for nested output types. Use Predict with top-level decision outputs instead."

  /** `true` when an output of `signature` is a `Noul`, a `Score`, or a `Choice` (experimental), also inside an
    * `Option`, a collection, or a map (upstream `RLM.__init__`).
    */
  private def hasDecisionOutputs(signature: Signature[?, ?]): Boolean =
    signature.layout.outputFields.exists(field => DecisionTypes.contains(field.typeRef)) ||
      signature.outputShape.nestedDecisionFields.nonEmpty

  private val DecisionTypes: Set[TypeRef] = Set(TypeRef.noul, TypeRef.score, TypeRef.choice)

  /** The name checks of the constructor (upstream `_normalize_tools` and `_validate_namespace`). See the "Name checks"
    * section of [[RLM]]. Throws an `IllegalArgumentException` for the first problem.
    */
  private def checkNames(toolNames: Vector[String], layout: SignatureLayout): Unit =
    val duplicates = toolNames.diff(toolNames.distinct)
    require(duplicates.isEmpty, s"Duplicate tool name '${duplicates.head}'")
    toolNames.foreach { name =>
      require(
        PythonNames.isIdentifier(name) && !PythonNames.Keywords.contains(name),
        s"Invalid tool name '$name': must be a valid Python identifier and not a keyword"
      )
      require(!isReservedSandboxName(name), s"Tool name '$name' conflicts with a built-in sandbox function")
    }
    val inputNames     = layout.inputFields.map(_.name)
    val reservedInputs = inputNames.filter(isReservedSandboxName).sorted
    require(
      reservedInputs.isEmpty,
      s"Input fields conflict with built-in sandbox functions: ${reservedInputs.mkString(", ")}"
    )
    val toolInputs = inputNames.filter(toolNames.contains).sorted
    require(toolInputs.isEmpty, s"Input fields conflict with user tools: ${toolInputs.mkString(", ")}")
    val reservedOutputs = layout.outputFields.map(_.name).filter(ReservedResultNames.contains).sorted
    require(
      reservedOutputs.isEmpty,
      s"Output fields conflict with RLM result metadata: ${reservedOutputs.mkString(", ")}"
    )

  /** Upstream's `ACTION_INSTRUCTIONS_TEMPLATE`, ported verbatim with the same placeholders. */
  private[programs] def actionInstructionsTemplate(
      inputs          : String,
      outputFields    : String,
      finalOutputNames: String,
      maxLlmCalls     : LlmCallLimit,
      interpreterRules: String
  ): String =
    RLMReplProtocol.actionInstructionsTemplate(inputs, outputFields, finalOutputNames, maxLlmCalls, interpreterRules)

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
    * exceptions (`llm_query`) or per-item `[ERROR] …` strings (`llm_query_batched`). The usage of each sub-LM call goes
    * to `usage`.
    */
  private[programs] def makeLlmTools(
      maxLlmCalls: LlmCallLimit,
      subLm      : Option[LanguageModel],
      ctx        : RuntimeContext,
      usage      : RunUsage = RunUsage()
  ): Vector[SandboxTool] =
    RLMSandboxTools.build(maxLlmCalls, subLm, ctx, usage)
