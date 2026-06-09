package dspy4s.optimize

import dspy4s.programs.Predictor

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.programs.DynamicPredict
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
    failsWith: Option[RuntimeException] = None
) extends DynamicModule:
  override val moduleName: String = "scripted"
  override protected def forward(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    failsWith match
      case Some(err) => throw err
      case None =>
        val q = lookupString(input.inputs, "question")
        Right(DynamicPrediction(rec("answer" := answers.getOrElse(q, "unknown"))))

final case class DemoAwarePredictProgram(
    layout: SignatureLayout,
    demos: Vector[Example] = Vector.empty,
    answers: Map[String, String] = Map.empty
) extends DynamicModule:
  override val moduleName: String = "demo_aware"
  override protected def forward(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    val q = lookupString(input.inputs, "question")
    // Use answers map first; then demos; else "unknown"
    val answer = answers.get(q)
      .orElse(
        demos
          .find(_.get("question").map(DynamicValues.renderText).contains(q))
          .flatMap(_.get("answer").map(DynamicValues.renderText))
      )
      .getOrElse("unknown")
    Right(DynamicPrediction(rec("answer" := answer)))

object DemoAwarePredictProgram:
  given demoAwarePredictor: Predictor[DemoAwarePredictProgram] with
    def get(program: DemoAwarePredictProgram): DynamicPredict =
      DynamicPredict(layout = program.layout, demos = program.demos, name = Some(program.moduleName))
    def set(program: DemoAwarePredictProgram, updated: DynamicPredict): DemoAwarePredictProgram =
      program.copy(demos = updated.demos)

object ScriptedPredictProgram:
  given scriptedPredictor: Predictor[ScriptedPredictProgram] with
    def get(program: ScriptedPredictProgram): DynamicPredict =
      DynamicPredict(layout = program.layout, demos = program.demos, name = Some(program.moduleName))
    def set(program: ScriptedPredictProgram, updated: DynamicPredict): ScriptedPredictProgram =
      program.copy(demos = updated.demos)
