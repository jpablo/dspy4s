package dspy4s.programs

import dspy4s.core.contracts.CodeInterpreter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.Prediction
import dspy4s.core.contracts.PredictionData
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.Signature
import dspy4s.core.contracts.TypeRef
import dspy4s.programs.contracts.PredictProgram
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.runtime.BasePredictProgram

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
  * After the loop, a [[ChainOfThought]] "extractor" reads the full
  * trajectory and produces the user-visible outputs declared in
  * `baseSignature`.
  *
  * '''Scope of this scaffolding.''' The wiring + prompt are present and
  * exercisable end-to-end against a real LM. What is **not** in this v1:
  *
  *   - **Tools-inside-code.** Python `CodeAct` lets the user pass Scala
  *     functions that the LM's generated Python can call. That requires a
  *     Scala↔Python RPC bridge — deferred until the Deno+Pyodide interpreter
  *     lands. Users wanting tool calls can either preload them into their
  *     interpreter environment, or wait for the v2 sandboxed interpreter.
  *   - **Persistent REPL state.** The default
  *     [[dspy4s.core.runtime.SubprocessPythonInterpreter]] is stateless —
  *     each iteration runs in a fresh Python subprocess. CodeAct compensates
  *     by carrying the accumulated code in the trajectory (the LM sees what
  *     it ran previously). A stateful interpreter impl would skip the
  *     accumulation; CodeAct doesn't need to know.
  *
  * '''Closing the interpreter.''' CodeAct does **not** call
  * `interpreter.close()` itself — the same interpreter instance can be
  * reused across multiple `run(...)` invocations. The caller owns lifecycle.
  */
final case class CodeAct(
    baseSignature: Signature,
    interpreter: CodeInterpreter,
    maxIterations: Int = 5,
    codeActProgramName: String = "codeact",
    extractorProgramName: String = "codeact_extract"
) extends BasePredictProgram(moduleName = "code_act"):
  require(maxIterations > 0, "maxIterations must be greater than 0")

  /** Signature for the per-iteration code generator. Mirrors Python:
    *   inputs:  baseSignature.inputs ∪ {trajectory}
    *   outputs: {generated_code, finished} */
  val codeActSignature: Signature =
    baseSignature
      .append(FieldSpec(
        name = "trajectory",
        role = FieldRole.Input,
        typeRef = TypeRef.string,
        description = Some("History of generated code and observations so far.")
      ))
      .append(FieldSpec(
        name = "generated_code",
        role = FieldRole.Output,
        typeRef = TypeRef.string,
        description = Some("Python code that, when executed, produces output relevant to answering the question.")
      ))
      .append(FieldSpec(
        name = "finished",
        role = FieldRole.Output,
        typeRef = TypeRef.bool,
        description = Some("Set to true once enough information has been collected to produce the final outputs.")
      ))
      // Replace any user-supplied output fields on the codeact signature
      // with just generated_code + finished. The original outputs are
      // produced by the extractor.
      .withFields(
        baseSignature.inputFields ++
          Vector(
            FieldSpec(
              name = "trajectory",
              role = FieldRole.Input,
              typeRef = TypeRef.string,
              description = Some("History of generated code and observations so far.")
            ),
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
          )
      )
      .withInstructions(Some(buildInstructions))

  /** Signature for the final extractor. Mirrors Python:
    *   inputs:  baseSignature.inputs ∪ {trajectory}
    *   outputs: baseSignature.outputs */
  val extractorSignature: Signature =
    baseSignature.append(FieldSpec(
      name = "trajectory",
      role = FieldRole.Input,
      typeRef = TypeRef.string,
      description = Some("History of generated code and observations.")
    ))

  /** System-prompt instructions handed to the codeact Predict. Mirrors
    * Python's `_build_instructions` shape verbatim. */
  private def buildInstructions: String =
    val inputs = baseSignature.inputFields.map(f => s"`${f.name}`").mkString(", ")
    val outputs = baseSignature.outputFields.map(f => s"`${f.name}`").mkString(", ")
    val taskPrelude = baseSignature.instructions.fold("")(_ + "\n")
    s"""${taskPrelude}You are an intelligent agent. For each episode, you will receive the fields $inputs as input.
       |Your goal is to generate executable Python code that collects any necessary information for producing $outputs.
       |For each iteration, you will generate a code snippet that either solves the task or progresses towards the solution.
       |Ensure any output you wish to extract from the code is printed to the console. The code should be enclosed in a fenced code block.
       |When all information for producing the outputs ($outputs) are available to be extracted, mark `finished=true` besides the final Python code.
       |You have access to the Python Standard Library.""".stripMargin

  override protected def execute(call: ProgramCall)(using RuntimeContext): Either[DspyError, Prediction] =
    val codeActPredict = Predict(signature = codeActSignature, name = Some(codeActProgramName))
    val extractor = ChainOfThought(baseSignature = extractorSignature)

    runIterations(call, codeActPredict, trajectory = Vector.empty, iteration = 0).flatMap { trajectory =>
      val extractInputs = call.inputs.updated("trajectory", trajectory.render)
      extractor.run(call.copy(inputs = extractInputs)).map { extracted =>
        // Attach the trajectory to the extracted prediction's values so
        // callers can inspect it after the fact.
        PredictionData(
          values = extracted.values.updated("trajectory", trajectory.render),
          completions = extracted.completions,
          lmUsage = extracted.lmUsage
        )
      }
    }

  /** Recursive iteration loop. Tail-call friendly via @annotation.tailrec
    * would require Either-flattening — leaving as while-loop-equivalent
    * recursion since maxIterations bounds depth. */
  private def runIterations(
      call: ProgramCall,
      codeActPredict: Predict,
      trajectory: Vector[CodeAct.TrajectoryEntry],
      iteration: Int
  )(using RuntimeContext): Either[DspyError, Vector[CodeAct.TrajectoryEntry]] =
    if iteration >= maxIterations then Right(trajectory)
    else
      val stepInputs = call.inputs.updated("trajectory", CodeAct.renderTrajectory(trajectory))
      codeActPredict.run(call.copy(inputs = stepInputs)).flatMap { prediction =>
        val rawCode = prediction.values.getOrElse("generated_code", "").toString
        val finished = isFinished(prediction.values.get("finished"))
        val code = extractCode(rawCode).getOrElse(rawCode.trim)

        if code.isEmpty then
          val entry = CodeAct.TrajectoryEntry(iteration, code = "", observation = "Failed to parse the generated code: empty code", isError = true)
          if finished then Right(trajectory :+ entry)
          else runIterations(call, codeActPredict, trajectory :+ entry, iteration + 1)
        else
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

  private def isFinished(value: Option[Any]): Boolean =
    value match
      case Some(b: Boolean)            => b
      case Some(s: String)             => s.trim.equalsIgnoreCase("true")
      case _                           => false

  /** Strip a fenced ```python / ``` block from the LM's `generated_code`
    * field. The LM is instructed to emit fenced code, but it sometimes
    * emits the raw snippet — both shapes are accepted. */
  private def extractCode(raw: String): Option[String] =
    val trimmed = raw.trim
    CodeAct.FencedBlock.findFirstMatchIn(trimmed) match
      case Some(m) => Some(m.group(1).trim)
      case None    =>
        if trimmed.nonEmpty then Some(trimmed) else None

object CodeAct:
  /** Matches a fenced code block, optionally tagged ```python. Captures the
    * snippet body in group 1. Multiline-aware. */
  private val FencedBlock: Regex = """(?s)```(?:python|py)?\s*\n?(.*?)```""".r

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
