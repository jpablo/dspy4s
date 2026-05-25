package dspy4s.optimize

import dspy4s.core.contracts.Example
import dspy4s.core.contracts.SignatureLayout
import dspy4s.programs.DynamicPredict

trait PredictOps[P]:
  def name(program: P): String
  def layout(program: P): SignatureLayout
  def demos(program: P): Vector[Example]
  def withDemos(program: P, demos: Vector[Example]): P

object PredictOps:
  given predictOps: PredictOps[DynamicPredict] with
    def name(program: DynamicPredict): String = "predict"
    def layout(program: DynamicPredict): SignatureLayout = program.layout
    def demos(program: DynamicPredict): Vector[Example] = program.demos
    def withDemos(program: DynamicPredict, demos: Vector[Example]): DynamicPredict =
      program.copy(demos = demos)
