package dspy4s.optimize

import dspy4s.programs.optimization.{OptimizableLeaf, OptimizableMetadata, OptimizableParameters}

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.programs.contracts.DynamicModule
import dspy4s.programs.contracts.ProgramCall
import zio.blocks.schema.DynamicValue

private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
  DynamicValues.recordFromEntries(entries)

private def lookupString(rec: DynamicValue.Record, key: String): String =
  DynamicValues.recordGet(rec, key).map(DynamicValues.renderText).getOrElse("")

final case class ScriptedPredictProgram(
    answers: Map[String, String],
    layout: SignatureLayout,
    demos: Vector[Example] = Vector.empty,
    failsWith: Option[RuntimeException] = None,
    config: DynamicValue.Record = DynamicValue.Record.empty
) extends DynamicModule:
  override val moduleName: String = "scripted"
  override protected def forwardDynamic(input: ProgramCall[DynamicValue.Record])(using
      RuntimeContext
  ): Either[DspyError, RawPrediction] =
    failsWith match
      case Some(err) => throw err
      case None =>
        val q = lookupString(input.input, "question")
        Right(RawPrediction(rec("answer" := answers.getOrElse(q, "unknown"))))

final case class DemoAwarePredictProgram(
    layout: SignatureLayout,
    demos: Vector[Example] = Vector.empty,
    answers: Map[String, String] = Map.empty,
    config: DynamicValue.Record = DynamicValue.Record.empty
) extends DynamicModule:
  override val moduleName: String = "demo_aware"
  override protected def forwardDynamic(input: ProgramCall[DynamicValue.Record])(using
      RuntimeContext
  ): Either[DspyError, RawPrediction] =
    val q = lookupString(input.input, "question")
    // Use answers map first; then demos; else "unknown"
    val answer = answers.get(q)
      .orElse(
        demos
          .find(_.get("question").map(DynamicValues.renderText).contains(q))
          .flatMap(_.get("answer").map(DynamicValues.renderText))
      )
      .getOrElse("unknown")
    Right(RawPrediction(rec("answer" := answer)))

object DemoAwarePredictProgram:
  given demoAwareOptimizable: OptimizableLeaf[DemoAwarePredictProgram] with
    def get(program: DemoAwarePredictProgram): OptimizableParameters =
      OptimizableParameters(program.layout.instructions, program.demos, program.config)
    def metadata(program: DemoAwarePredictProgram): OptimizableMetadata =
      OptimizableMetadata.from(program.layout, program.moduleName)
    def set(program: DemoAwarePredictProgram, updated: OptimizableParameters): DemoAwarePredictProgram =
      if updated == get(program) then program
      else
        program.copy(
        layout = program.layout.withInstructions(updated.instructions),
        demos = updated.demos,
        config = updated.config
      )

object ScriptedPredictProgram:
  given scriptedOptimizable: OptimizableLeaf[ScriptedPredictProgram] with
    def get(program: ScriptedPredictProgram): OptimizableParameters =
      OptimizableParameters(program.layout.instructions, program.demos, program.config)
    def metadata(program: ScriptedPredictProgram): OptimizableMetadata =
      OptimizableMetadata.from(program.layout, program.moduleName)
    def set(program: ScriptedPredictProgram, updated: OptimizableParameters): ScriptedPredictProgram =
      if updated == get(program) then program
      else
        program.copy(
        layout = program.layout.withInstructions(updated.instructions),
        demos = updated.demos,
        config = updated.config
      )
