package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ProgramRuntime
import dspy4s.programs.runtime.BasePredictProgram
import dspy4s.programs.runtime.PredictEngine
import dspy4s.programs.runtime.SettingsProgramRuntime

final case class DynamicPredict(
    layout: SignatureLayout,
    demos: Vector[Example] = Vector.empty,
    name: Option[String] = None,
    runtime: ProgramRuntime = new SettingsProgramRuntime {},
    /** Optional pre-rendered JSON Schema string for the output, threaded into [[AdapterInvocation]]. The typed
      * `Predict[I, O]` path provides this from its `signature.outputShape.jsonSchemaString`; users who
      * construct `DynamicPredict` directly leave it `None` and adapters fall back to their default behavior. */
    outputJsonSchema: Option[String] = None
) extends BasePredictProgram(moduleName = name.getOrElse("predict")):

  private val engine = PredictEngine(layout, demos, moduleName, runtime, outputJsonSchema)

  override protected def execute(call: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    engine.execute(call)
