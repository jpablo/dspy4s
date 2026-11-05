package dspy4s.programs.strategies

import dspy4s.core.contracts.{DspyError, DynamicValues, ReplCodeInterpreter, RuntimeContext, SandboxOutputField}
import dspy4s.core.data.RawPrediction
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.{Prediction, ProgramCall}
import dspy4s.programs.runtime.AgentLoop
import dspy4s.programs.strategies.RLMModel.{ActionInputs, ActionStep, ExtractInputs, ReplEntry, ReplVariable}
import zio.blocks.schema.DynamicValue

/** Executes one [[RLM]] invocation.
  *
  * Configuration and addressable predictor leaves remain on `RLM`; this class owns the per-call interpreter lifecycle,
  * persistent REPL history, loop transitions, validated SUBMIT handling, and exhausted-budget extraction fallback.
  *
  * The LM usage of the result is the total of the usage scope of the RLM call, which `Module.apply` opens: the action
  * calls, the sub-LM calls of `llm_query` and `llm_query_batched`, the model calls of the tools, and the extract call
  * (upstream `Module.__call__` puts the total usage of the call on the result, `dspy/primitives/module.py`). An
  * instance serves one call.
  */
private[programs] final class RLMExecution[I, O](rlm: RLM[I, O]):
  private type LoopStep = AgentLoop.Step[Vector[ReplEntry], Prediction[O]]

  private val baseSignature    = rlm.baseSignature
  private val baseLayout       = baseSignature.layout
  private val outputFieldNames = baseLayout.outputFields.map(_.name)

  def run(call: ProgramCall[I])(using ctx: RuntimeContext): Either[DspyError, Prediction[O]] =
    val inputs                               = call.encodedInput(baseSignature.inputShape)
    val inputVars: Map[String, DynamicValue] = baseLayout.inputFields.map { field =>
      field.name -> DynamicValues.recordGet(inputs, field.name).getOrElse(DynamicValue.Null)
    }.toMap
    // The variable metadata is fixed for the whole forward, so its prompt rendering is computed once here rather
    // than per iteration. The prompt shows the list as a numbered list (upstream `variables_info`).
    val variablesInfo = baseLayout.inputFields.map { field =>
      ReplVariable.fromValue(field.name, inputVars(field.name), Some(field)).format
    }

    val sandboxTools = RLMSandboxTools.build(rlm.maxLlmCalls, rlm.subLm, ctx) ++
      RLMSandboxTools.fromToolFunctions(rlm.tools)
    val outputFields = baseLayout.outputFields.map { field =>
      SandboxOutputField(field.name, field.typeRef.pythonTypeName)
    }
    val factory = RLM.resolveInterpreterFactory(rlm.interpreterFactory)
    // The action predict of this call has the execution text of the factory of this call.
    val actionPredict = rlm.actionPredictFor(factory)
    val interpreter   = factory(sandboxTools, outputFields)
    try AgentLoop.run[Vector[ReplEntry], Prediction[O]](Vector.empty, rlm.maxIterations)(
        onExhausted = history => extractFallback(call, variablesInfo, history)
      )(step(call, actionPredict, interpreter, inputVars, variablesInfo))
    finally interpreter.close()

  /** One iteration of the loop: run the action predict, strip the code fences, run the code, and classify the result.
    *
    * `inputVars` (the same map instance) rides on EVERY `execute` -- upstream parity: the interpreter re-assigns the
    * variables at the top of each code block, so a model-side mutation of an input variable deliberately does not
    * survive to the next step. The interpreter caches the serialized assignment prelude per map instance, so the
    * repeated injection costs no host-side re-encoding.
    *
    * A rejected fence and a user-code error (a `CodeResult` with a non-zero exit code) become an `[Error]` observation
    * and the loop continues. A `Left` from the REPL means that the interpreter itself failed (process, timeout,
    * protocol). Generated code cannot repair that failure, so the `Left` ends the run and the LM gets no more calls.
    * Upstream `_execute_code` catches only `CodeExecutionError` and `SyntaxError`. `run` still closes the interpreter.
    *
    * A SUBMIT that is not complete becomes an `[Error]` observation. The result of a complete SUBMIT holds the declared
    * outputs only. The check of the allowed values (decision D7) and the single schema decode of the SUBMIT payload
    * then give a `[Type Error]` observation on a failure, and the loop continues (upstream `_process_final_output`). A
    * success ends the loop.
    */
  private def step(
      call         : ProgramCall[I],
      actionPredict: Predict[ActionInputs, ActionStep],
      interpreter  : ReplCodeInterpreter,
      inputVars    : Map[String, DynamicValue],
      variablesInfo: Vector[String]
  )(history: Vector[ReplEntry], iteration: Int)(using RuntimeContext): Either[DspyError, LoopStep] =
    val actionInputs = ActionInputs(
      variables_info = variablesInfo,
      repl_history = RLMReplProtocol.renderHistory(history, rlm.maxOutputChars),
      iteration = s"${iteration + 1}/${rlm.maxIterations}"
    )
    actionPredict(call.mapInput(_ => actionInputs)).flatMap { action =>
      val reasoning = action.output.reasoning
      val rawCode   = action.output.code
      if rlm.verbose then
        Console.err.println(
          s"RLM iteration ${iteration + 1}/${rlm.maxIterations}\nReasoning: $reasoning\nCode:\n$rawCode"
        )

      def observed(code: String, output: String): LoopStep =
        if rlm.verbose then Console.err.println(RLMReplProtocol.formatOutputBlock(output, rlm.maxOutputChars))
        AgentLoop.Step.Continue(history :+ ReplEntry(reasoning, code, output))

      RLMReplProtocol.stripCodeFences(rawCode) match
        case Left(fenceError) => Right(observed(rawCode, s"[Error] $fenceError"))
        case Right(code)      =>
          // The `[Type Error]` path writes no verbose output.
          def typeError(output: String): LoopStep =
            AgentLoop.Step.Continue(history :+ ReplEntry(reasoning, code, output))
          interpreter.execute(code, inputVars).map { result =>
            result.finalOutput match
              case None if result.exitCode == 0 =>
                observed(code, RLMReplProtocol.formatOutput(result.stdout.stripTrailing))
              case None            => observed(code, s"[Error] ${result.stderr.stripTrailing}")
              case Some(finalJson) =>
                val submission: Either[LoopStep, LoopStep] =
                  for
                    submitted <- RLMReplProtocol.parseSubmitted(finalJson, outputFieldNames).left.map(observed(code, _))
                    outputs   <- RLMReplProtocol.checkAllowedValues(submitted, baseLayout).left.map(typeError)
                    output    <- baseSignature.outputShape.decode(outputs).left.map(error =>
                                typeError(s"[Type Error] ${error.message}")
                              )
                  yield
                    val entry = ReplEntry(reasoning, code, s"FINAL: $finalJson")
                    AgentLoop.Step.Done(finishWith(output, outputs, reasoning, history :+ entry))
                submission.merge
          }
    }

  private def finishWith(
      output        : O,
      outputsRecord : DynamicValue.Record,
      finalReasoning: String,
      history       : Vector[ReplEntry]
  ): Prediction[O] =
    Prediction(
      output = output,
      raw = RawPrediction(values = outputsRecord)
        .withRawValue("trajectory", RLMReplProtocol.renderHistory(history, rlm.maxOutputChars))
        .withRawValue("final_reasoning", finalReasoning)
    )

  private def extractFallback(
      call         : ProgramCall[I],
      variablesInfo: Vector[String],
      history      : Vector[ReplEntry]
  )(using RuntimeContext): Either[DspyError, Prediction[O]] =
    RuntimeEnvironment.warn("RLM", "reached max iterations, using extract to get final output")
    val renderedHistory = RLMReplProtocol.renderHistory(history, rlm.maxOutputChars)
    val extractInputs   = ExtractInputs(
      variables_info = variablesInfo,
      repl_history = renderedHistory
    )
    rlm.extractPredict(call.mapInput(_ => extractInputs)).map { extracted =>
      Prediction(
        output = extracted.output,
        // withRawValue on the extractor's raw envelope preserves its completions (same rationale as ReAct.forward).
        // `Module.apply` replaces the usage with the total of the call.
        raw = extracted.raw
          .withRawValue("trajectory", renderedHistory)
          .withRawValue("final_reasoning", "Extract forced final output")
      )
    }
