package dspy4s.harness

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.core.data.RawPrediction
import dspy4s.programs.ProgramRunner
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.optimization.OptimizableStructure
import dspy4s.programs.strategies.Predict
import zio.blocks.schema.DynamicValue

/** What the optimizers of dspy4s need from a program (CODE_HARNESS_DESIGN.md, 9): each one takes
  * `P: {OptimizableStructure, ProgramRunner}`. The companion of `Harness` extends this trait, so the givens are in the
  * implicit scope of each `Harness`.
  */
trait HarnessGivens:

  /** The leaves `policy` and `finisher`, in this order and with distinct names, so an optimizer tells their traces
    * apart. A replace changes only these leaves. The strategy of phase 4 has no leaves.
    */
  def structure[I, O]: OptimizableStructure[Harness[I, O]] =
    OptimizableStructure.pair[Harness[I, O], Predict[PolicyInput, PolicyStep], Predict[FinishInput, O], 1, 1](
      "Harness",
      Some("policy"),
      Some("finisher"),
      _.policy,
      _.finisher,
      (harness, policy, finisher) =>
        harness.copy(policy = policy, finisher = finisher)(using harness.inputData, harness.outputData),
      OptimizableStructure.fromOptimizableLeaf[Predict[PolicyInput, PolicyStep]],
      OptimizableStructure.fromOptimizableLeaf[Predict[FinishInput, O]]
    )

  given harnessStructure[I, O]: OptimizableStructure[Harness[I, O]] = structure[I, O]

  /** The runner of the optimizers: it reads the record of the inputs through the signature, runs the harness, and puts
    * the encoded output in `RawPrediction.values`, where a metric reads it.
    */
  def runner[I, O]: ProgramRunner[Harness[I, O]] =
    new ProgramRunner[Harness[I, O]]:
      def run(program: Harness[I, O], call: ProgramCall[DynamicValue.Record])(using
          RuntimeContext
      ): Either[DspyError, RawPrediction] =
        program.signature.inputShape.decode(call.input).flatMap(input => program(call.mapInput(_ => input))).map(_.raw)

  given harnessRunner[I, O]: ProgramRunner[Harness[I, O]] = runner[I, O]
