package dspy4s.optimize

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.ExampleData
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.PredictionData
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.programs.contracts.PredictProgram
import dspy4s.programs.contracts.ProgramCall

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
        val q = input.inputs.getOrElse("question", "").toString
        Right(PredictionData(Map("answer" -> answers.getOrElse(q, "unknown"))))

final case class DemoAwarePredictProgram(
    layout: SignatureLayout,
    demos: Vector[Example] = Vector.empty,
    answers: Map[String, String] = Map.empty
) extends PredictProgram:
  override val moduleName: String = "demo_aware"
  override def run(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    val q = input.inputs.getOrElse("question", "").toString
    // Use answers map first; then demos; else "unknown"
    val answer = answers.get(q)
      .orElse(demos.find(_.values.get("question").contains(q)).flatMap(_.values.get("answer")).map(_.toString))
      .getOrElse("unknown")
    Right(PredictionData(Map("answer" -> answer)))

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
