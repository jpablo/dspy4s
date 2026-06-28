package dspy4s.programs

import dspy4s.core.contracts.CodeInterpreter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.updated
import dspy4s.core.contracts.SignatureOps.*
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.TypedCall
import dspy4s.programs.runtime.TrajectoryTruncation.truncateOnOverflow
import dspy4s.typed.{OutputAugmentation, Prediction, Signature}
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

import scala.util.matching.Regex

/** Iterative code-generation agent. Port of Python DSPy's `dspy.CodeAct`.
  *
  * The flow per iteration:
  *   1. Ask the LM to produce a `generated_code` Python snippet plus a
  *      `finished: bool` flag, given the original task inputs and the
  *      accumulated `trajectory` so far.
  *   2. Strip the fenced ```python code block from the LM's output.
  *   3. Run that code via the configured [[CodeInterpreter]]; capture stdout
  *      (or stderr on failure).
  *   4. Append the snippet + observation to `trajectory`. Exit early if the
  *      LM set `finished=true`.
  *
  * After the loop, a reasoning-augmented [[DynamicPredict]] extractor reads the full trajectory and produces the
  * user-visible outputs declared in `baseSignature`. `CodeAct[I, O]` is a
  * `Module[TypedCall[I], Prediction[WithReasoning[O]]]`: it encodes the typed input, runs the loop + extractor
  * internally over the data-bag layer, and decodes the reply into the base outputs `O` with `reasoning: String`
  * prepended (see [[OutputAugmentation]]). The rendered `trajectory` is kept on `.raw`.
  *
  * '''Tools-inside-code.''' Python `CodeAct` lets the user pass functions the LM's generated Python can call.
  * Pass them as [[tools]]: they are listed in the codeact instructions (so the LM knows they exist), and on a
  * sandboxed [[dspy4s.core.runtime.DenoPyodideInterpreter]] the SAME vector is wired into the sandbox via
  * [[sandboxTools]] so the calls execute (`new DenoPyodideInterpreter(tools = program.sandboxTools)`). The plain
  * [[dspy4s.core.runtime.SubprocessPythonInterpreter]] has no bridge — there, pre-load tools into the
  * environment or go without. (Upstream injects each tool's Python SOURCE into the interpreter, which is why it
  * only accepts plain functions; the dspy4s bridge is RPC, so Scala-implemented tools work too.)
  *
  * '''Per-call iteration override.''' Python accepts `max_iters` as a call kwarg; the dspy4s idiom is the
  * immutable copy — `program.copy(maxIterations = n).apply(...)` — rather than a magic key in the per-call
  * config bag (which is reserved for provider options).
  *
  * '''Persistent REPL state.''' The default [[dspy4s.core.runtime.SubprocessPythonInterpreter]] is stateless
  * across snippets; the trajectory carries earlier code/output as PROMPT context, so the LM regenerates what it
  * needs. On the stateful Deno+Pyodide interpreter, variables genuinely persist between iterations (upstream
  * behavior).
  *
  * '''Closing the interpreter.''' CodeAct does **not** call `interpreter.close()` itself — the caller owns
  * lifecycle. (Upstream shuts the interpreter down at the end of every `forward`, even a caller-supplied one —
  * a delta we deliberately do not copy.)
  */
final case class CodeAct[I, O](
    baseSignature: Signature[I, O],
    interpreter: CodeInterpreter,
    /** Tools the generated Python may call (Python `CodeAct(tools=...)`). They are listed in the codeact
      * instructions (so the LM knows they exist) and should ALSO be wired into the sandbox via
      * [[sandboxTools]] (so the calls actually execute) — same vector, both sides. */
    tools: Vector[dspy4s.programs.contracts.ToolFunction] = Vector.empty,
    maxIterations: Int = 5,
    codeActProgramName: String = "codeact",
    extractorProgramName: String = "codeact_extract",
    /** Optional override for the per-iteration code-generator predict. When `None` (the default), it is built from
      * [[codeActSignature]]. Carrying it as a defaulted, `copy`-reachable field makes the learnable sub-predict
      * addressable + immutably replaceable (see the `Predictors[CodeAct]` instance). */
    codeActPredictOverride: Option[DynamicPredict] = None,
    /** Optional override for the final extractor predict (CoT-augmented). When `None` (the default), it is built
      * fail-fast from [[extractorSignature]] at construction; see [[extractorPredict]]. */
    extractorPredictOverride: Option[DynamicPredict] = None
)(using
    prepend: OutputAugmentation.PrependField.Aux["reasoning", String, O, CodeAct.WithReasoning[O]]
) extends Module[TypedCall[I], Prediction[CodeAct.WithReasoning[O]]]:

  /** The output type — `reasoning: String` prepended to the base outputs `O` (always a named tuple). */
  type Out = CodeAct.WithReasoning[O]

  override val moduleName: String = "code_act"
  require(maxIterations > 0, "maxIterations must be greater than 0")

  private val baseLayout: SignatureLayout = baseSignature.layout

  /** SignatureLayout for the per-iteration code generator. Mirrors Python:
    *   inputs:  baseSignature.inputs ∪ {trajectory}
    *   outputs: {generated_code, finished} */
  val codeActSignature: SignatureLayout =
    baseLayout
      // Replace any user-supplied output fields on the codeact signature with just generated_code + finished.
      // The original outputs are produced by the extractor.
      .appendInput(
        FieldSpec(
          name = "trajectory",
          role = FieldRole.Input,
          typeRef = TypeRef.string,
          description = Some("History of generated code and observations so far.")
        )
      )
      .replaceOutputs(Vector(
        FieldSpec(
          name = "generated_code",
          role = FieldRole.Output,
          typeRef = TypeRef.string,
          description = Some("Python code that, when executed, produces output relevant to answering the question.")
        ),
        FieldSpec(
          name = "finished",
          role = FieldRole.Output,
          typeRef = TypeRef.bool,
          description = Some("Set to true once enough information has been collected to produce the final outputs.")
        )
      ))
      .withInstructions(Some(buildInstructions))

  /** SignatureLayout for the final extractor. Mirrors Python:
    *   inputs:  baseSignature.inputs ∪ {trajectory}
    *   outputs: baseSignature.outputs */
  val extractorSignature: SignatureLayout =
    baseLayout.appendInput(FieldSpec(
      name = "trajectory",
      role = FieldRole.Input,
      typeRef = TypeRef.string,
      description = Some("History of generated code and observations.")
    ))

  /** The per-iteration code-generator predict, built once from [[codeActSignature]] (mirrors Python's
    * `self.code = Predict(...)` in `__init__`). Addressable + tunable via [[codeActPredictOverride]]; `forward`
    * uses this member rather than rebuilding a local each call. */
  val codeActPredict: DynamicPredict =
    codeActPredictOverride.getOrElse(DynamicPredict(layout = codeActSignature, name = Some(codeActProgramName)))

  /** The final extractor predict, built once from the CoT-augmented [[extractorSignature]]. Built fail-fast at
    * construction (mirroring `require`): if the augmentation fails the error surfaces deterministically here, so
    * both sub-predicts are always present and addressable. Tunable via [[extractorPredictOverride]]. */
  val extractorPredict: DynamicPredict =
    extractorPredictOverride.getOrElse(
      DynamicPredict(
        layout = ChainOfThought
          .augmentLayout(extractorSignature)
          .fold(error => throw new IllegalArgumentException(error.message), identity),
        name = Some(extractorProgramName)
      )
    )

  /** System-prompt instructions handed to the codeact DynamicPredict. Mirrors Python's `_build_instructions`
    * shape verbatim, including the numbered tool list (upstream's `Tool.__str__` rendering: name, `<desc>`-wrapped
    * description, argument schema). */
  private def buildInstructions: String =
    val inputs = baseLayout.inputFields.map(f => s"`${f.name}`").mkString(", ")
    val outputs = baseLayout.outputFields.map(f => s"`${f.name}`").mkString(", ")
    val taskPrelude = baseLayout.instructions.fold("")(_ + "\n")
    val library =
      if tools.isEmpty then "You have access to the Python Standard Library."
      else "You have access to the Python Standard Library and the following functions:"
    val toolLines = tools.zipWithIndex.map { case (tool, idx) => s"(${idx + 1}) ${CodeAct.renderTool(tool)}" }
    (Vector(
      s"""${taskPrelude}You are an intelligent agent. For each episode, you will receive the fields $inputs as input.
         |Your goal is to generate executable Python code that collects any necessary information for producing $outputs.
         |For each iteration, you will generate a code snippet that either solves the task or progresses towards the solution.
         |Ensure any output you wish to extract from the code is printed to the console. The code should be enclosed in a fenced code block.
         |When all information for producing the outputs ($outputs) are available to be extracted, mark `finished=true` besides the final Python code.
         |$library""".stripMargin
    ) ++ toolLines).mkString("\n")

  override protected def callInputs(call: TypedCall[I]): DynamicValue.Record =
    baseSignature.inputShape.encode(call.input)
  override protected def callTraceEnabled(call: TypedCall[I]): Boolean = call.traceEnabled
  override protected def tracePayload(prediction: Prediction[Out]): DynamicValue.Record = prediction.raw.values

  override protected def forward(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[Out]] =
    val inputs = baseSignature.inputShape.encode(call.input)
    val baseCall = ProgramCall(
      inputs       = inputs,
      config       = call.config,
      traceEnabled = call.traceEnabled,
      rolloutId    = call.rolloutId
    )
    for
      trajectory <- runIterations(baseCall, codeActPredict, trajectory = Vector.empty, iteration = 0)
      rendered = DynamicValue.Primitive(PrimitiveValue.String(trajectory.render))
      extracted <- extractWithTruncation(baseCall, inputs, trajectory)
      augmented <- OutputAugmentation.decodePrepended(
                     extracted.values, baseSignature.outputShape, "reasoning", "CodeAct extractor", baseSignature.name
                   )
    yield Prediction(
      output = augmented,
      raw = DynamicPrediction(
        values      = extracted.values.updated("trajectory", rendered),
        completions = extracted.completions,
        lmUsage     = extracted.lmUsage
      )
    )

  /** Convenience entry mirroring the typed caller signature; builds a [[TypedCall]] and dispatches through the
    * wrapped [[apply]]. */
  def apply(
      input: I,
      config: DynamicValue.Record = DynamicValue.Record.empty,
      traceEnabled: Boolean = true
  )(using RuntimeContext): Either[DspyError, Prediction[Out]] =
    apply(TypedCall(input, config, traceEnabled))

  /** This program's [[tools]] bridged for a sandboxed interpreter — pass as
    * `new DenoPyodideInterpreter(tools = program.sandboxTools)` so the prompt's tool list and the sandbox's
    * callable surface come from the same vector. See [[CodeAct.sandboxTools]]. */
  def sandboxTools(using RuntimeContext): Vector[dspy4s.core.contracts.SandboxTool] =
    CodeAct.sandboxTools(tools)

  /** Run the final extractor over the trajectory, truncating the OLDEST iteration and retrying (up to 3
    * attempts) when the prompt overflows the model's context window — Python's
    * `_call_with_potential_trajectory_truncation`. Only the extractor's view is truncated; the returned
    * prediction's `trajectory` stays complete. */
  private def extractWithTruncation(
      baseCall: ProgramCall,
      inputs: DynamicValue.Record,
      trajectory: Vector[CodeAct.TrajectoryEntry]
  )(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    truncateOnOverflow(trajectory, maxAttempts = 3)(CodeAct.renderTrajectory) { rendered =>
      extractorPredict.apply(
        baseCall.copy(inputs = inputs.updated("trajectory", DynamicValue.Primitive(PrimitiveValue.String(rendered))))
      )
    }._1

  /** Recursive iteration loop. `maxIterations` bounds depth. */
  private def runIterations(
      call: ProgramCall,
      codeActPredict: DynamicPredict,
      trajectory: Vector[CodeAct.TrajectoryEntry],
      iteration: Int
  )(using RuntimeContext): Either[DspyError, Vector[CodeAct.TrajectoryEntry]] =
    if iteration >= maxIterations then Right(trajectory)
    else
      val stepInputs = call.inputs.updated(
        "trajectory",
        DynamicValue.Primitive(PrimitiveValue.String(CodeAct.renderTrajectory(trajectory)))
      )
      codeActPredict.apply(call.copy(inputs = stepInputs)).flatMap { prediction =>
        val rawCode  = prediction.get("generated_code").map(DynamicValues.renderText).getOrElse("")
        val finished = isFinished(prediction.get("finished"))

        CodeAct.parseCode(rawCode) match
          case Left(parseError) =>
            // Upstream `continue`s on a parse failure: the iteration is consumed, `finished` is IGNORED (an
            // unparseable "final" snippet can't be final), and no code is recorded in the trajectory.
            val entry = CodeAct.TrajectoryEntry(iteration, code = "", observation = s"Failed to parse the generated code: $parseError", isError = true)
            runIterations(call, codeActPredict, trajectory :+ entry, iteration + 1)
          case Right(code) =>
            interpreter.execute(code) match
              case Right(result) if result.exitCode == 0 =>
                val entry = CodeAct.TrajectoryEntry(iteration, code = code, observation = result.stdout.stripTrailing, isError = false)
                if finished then Right(trajectory :+ entry)
                else runIterations(call, codeActPredict, trajectory :+ entry, iteration + 1)
              case Right(result) =>
                val entry = CodeAct.TrajectoryEntry(
                  iteration,
                  code = code,
                  observation = s"Failed to execute the generated code: ${result.stderr.stripTrailing}",
                  isError = true
                )
                if finished then Right(trajectory :+ entry)
                else runIterations(call, codeActPredict, trajectory :+ entry, iteration + 1)
              case Left(err: RuntimeError) =>
                val entry = CodeAct.TrajectoryEntry(
                  iteration,
                  code = code,
                  observation = s"Interpreter failure (${err.component}): ${err.message}",
                  isError = true
                )
                if finished then Right(trajectory :+ entry)
                else runIterations(call, codeActPredict, trajectory :+ entry, iteration + 1)
              case Left(other) => Left(other)
      }

  private def isFinished(value: Option[DynamicValue]): Boolean =
    value match
      case Some(DynamicValue.Primitive(PrimitiveValue.Boolean(b))) => b
      case Some(DynamicValue.Primitive(PrimitiveValue.String(s)))  => s.trim.equalsIgnoreCase("true")
      case _                                                       => false

object CodeAct:
  /** The output type: base outputs `O` with `reasoning: String` prepended (idempotent; always a named tuple). */
  type WithReasoning[O] = OutputAugmentation.WithField[O, "reasoning", String]

  /** Bridge [[dspy4s.programs.contracts.ToolFunction]]s into [[dspy4s.core.contracts.SandboxTool]]s so the LM's
    * generated Python can call them BY NAME from inside a sandboxed interpreter — Python `CodeAct`'s
    * tools-inside-code, enabled by [[dspy4s.core.runtime.DenoPyodideInterpreter]]:
    *
    * {{{
    * val interpreter = new DenoPyodideInterpreter(tools = CodeAct.sandboxTools(myTools))
    * val program     = CodeAct(signature, interpreter)
    * }}}
    *
    * The ambient [[RuntimeContext]] is captured NOW and used for every sandbox-initiated invocation (the bridge
    * call arrives outside any dspy4s call stack). Wire-type `argSchema` entries map to Python type hints where
    * a direct equivalent exists. */
  def sandboxTools(tools: Vector[dspy4s.programs.contracts.ToolFunction])(using
      ctx: dspy4s.core.contracts.RuntimeContext
  ): Vector[dspy4s.core.contracts.SandboxTool] =
    tools.map { tool =>
      dspy4s.core.contracts.SandboxTool(
        name = tool.name,
        parameters = tool.argSchema.map { case (name, typeRef) =>
          dspy4s.core.contracts.SandboxTool.Param(name, pythonTypeOf(typeRef))
        },
        invoke = kwargs => tool.invoke(kwargs)(using ctx)
      )
    }

  private def pythonTypeOf(typeRef: dspy4s.core.contracts.TypeRef): Option[String] = typeRef.repr match
    case "string" => Some("str")
    case "int"    => Some("int")
    case "double" => Some("float")
    case "bool"   => Some("bool")
    case "list"   => Some("list")
    case "json"   => Some("dict")
    case _        => None

  /** Matches a fenced code block, optionally tagged ```python. Captures the
    * snippet body in group 1. Multiline-aware. */
  private val FencedBlock: Regex = """(?s)```(?:python|py)?\s*\n?(.*?)```""".r

  private val LastLineAssignment: Regex = """^(\w+)\s*=""".r

  /** Parse the LM's `generated_code` field — a port of upstream's `_parse_code` (shared by PoT/CodeAct):
    * cut at `---` / triple-newline, strip the code fence, reject empty code and the single-line-multiple-`=`
    * shape ("Code format is not correct."), and when the LAST line is a bare assignment append the assigned
    * variable as a trailing expression (so REPL-style evaluation echoes it). Delta: our fence regex also
    * accepts ```py and UNTAGGED fences (upstream only matches ```python and otherwise leaves the backticks
    * in the code — a wart, not a behavior to reproduce). */
  private[programs] def parseCode(raw: String): Either[String, String] =
    val pre       = raw.split("---", 2)(0).split("\n\n\n", 2)(0).trim
    val codeBlock = FencedBlock.findFirstMatchIn(pre).map(_.group(1).trim).getOrElse(pre)
    if codeBlock.isEmpty then Left("Empty code after parsing.")
    else if !codeBlock.contains("\n") && codeBlock.count(_ == '=') > 1 then Left("Code format is not correct.")
    else
      val lines = codeBlock.split("\n", -1)
      LastLineAssignment.findPrefixMatchOf(lines.last.trim) match
        case Some(m) if lines.length > 1 => Right(codeBlock + "\n" + m.group(1))
        case _                           => Right(codeBlock)

  /** Render one tool for the instruction list — upstream `Tool.__str__`: name, `<desc>`-wrapped description
    * (newlines flattened), and the argument schema. Args render as `{name: wireType, …}` from
    * [[dspy4s.programs.contracts.ToolFunction.argSchema]] (upstream renders its JSON-schema dict). */
  private[programs] def renderTool(tool: dspy4s.programs.contracts.ToolFunction): String =
    val desc =
      if tool.description.nonEmpty then s", whose description is <desc>${tool.description.replace("\n", "  ")}</desc>."
      else "."
    val args = tool.argSchema.map { case (name, typeRef) => s"$name: ${typeRef.repr}" }.mkString("{", ", ", "}")
    s"${tool.name}$desc It takes arguments $args."

  /** One step in the CodeAct trajectory. `code` is what we ran; `observation`
    * is either the captured stdout (success) or an explanation of what
    * failed (parse, execute, or interpreter error). */
  final case class TrajectoryEntry(
      iteration: Int,
      code: String,
      observation: String,
      isError: Boolean
  )

  extension (entries: Vector[TrajectoryEntry])
    def render: String = CodeAct.renderTrajectory(entries)

  private[programs] def renderTrajectory(entries: Vector[TrajectoryEntry]): String =
    if entries.isEmpty then "(empty)"
    else
      entries.iterator.map { entry =>
        val codeBlock =
          if entry.code.isEmpty then "(no code)"
          else s"```python\n${entry.code}\n```"
        val obsLabel = if entry.isError then "observation" else "code_output"
        s"## Iteration ${entry.iteration + 1}\n$codeBlock\n${obsLabel}_${entry.iteration}: ${entry.observation}"
      }.mkString("\n\n")
