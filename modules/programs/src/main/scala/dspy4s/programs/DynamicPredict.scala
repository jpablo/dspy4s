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
    runtime: ProgramRuntime = new SettingsProgramRuntime {}
) extends BasePredictProgram(moduleName = name.getOrElse("predict")):

  private val engine = PredictEngine(layout, demos, moduleName, runtime)

  override protected def execute(call: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    engine.execute(call)
