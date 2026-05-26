package dspy4s.optimize

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.programs.contracts.PredictProgram
import dspy4s.programs.contracts.ProgramCall
import zio.blocks.schema.DynamicValue

private def rec(entries: (String, Any)*): DynamicValue.Record =
  DynamicValues.recordFromEntries(entries)

private def lookupString(rec: DynamicValue.Record, key: String): String =
  DynamicValues.recordGet(rec, key).map(DynamicValues.renderText).getOrElse("")

final case class ScriptedPredictProgram(
    answers: Map[String, String],
    layout: SignatureLayout,
    demos: Vector[Example] = Vector.empty,
    failsWith: Option[RuntimeException] = None
) extends PredictProgram:
  override val moduleName: String = "scripted"
  override def run(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    failsWith match
      case Some(err) => throw err
      case None =>
        val q = lookupString(input.inputs, "question")
        Right(DynamicPrediction(rec("answer" -> answers.getOrElse(q, "unknown"))))

final case class DemoAwarePredictProgram(
    layout: SignatureLayout,
    demos: Vector[Example] = Vector.empty,
    answers: Map[String, String] = Map.empty
) extends PredictProgram:
  override val moduleName: String = "demo_aware"
  override def run(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    val q = lookupString(input.inputs, "question")
    // Use answers map first; then demos; else "unknown"
    val answer = answers.get(q)
      .orElse(
        demos
          .find(_.get("question").map(DynamicValues.renderText).contains(q))
          .flatMap(_.get("answer").map(DynamicValues.renderText))
      )
      .getOrElse("unknown")
    Right(DynamicPrediction(rec("answer" -> answer)))

object DemoAwarePredictProgram:
  given demoAwareOps: PredictOps[DemoAwarePredictProgram] with
    def name(program: DemoAwarePredictProgram): String = program.moduleName
    def layout(program: DemoAwarePredictProgram): SignatureLayout = program.layout
    def demos(program: DemoAwarePredictProgram): Vector[Example] = program.demos
    def withDemos(program: DemoAwarePredictProgram, demos: Vector[Example]): DemoAwarePredictProgram =
      program.copy(demos = demos)

object ScriptedPredictProgram:
  given scriptedOps: PredictOps[ScriptedPredictProgram] with
    def name(program: ScriptedPredictProgram): String = program.moduleName
    def layout(program: ScriptedPredictProgram): SignatureLayout = program.layout
    def demos(program: ScriptedPredictProgram): Vector[Example] = program.demos
    def withDemos(program: ScriptedPredictProgram, demos: Vector[Example]): ScriptedPredictProgram =
      program.copy(demos = demos)
