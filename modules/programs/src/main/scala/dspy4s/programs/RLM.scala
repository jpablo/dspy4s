package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.ReplCodeInterpreter
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SandboxTool
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.updated
import dspy4s.core.runtime.DenoPyodideInterpreter
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.{Prediction, Signature}
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/** RLM — Recursive Language Model (a port of `dspy.RLM`, upstream-`@experimental`; PORT_GAPS G-20 part 2).
  *
  * An inference strategy where LONG CONTEXTS never enter the prompt: input fields are injected into a sandboxed
  * Python REPL as VARIABLES, and the LM iteratively writes code to explore them — printing findings, calling
  * `llm_query(prompt)` / `llm_query_batched(prompts)` (sub-LM calls made FROM INSIDE the generated code) for
  * semantic analysis, and finally `SUBMIT(field=value, ...)` to terminate with the structured outputs. The prompt
  * sees only variable METADATA (type, length, preview) plus the running REPL history. If `maxIterations` is
  * exhausted without a SUBMIT, an extract predict produces the outputs from the trajectory (the fallback).
  * Reference: "Recursive Language Models" (Zhang, Kraska, Khattab, 2025).
  *
  * `RLM[I, O]` is a `Module[TypedCall[I], Prediction[O]]` — SUBMIT's payload (or the extract's reply) is decoded
  * into the typed outputs `O`; the rendered trajectory and `final_reasoning` ride on `.raw`.
  *
  * ==Deltas from Python==
  *   - `llm_query_batched` runs its prompts SEQUENTIALLY (upstream uses an 8-worker thread pool); per-prompt
  *     failures still yield `[ERROR] …` entries in the result list, like upstream.
  *   - Output-type validation is the signature's `Schema` decode over the whole SUBMIT payload, not per-field
  *     pydantic `parse_value`; a failed decode becomes a `[Type Error] …` observation and the loop continues.
  *   - Upstream's `SandboxSerializable` custom-serialization path (bespoke setup/assignment code per value) is
  *     not ported — inputs are plain `DynamicValue`s injected by the interpreter.
  *   - `verbose` logging and the async path are omitted.
  *
  * @param baseSignature      the task signature; inputs become REPL variables, outputs the SUBMIT fields
  * @param maxIterations      REPL interaction budget before the extract fallback
  * @param maxLlmCalls        total `llm_query`(+batched) sub-LM calls allowed per forward
  * @param maxOutputChars     head+tail cap on each REPL output shown in the prompt
  * @param verbose            log each iteration's reasoning/code and step output to stderr as it happens
  *                           (upstream's `verbose` flag; `logger.info` there, `Console.err` here per the
  *                           PredictEngine diagnostics precedent). The surviving record when a run fails mid-loop.
  * @param tools              extra [[ToolFunction]]s callable from generated code (documented in the prompt);
  *                           names must not collide with the built-ins (`llm_query`, `llm_query_batched`,
  *                           `SUBMIT`, `print`)
  * @param subLm              LM for `llm_query` — defaults to the ambient context's LM (pass a cheaper model here)
  * @param interpreterFactory builds the per-forward REPL from (tools, output fields); defaults to a fresh
  *                           [[DenoPyodideInterpreter]]. RLM closes what it builds after each forward.
  */
final case class RLM[I, O](
    baseSignature: Signature[I, O],
    maxIterations: Int = 20,
    maxLlmCalls: Int = 50,
    maxOutputChars: Int = 10_000,
    verbose: Boolean = false,
    tools: Vector[ToolFunction] = Vector.empty,
    subLm: Option[LanguageModel] = None,
    interpreterFactory: RLM.InterpreterFactory = RLM.defaultInterpreterFactory,
    actionProgramName: String = "rlm_action",
    extractProgramName: String = "rlm_extract",
    /** Optional override for the per-iteration action predict (tunable; see ReAct/CodeAct's same pattern). */
    actionPredictOverride: Option[DynamicPredict] = None,
    /** Optional override for the max-iterations extract-fallback predict. */
    extractPredictOverride: Option[DynamicPredict] = None
) extends Module[TypedCall[I], Prediction[O]]:

  override val moduleName: String = "rlm"
  require(maxIterations > 0, "maxIterations must be greater than 0")
  require(maxLlmCalls > 0, "maxLlmCalls must be greater than 0")
  tools.foreach { tool =>
    require(
      !RLM.ReservedToolNames.contains(tool.name),
      s"Tool name '${tool.name}' conflicts with a built-in sandbox function"
    )
  }

  private val baseLayout: SignatureLayout = baseSignature.layout
  private val outputFieldNames: Vector[String] = baseLayout.outputFields.map(_.name)

  /** Per-iteration action signature: `variables_info, repl_history, iteration -> reasoning, code`, instructed
    * with the REPL protocol (upstream's `ACTION_INSTRUCTIONS_TEMPLATE` + user-tool docs). */
  val actionSignature: SignatureLayout =
    baseLayout
      .withFields(Vector(
        FieldSpec("variables_info", FieldRole.Input, typeRef = TypeRef.string,
          description = Some("Metadata about the variables available in the REPL")),
        FieldSpec("repl_history", FieldRole.Input, typeRef = TypeRef.string,
          description = Some("Previous REPL code executions and their outputs")),
        FieldSpec("iteration", FieldRole.Input, typeRef = TypeRef.string,
          description = Some("Current iteration number (1-indexed) out of max_iterations")),
        FieldSpec("reasoning", FieldRole.Output, typeRef = TypeRef.string,
          description = Some("Think step-by-step: what do you know? What remains? Plan your next action.")),
        FieldSpec("code", FieldRole.Output, typeRef = TypeRef.string,
          description = Some("Python code to execute. Use markdown code block format: ```python\\n<code>\\n```"))
      ))
      .withInstructions(Some(buildActionInstructions))

  /** Extract-fallback signature: `variables_info, repl_history -> <base outputs>`. */
  val extractSignature: SignatureLayout =
    baseLayout
      .withFields(Vector(
        FieldSpec("variables_info", FieldRole.Input, typeRef = TypeRef.string,
          description = Some("Metadata about the variables available in the REPL")),
        FieldSpec("repl_history", FieldRole.Input, typeRef = TypeRef.string,
          description = Some("Your REPL interactions so far"))
      ) ++ baseLayout.outputFields)
      .withInstructions(Some(buildExtractInstructions))

  /** The per-iteration action predict (addressable + tunable, like ReAct's `reactPredict`). */
  val actionPredict: DynamicPredict =
    actionPredictOverride.getOrElse(DynamicPredict(layout = actionSignature, name = Some(actionProgramName)))

  /** The extract-fallback predict. */
  val extractPredict: DynamicPredict =
    extractPredictOverride.getOrElse(DynamicPredict(layout = extractSignature, name = Some(extractProgramName)))

  private def buildActionInstructions: String =
    val inputs           = baseLayout.inputFields.map(f => s"`${f.name}`").mkString(", ")
    val finalOutputNames = outputFieldNames.mkString(", ")
    val outputFields = baseLayout.outputFields.map { f =>
      val desc = f.description.filterNot(_.startsWith("${")).fold("")(d => s": $d")
      s"- ${f.name} (${f.typeRef.repr})$desc"
    }.mkString("\n")
    val taskInstructions = baseLayout.instructions.fold("")(_ + "\n\n")
    val toolDocs =
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

  override protected def callInputs(call: TypedCall[I]): DynamicValue.Record =
    baseSignature.inputShape.encode(call.input)
  override protected def callTraceEnabled(call: TypedCall[I]): Boolean = call.traceEnabled
  override protected def tracePayload(prediction: Prediction[O]): DynamicValue.Record = prediction.raw.values

  override protected def forward(call: TypedCall[I])(using ctx: RuntimeContext): Either[DspyError, Prediction[O]] =
    val inputs = baseSignature.inputShape.encode(call.input)
    val inputVars: Map[String, DynamicValue] =
      baseLayout.inputFields.map(f => f.name -> DynamicValues.recordGet(inputs, f.name).getOrElse(DynamicValue.Null)).toMap
    val variablesMeta = baseLayout.inputFields.map { f =>
      RLM.ReplVariable.fromValue(f.name, inputVars(f.name), Some(f))
    }
    val baseCall = ProgramCall(
      inputs       = inputs,
      config       = call.config,
      traceEnabled = call.traceEnabled,
      rolloutId    = call.rolloutId
    )

    val sandboxTools = RLM.makeLlmTools(maxLlmCalls, subLm, ctx) ++ CodeAct.sandboxTools(tools)
    val outputFields = baseLayout.outputFields.map(f => DenoPyodideInterpreter.OutputField(f.name, RLM.pythonTypeOf(f.typeRef)))
    val interpreter  = interpreterFactory(sandboxTools, outputFields)
    try iterate(baseCall, interpreter, inputVars, variablesMeta, history = Vector.empty, iteration = 0)
    finally interpreter.close()

  /** Convenience entry mirroring the typed caller signature. */
  def apply(
      input: I,
      config: DynamicValue.Record = DynamicValue.Record.empty,
      traceEnabled: Boolean = true
  )(using RuntimeContext): Either[DspyError, Prediction[O]] =
    apply(TypedCall(input, config, traceEnabled))

  @scala.annotation.tailrec
  private def iterate(
      call: ProgramCall,
      interpreter: ReplCodeInterpreter,
      inputVars: Map[String, DynamicValue],
      variablesMeta: Vector[RLM.ReplVariable],
      history: Vector[RLM.ReplEntry],
      iteration: Int
  )(using RuntimeContext): Either[DspyError, Prediction[O]] =
    if iteration >= maxIterations then extractFallback(call, variablesMeta, history)
    else
      // Only the declared meta inputs — base inputs reach the LM solely as REPL variable metadata (upstream parity).
      val actionInputs = DynamicValues.recordFromEntries(Vector(
        "variables_info" -> DynamicValues.fromAny(variablesMeta.map(_.format).mkString("\n\n")),
        "repl_history"   -> DynamicValues.fromAny(RLM.renderHistory(history, maxOutputChars)),
        "iteration"      -> DynamicValues.fromAny(s"${iteration + 1}/$maxIterations")
      ))
      actionPredict.apply(call.copy(inputs = actionInputs)) match
        case Left(error) => Left(error)
        case Right(action) =>
          val reasoning = action.get("reasoning").map(DynamicValues.renderText).getOrElse("")
          val rawCode   = action.get("code").map(DynamicValues.renderText).getOrElse("")
          if verbose then
            Console.err.println(
              s"RLM iteration ${iteration + 1}/$maxIterations\nReasoning: $reasoning\nCode:\n$rawCode"
            )
          stepOutcome(interpreter, inputVars, reasoning, rawCode) match
            case Left(error) => Left(error)
            case Right(Left(entry)) => // not final — record and continue
              if verbose then Console.err.println(RLM.formatOutputBlock(entry.output, maxOutputChars))
              iterate(call, interpreter, inputVars, variablesMeta, history :+ entry, iteration + 1)
            case Right(Right((entry, outputsRecord))) => // SUBMIT accepted
              finishWith(outputsRecord, reasoning, history :+ entry)

  /** One REPL step: strip fences, execute with the input variables, classify the result. Returns
    * `Right(Left(entry))` to continue the loop, `Right(Right((entry, outputs)))` on an accepted SUBMIT. */
  private def stepOutcome(
      interpreter: ReplCodeInterpreter,
      inputVars: Map[String, DynamicValue],
      reasoning: String,
      rawCode: String
  ): Either[DspyError, Either[RLM.ReplEntry, (RLM.ReplEntry, DynamicValue.Record)]] =
    RLM.stripCodeFences(rawCode) match
      case Left(fenceError) =>
        Right(Left(RLM.ReplEntry(reasoning, rawCode, s"[Error] $fenceError")))
      case Right(code) =>
        interpreter.execute(code, inputVars) match
          case Left(err) =>
            // Interpreter-level failure: upstream catches CodeInterpreterError into an [Error] observation and
            // keeps looping (our Deno interpreter restarts its process on the next execute).
            Right(Left(RLM.ReplEntry(reasoning, code, s"[Error] ${err.message}")))
          case Right(result) =>
            result.finalOutput match
              case Some(finalJson) =>
                RLM.parseSubmitted(finalJson, outputFieldNames) match
                  case Left(problem) => Right(Left(RLM.ReplEntry(reasoning, code, problem)))
                  case Right(record) =>
                    baseSignature.outputShape.decode(record) match
                      case Left(decodeError) =>
                        Right(Left(RLM.ReplEntry(reasoning, code, s"[Type Error] ${decodeError.message}")))
                      case Right(_) =>
                        Right(Right((RLM.ReplEntry(reasoning, code, s"FINAL: $finalJson"), record)))
              case None =>
                val output =
                  if result.exitCode == 0 then RLM.formatOutput(result.stdout.stripTrailing)
                  else s"[Error] ${result.stderr.stripTrailing}"
                Right(Left(RLM.ReplEntry(reasoning, code, output)))

  private def finishWith(
      outputsRecord: DynamicValue.Record,
      finalReasoning: String,
      history: Vector[RLM.ReplEntry]
  ): Either[DspyError, Prediction[O]] =
    baseSignature.outputShape.decode(outputsRecord).map { output =>
      Prediction(
        output = output,
        raw = DynamicPrediction(values =
          outputsRecord
            .updated("trajectory", DynamicValues.fromAny(RLM.renderHistory(history, maxOutputChars)))
            .updated("final_reasoning", DynamicValues.fromAny(finalReasoning))
        )
      )
    }

  /** Max iterations exhausted: have the extract predict produce the outputs from the trajectory (upstream's
    * `_extract_fallback`). */
  private def extractFallback(
      call: ProgramCall,
      variablesMeta: Vector[RLM.ReplVariable],
      history: Vector[RLM.ReplEntry]
  )(using RuntimeContext): Either[DspyError, Prediction[O]] =
    // Unconditional like upstream's `logger.warning` — not gated on `verbose`.
    Console.err.println("WARN [dspy4s] RLM reached max iterations, using extract to get final output")
    // Only the declared meta inputs — base inputs reach the LM solely as REPL variable metadata (upstream parity).
    val extractInputs = DynamicValues.recordFromEntries(Vector(
      "variables_info" -> DynamicValues.fromAny(variablesMeta.map(_.format).mkString("\n\n")),
      "repl_history"   -> DynamicValues.fromAny(RLM.renderHistory(history, maxOutputChars))
    ))
    for
      extracted <- extractPredict.apply(call.copy(inputs = extractInputs))
      output    <- baseSignature.outputShape.decode(extracted.values)
    yield Prediction(
      output = output,
      raw = DynamicPrediction(values =
        extracted.values
          .updated("trajectory", DynamicValues.fromAny(RLM.renderHistory(history, maxOutputChars)))
          .updated("final_reasoning", DynamicValues.fromAny("Extract forced final output"))
      )
    )

object RLM:

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
      maxLlmCalls: Int
  ): String =
    s"""You are tasked with producing the following outputs given the inputs $inputs:
       |$outputFields
       |
       |You have access to a Python REPL environment. Write Python code and it will be executed. You will see the output, then write more code based on what you learned. This is an iterative process.
       |
       |Available:
       |- Variables: $inputs (your input data)
       |- `llm_query(prompt)` - query a sub-LLM (~500K char capacity) for semantic analysis
       |- `llm_query_batched(prompts)` - query multiple prompts concurrently (much faster for multiple queries)
       |- `print()` - ALWAYS print to see results
       |- `SUBMIT($finalOutputNames)` - submit final output when done
       |- Standard libraries: re, json, collections, math, etc.
       |
       |IMPORTANT: This is ITERATIVE. Each code block you write will execute, you'll see the output, then you decide what to do next. Do NOT try to solve everything in one step.
       |
       |1. EXPLORE FIRST - Look at your data before processing it. Print samples, check types/lengths, understand the structure.
       |2. ITERATE - Write small code snippets, observe outputs, then decide next steps. State persists between iterations.
       |3. VERIFY BEFORE SUBMITTING - If results seem wrong (zeros, empty, unexpected), reconsider your approach.
       |4. USE llm_query FOR SEMANTICS - String matching finds WHERE things are; llm_query understands WHAT things mean.
       |5. MINIMIZE RETYPING (INPUTS & OUTPUTS) - When values are long, precise, or error-prone (IDs, numbers, code, quotes), re-access them via variables and parse/compute in code instead of retyping. Use small, targeted prints to sanity-check, but avoid manual copying when variables can carry the exact value.
       |6. SUBMIT ONLY AFTER SEEING OUTPUTS - SUBMIT ends the current run immediately. If you need to inspect printed output, run it in one step, review the result, then call SUBMIT in a later step.
       |
       |You have max $maxLlmCalls sub-LLM calls. When done, call SUBMIT() with your output.""".stripMargin

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
    /** Upstream `REPLVariable.from_value`: head+tail preview over the rendered value, Python-style type name,
      * field description (skipping `${...}` placeholders). */
    def fromValue(name: String, value: DynamicValue, field: Option[FieldSpec], previewChars: Int = 1000): ReplVariable =
      val rendered = renderValue(value)
      val preview =
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
    def format(index: Int, maxOutputChars: Int): String =
      val reasoningLine = if reasoning.nonEmpty then s"Reasoning: $reasoning\n" else ""
      s"=== Step ${index + 1} ===\n${reasoningLine}Code:\n```python\n$code\n```\n${formatOutputBlock(output, maxOutputChars)}"

  /** Upstream `REPLHistory.format`. */
  private[programs] def renderHistory(entries: Vector[ReplEntry], maxOutputChars: Int): String =
    if entries.isEmpty then "You have not interacted with the REPL environment yet."
    else entries.zipWithIndex.map { case (entry, i) => entry.format(i, maxOutputChars) }.mkString("\n")

  /** Upstream `REPLEntry.format_output`: head+tail truncation with the true length in the header. */
  private[programs] def formatOutputBlock(output: String, maxOutputChars: Int): String =
    val rawLen = output.length
    val body =
      if rawLen > maxOutputChars then
        val half    = maxOutputChars / 2
        val omitted = rawLen - maxOutputChars
        output.take(half) + s"\n\n... (${groupDigits(omitted)} characters omitted) ...\n\n" + output.takeRight(half)
      else output
    s"Output (${groupDigits(rawLen)} chars):\n$body"

  private[programs] def formatOutput(output: String): String =
    if output.isEmpty then "(no output - did you forget to print?)" else output

  // ── SUBMIT payload + code-fence handling ────────────────────────────────────────────────────────────────────

  private val dynamicJsonCodec = Schema.dynamic.jsonCodec

  /** Parse a SUBMIT payload (the interpreter's `finalOutput` JSON) and verify every output field is present.
    * Returns the upstream-style `[Error] …` message on a problem. */
  private[programs] def parseSubmitted(finalJson: String, outputFieldNames: Vector[String]): Either[String, DynamicValue.Record] =
    dynamicJsonCodec.decode(finalJson.getBytes(StandardCharsets.UTF_8)) match
      case Right(record: DynamicValue.Record) =>
        val present = DynamicValues.recordKeys(record).toSet
        val missing = outputFieldNames.filterNot(present.contains)
        if missing.isEmpty then Right(record)
        else Left(s"[Error] Missing output fields: ${missing.sorted.mkString("[", ", ", "]")}. Use SUBMIT(${outputFieldNames.mkString(", ")})")
      case _ =>
        Left(s"[Error] FINAL returned a non-dict payload, expected dict with fields: ${outputFieldNames.mkString(", ")}")

  /** Upstream `_strip_code_fences`: strip decorative outer fences, accept ```python/```py/bare fences, REJECT an
    * explicit non-Python language tag (the error becomes an `[Error]` observation). */
  private[programs] def stripCodeFences(raw: String): Either[String, String] =
    var code = raw.trim
    if !code.contains("```") then Right(code)
    else
      // Strip outer decorative fence pairs (e.g. ```\n```python\n...\n```\n```).
      var lines = code.linesIterator.toVector
      while lines.size >= 2 && lines.head.trim == "```" && lines.last.trim == "```" do
        lines = lines.drop(1).dropRight(1)
      code = lines.mkString("\n").trim
      if !code.contains("```") then Right(code)
      else
        val fenceStart = code.indexOf("```")
        val afterFence = code.drop(fenceStart + 3)
        val newline    = afterFence.indexOf('\n')
        if newline < 0 then Right(code)
        else
          val langLine = afterFence.take(newline).trim
          val lang     = if langLine.isEmpty then "" else langLine.split("\\s+", 2)(0).toLowerCase
          if !Set("python", "py", "python3", "py3", "").contains(lang) then
            Left(s"Expected Python code but got ```$lang fence. Write Python code, not $lang.")
          else
            val remainder = afterFence.drop(newline + 1)
            val blockEnd  = remainder.indexOf("```")
            if blockEnd < 0 then Right(remainder.trim) else Right(remainder.take(blockEnd).trim)

  // ── Built-in llm_query tools ────────────────────────────────────────────────────────────────────────────────

  /** Build `llm_query` / `llm_query_batched` as [[SandboxTool]]s with a SHARED call counter capped at
    * `maxLlmCalls` (upstream `_make_llm_tools`). The sub-LM is `subLm` or the captured context's LM; failures
    * surface as in-sandbox exceptions (`llm_query`) or per-item `[ERROR] …` strings (`llm_query_batched`). */
  private[programs] def makeLlmTools(
      maxLlmCalls: Int,
      subLm: Option[LanguageModel],
      ctx: RuntimeContext
  ): Vector[SandboxTool] =
    val counter = new AtomicInteger(0)

    def checkAndIncrement(n: Int): Either[DspyError, Unit] =
      if counter.get() + n > maxLlmCalls then
        Left(RuntimeError("rlm",
          s"LLM call limit exceeded: ${counter.get()} + $n > $maxLlmCalls. " +
            "Use Python code for aggregation instead of making more LLM calls."))
      else
        val _ = counter.addAndGet(n)
        Right(())

    def queryLm(prompt: String): Either[DspyError, String] =
      val lm = subLm.orElse(ctx.lm.collect { case m: LanguageModel => m })
      lm match
        case None => Left(RuntimeError("rlm", "No LM configured. Configure an ambient LM or pass subLm to RLM."))
        case Some(model) =>
          model
            .call(LmRequest(model = model.id, messages = Vector(Message(role = MessageRole.User, text = Some(prompt)))))(using ctx)
            .map(_.outputs.headOption.map(_.text).getOrElse(""))

    val llmQuery = SandboxTool(
      name = "llm_query",
      parameters = Vector(SandboxTool.Param("prompt", Some("str"))),
      invoke = kwargs =>
        val prompt = DynamicValues.recordGet(kwargs, "prompt").map(DynamicValues.renderText).getOrElse("")
        if prompt.isEmpty then Left(RuntimeError("rlm", "prompt cannot be empty"))
        else
          for
            _      <- checkAndIncrement(1)
            answer <- queryLm(prompt)
          yield DynamicValues.fromAny(answer)
    )

    val llmQueryBatched = SandboxTool(
      name = "llm_query_batched",
      parameters = Vector(SandboxTool.Param("prompts", Some("list"))),
      invoke = kwargs =>
        val prompts = DynamicValues.recordGet(kwargs, "prompts") match
          case Some(seq: DynamicValue.Sequence) => seq.elements.iterator.map(DynamicValues.renderText).toVector
          case _                                => Vector.empty
        if prompts.isEmpty then Right(DynamicValues.fromAny(List.empty[String]))
        else
          checkAndIncrement(prompts.size).map { _ =>
            // Sequential (upstream uses a thread pool); per-prompt failures become [ERROR] items, like upstream.
            val answers = prompts.map(p => queryLm(p).fold(err => s"[ERROR] ${err.message}", identity))
            DynamicValues.fromAny(answers.toList)
          }
    )

    Vector(llmQuery, llmQueryBatched)

  // ── Rendering helpers ───────────────────────────────────────────────────────────────────────────────────────

  /** Python-style type name for the variable metadata (upstream `type(value).__name__`). */
  private[programs] def pythonTypeName(value: DynamicValue): String = value match
    case DynamicValue.Primitive(p) =>
      p match
        case _: PrimitiveValue.String  => "str"
        case _: PrimitiveValue.Boolean => "bool"
        case _: PrimitiveValue.Int | _: PrimitiveValue.Long | _: PrimitiveValue.Short | _: PrimitiveValue.Byte |
            _: PrimitiveValue.BigInt => "int"
        case _: PrimitiveValue.Double | _: PrimitiveValue.Float | _: PrimitiveValue.BigDecimal => "float"
        case _ => "str"
    case _: DynamicValue.Sequence  => "list"
    case _: DynamicValue.Record    => "dict"
    case _: DynamicValue.Map       => "dict"
    case _: DynamicValue.Null.type => "NoneType"
    case _                         => "str"

  /** Render a variable's value for length/preview: primitives as text, records/sequences as JSON (upstream
    * pretty-prints with indent=2; ours is compact — metadata-only delta). */
  private def renderValue(value: DynamicValue): String = value match
    case DynamicValue.Primitive(_) => DynamicValues.renderText(value)
    case _                         => new String(dynamicJsonCodec.encode(value), StandardCharsets.UTF_8)

  private def pythonTypeOf(typeRef: TypeRef): Option[String] = typeRef.repr match
    case "string" => Some("str")
    case "int"    => Some("int")
    case "double" => Some("float")
    case "bool"   => Some("bool")
    case "list"   => Some("list")
    case "json"   => Some("dict")
    case _        => None

  /** Digit grouping like Python's `{:,}` (locale-independent). */
  private def groupDigits(n: Int): String =
    String.format(java.util.Locale.US, "%,d", n)
