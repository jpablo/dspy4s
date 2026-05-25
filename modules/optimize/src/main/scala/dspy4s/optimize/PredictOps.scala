package dspy4s.optimize

import dspy4s.core.contracts.Example
import dspy4s.core.contracts.SignatureSchema
import dspy4s.programs.ChainOfThought
import dspy4s.programs.Predict

trait PredictOps[P]:
  def name(program: P): String
  def signature(program: P): SignatureSchema
  def demos(program: P): Vector[Example]
  def withDemos(program: P, demos: Vector[Example]): P

object PredictOps:
  given predictOps: PredictOps[Predict] with
    def name(program: Predict): String = "predict"
    def signature(program: Predict): SignatureSchema = program.signature
    def demos(program: Predict): Vector[Example] = program.demos
    def withDemos(program: Predict, demos: Vector[Example]): Predict =
      program.copy(demos = demos)

  given chainOfThoughtOps: PredictOps[ChainOfThought] with
    def name(program: ChainOfThought): String = "chain_of_thought"
    def signature(program: ChainOfThought): SignatureSchema =
      program.signature.toOption.getOrElse(program.baseSignature)
    def demos(program: ChainOfThought): Vector[Example] = program.demos
    def withDemos(program: ChainOfThought, demos: Vector[Example]): ChainOfThought =
      program.copy(demos = demos)
