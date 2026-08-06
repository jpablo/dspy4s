package dspy4s.programs.strategies

import dspy4s.core.contracts.{DspyError, DynamicValues, ReplCodeInterpreter, RuntimeContext}
import dspy4s.core.data.RawPrediction
import dspy4s.core.runtime.DenoPyodideInterpreter
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.{ActionInterpreter, ActionOutcome, Prediction, ProgramCall}
import dspy4s.programs.runtime.{AgentLoop, SandboxToolBridge}
import zio.blocks.schema.DynamicValue

/** Executes one [[RLM]] invocation.
  *
  * Configuration and addressable predictor leaves remain on `RLM`; this class owns the per-call interpreter lifecycle,
  * persistent REPL history, loop transitions, validated SUBMIT handling, and exhausted-budget extraction fallback.
  */
private[programs] final class RLMExecution[I, O](rlm: RLM[I, O]):
  private val baseSignature    = rlm.baseSignature
  private val baseLayout       = baseSignature.layout
  private val outputFieldNames = baseLayout.outputFields.map(_.name)

  def run(call: ProgramCall[I])(using ctx: RuntimeContext): Either[DspyError, Prediction[O]] =
    val inputs                               = call.encodedInput(baseSignature.inputShape)
    val inputVars: Map[String, DynamicValue] = baseLayout.inputFields.map { field =>
      field.name -> DynamicValues.recordGet(inputs, field.name).getOrElse(DynamicValue.Null)
    }.toMap
    // The variable metadata is fixed for the whole forward, so its prompt rendering is computed once here rather
    // than per iteration.
    val variablesInfo = baseLayout.inputFields.map { field =>
      RLM.ReplVariable.fromValue(field.name, inputVars(field.name), Some(field)).format
    }.mkString("\n\n")

    val sandboxTools = RLM.makeLlmTools(rlm.maxLlmCalls, rlm.subLm, ctx) ++
      SandboxToolBridge.fromToolFunctions(rlm.tools)
    val outputFields = baseLayout.outputFields.map { field =>
      DenoPyodideInterpreter.OutputField(field.name, field.typeRef.pythonTypeName)
    }
    val interpreter       = rlm.interpreterFactory(sandboxTools, outputFields)
    val actionInterpreter = replActionInterpreter(interpreter, inputVars)
    try iterate(call, actionInterpreter, variablesInfo)
    finally interpreter.close()

  private def iterate(
      call             : ProgramCall[I],
      actionInterpreter: ActionInterpreter[RLM.ReplAction, RLM.ReplExecution],
      variablesInfo    : String
  )(using RuntimeContext): Either[DspyError, Prediction[O]] =
    AgentLoop.run[Vector[RLM.ReplEntry], Prediction[O]](Vector.empty, 0, rlm.maxIterations)(
      onExhausted = history => extractFallback(call, variablesInfo, history)
    )(rlmStep(call, actionInterpreter, variablesInfo))

  private def rlmStep(
      call             : ProgramCall[I],
      actionInterpreter: ActionInterpreter[RLM.ReplAction, RLM.ReplExecution],
      variablesInfo    : String
  )(using
      RuntimeContext
  ): (Vector[RLM.ReplEntry], Int) => Either[DspyError, AgentLoop.Step[Vector[RLM.ReplEntry], Prediction[O]]] =
    (history, iteration) =>
      val actionInputs = RLM.ActionInputs(
        variables_info = variablesInfo,
        repl_history = RLM.renderHistory(history, rlm.maxOutputChars),
        iteration = s"${iteration + 1}/${rlm.maxIterations}"
      )
      rlm.actionPredict(call.mapInput(_ => actionInputs)).flatMap { action =>
        val reasoning = action.output.reasoning
        val rawCode   = action.output.code
        if rlm.verbose then
          Console.err.println(
            s"RLM iteration ${iteration + 1}/${rlm.maxIterations}\nReasoning: $reasoning\nCode:\n$rawCode"
          )
        val execution: Either[DspyError, ActionOutcome[RLM.ReplExecution]] = RLM.stripCodeFences(rawCode) match
          case Left(fenceError) =>
            val entry = RLM.ReplEntry(reasoning, rawCode, s"[Error] $fenceError")
            Right(ActionOutcome.Failed(RLM.ReplExecution.Observed(entry)))
          case Right(code) => actionInterpreter.execute(RLM.ReplAction(reasoning, code))
        execution.flatMap { outcome =>
          outcome.observation match
            case RLM.ReplExecution.Observed(entry) =>
              if rlm.verbose then Console.err.println(RLM.formatOutputBlock(entry.output, rlm.maxOutputChars))
              Right(AgentLoop.Step.Continue(history :+ entry))
            case RLM.ReplExecution.Submitted(entry, outputsRecord) =>
              // The single schema decode of the SUBMIT payload: a failed decode becomes a `[Type Error]`
              // observation and the loop continues (upstream parity); a successful one terminates the loop.
              baseSignature.outputShape.decode(outputsRecord) match
                case Left(decodeError) =>
                  val failed = entry.copy(output = s"[Type Error] ${decodeError.message}")
                  Right(AgentLoop.Step.Continue(history :+ failed))
                case Right(output) =>
                  Right(AgentLoop.Step.Done(finishWith(output, outputsRecord, reasoning, history :+ entry)))
        }
      }

  private def replActionInterpreter(
      interpreter: ReplCodeInterpreter,
      inputVars  : Map[String, DynamicValue]
  ): ActionInterpreter[RLM.ReplAction, RLM.ReplExecution] =
    new ActionInterpreter[RLM.ReplAction, RLM.ReplExecution]:
      override def execute(action: RLM.ReplAction)(using
          RuntimeContext
      ): Either[DspyError, ActionOutcome[RLM.ReplExecution]] =
        interpreter.execute(action.code, inputVars) match
          case Left(error) =>
            val entry = RLM.ReplEntry(action.reasoning, action.code, s"[Error] ${error.message}")
            Right(ActionOutcome.Failed(RLM.ReplExecution.Observed(entry)))
          case Right(result) => result.finalOutput match
              case Some(finalJson) => RLM.parseSubmitted(finalJson, outputFieldNames) match
                  case Left(problem) =>
                    val entry = RLM.ReplEntry(action.reasoning, action.code, problem)
                    Right(ActionOutcome.Failed(RLM.ReplExecution.Observed(entry)))
                  case Right(record) =>
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
      output        : O,
      outputsRecord : DynamicValue.Record,
      finalReasoning: String,
      history       : Vector[RLM.ReplEntry]
  ): Prediction[O] =
    Prediction(
      output = output,
      raw = RawPrediction(values = outputsRecord)
        .withRawValue("trajectory", RLM.renderHistory(history, rlm.maxOutputChars))
        .withRawValue("final_reasoning", finalReasoning)
    )

  private def extractFallback(
      call         : ProgramCall[I],
      variablesInfo: String,
      history      : Vector[RLM.ReplEntry]
  )(using RuntimeContext): Either[DspyError, Prediction[O]] =
    RuntimeEnvironment.warn("RLM", "reached max iterations, using extract to get final output")
    val renderedHistory = RLM.renderHistory(history, rlm.maxOutputChars)
    val extractInputs   = RLM.ExtractInputs(
      variables_info = variablesInfo,
      repl_history = renderedHistory
    )
    rlm.extractPredict(call.mapInput(_ => extractInputs)).map { extracted =>
      Prediction(
        output = extracted.output,
        // withRawValue on the extractor's raw envelope preserves its completions / LM usage (same rationale as
        // TrajectoryAgent.runAndExtractPrediction).
        raw = extracted.raw
          .withRawValue("trajectory", renderedHistory)
          .withRawValue("final_reasoning", "Extract forced final output")
      )
    }
