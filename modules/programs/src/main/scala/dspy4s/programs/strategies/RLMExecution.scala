package dspy4s.programs.strategies

import dspy4s.core.contracts.{DspyError, DynamicValues, ReplCodeInterpreter, RuntimeContext, updated}
import dspy4s.core.data.RawPrediction
import dspy4s.core.runtime.DenoPyodideInterpreter
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
    val variablesMeta = baseLayout.inputFields.map { field =>
      RLM.ReplVariable.fromValue(field.name, inputVars(field.name), Some(field))
    }

    val sandboxTools = RLM.makeLlmTools(rlm.maxLlmCalls, rlm.subLm, ctx) ++
      SandboxToolBridge.fromToolFunctions(rlm.tools)
    val outputFields = baseLayout.outputFields.map { field =>
      DenoPyodideInterpreter.OutputField(field.name, field.typeRef.pythonTypeName)
    }
    val interpreter       = rlm.interpreterFactory(sandboxTools, outputFields)
    val actionInterpreter = replActionInterpreter(interpreter, inputVars)
    try iterate(call, actionInterpreter, variablesMeta)
    finally interpreter.close()

  private def iterate(
      call             : ProgramCall[I],
      actionInterpreter: ActionInterpreter[RLM.ReplAction, RLM.ReplExecution],
      variablesMeta    : Vector[RLM.ReplVariable]
  )(using RuntimeContext): Either[DspyError, Prediction[O]] =
    AgentLoop.run[Vector[RLM.ReplEntry], Prediction[O]](Vector.empty, 0, rlm.maxIterations)(
      onExhausted = history => extractFallback(call, variablesMeta, history)
    )(rlmStep(call, actionInterpreter, variablesMeta))

  private def rlmStep(
      call             : ProgramCall[I],
      actionInterpreter: ActionInterpreter[RLM.ReplAction, RLM.ReplExecution],
      variablesMeta    : Vector[RLM.ReplVariable]
  )(using
      RuntimeContext
  ): (Vector[RLM.ReplEntry], Int) => Either[DspyError, AgentLoop.Step[Vector[RLM.ReplEntry], Prediction[O]]] =
    (history, iteration) =>
      val actionInputs = RLM.ActionInputs(
        variables_info = variablesMeta.map(_.format).mkString("\n\n"),
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
              finishWith(outputsRecord, reasoning, history :+ entry).map(AgentLoop.Step.Done(_))
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
                  case Right(record) => baseSignature.outputShape.decode(record) match
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
      outputsRecord : DynamicValue.Record,
      finalReasoning: String,
      history       : Vector[RLM.ReplEntry]
  ): Either[DspyError, Prediction[O]] =
    baseSignature.outputShape.decode(outputsRecord).map { output =>
      Prediction(
        output = output,
        raw = RawPrediction(values =
          outputsRecord
            .updated("trajectory", DynamicValues.fromAny(RLM.renderHistory(history, rlm.maxOutputChars)))
            .updated("final_reasoning", DynamicValues.fromAny(finalReasoning))
        )
      )
    }

  private def extractFallback(
      call         : ProgramCall[I],
      variablesMeta: Vector[RLM.ReplVariable],
      history      : Vector[RLM.ReplEntry]
  )(using RuntimeContext): Either[DspyError, Prediction[O]] =
    Console.err.println("WARN [dspy4s] RLM reached max iterations, using extract to get final output")
    val extractInputs = RLM.ExtractInputs(
      variables_info = variablesMeta.map(_.format).mkString("\n\n"),
      repl_history = RLM.renderHistory(history, rlm.maxOutputChars)
    )
    rlm.extractPredict(call.mapInput(_ => extractInputs)).map { extracted =>
      Prediction(
        output = extracted.output,
        raw = RawPrediction(values =
          extracted.raw.values
            .updated("trajectory", DynamicValues.fromAny(RLM.renderHistory(history, rlm.maxOutputChars)))
            .updated("final_reasoning", DynamicValues.fromAny("Extract forced final output"))
        )
      )
    }
