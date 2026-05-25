package dspy4s.optimize

import dspy4s.core.contracts.Example
import dspy4s.core.contracts.SignatureLayout
import dspy4s.programs.DynamicChainOfThought
import dspy4s.programs.DynamicPredict

trait PredictOps[P]:
  def name(program: P): String
  def signature(program: P): SignatureLayout
  def demos(program: P): Vector[Example]
  def withDemos(program: P, demos: Vector[Example]): P

object PredictOps:
  given predictOps: PredictOps[DynamicPredict] with
    def name(program: DynamicPredict): String = "predict"
    def signature(program: DynamicPredict): SignatureLayout = program.signature
    def demos(program: DynamicPredict): Vector[Example] = program.demos
    def withDemos(program: DynamicPredict, demos: Vector[Example]): DynamicPredict =
      program.copy(demos = demos)

  given chainOfThoughtOps: PredictOps[DynamicChainOfThought] with
    def name(program: DynamicChainOfThought): String = "chain_of_thought"
    def signature(program: DynamicChainOfThought): SignatureLayout =
      program.signature.toOption.getOrElse(program.baseSignature)
    def demos(program: DynamicChainOfThought): Vector[Example] = program.demos
    def withDemos(program: DynamicChainOfThought, demos: Vector[Example]): DynamicChainOfThought =
      program.copy(demos = demos)
