package dspy4s.harness

import dspy4s.programs.ProgramRunner
import dspy4s.programs.optimization.OptimizableStructure

/** What the optimizers of dspy4s need from a program (CODE_HARNESS_DESIGN.md, 9): each one takes
  * `P: {OptimizableStructure, ProgramRunner}`. The companion of `Harness` extends this trait, so the givens are in the
  * implicit scope of each `Harness`.
  */
trait HarnessGivens:

  /** The leaves `policy` and `finisher`, in this order and with distinct names, so an optimizer tells their traces
    * apart. A replace changes only these leaves. The strategy of phase 4 has no leaves.
    */
  def structure[I, O]: OptimizableStructure[Harness[I, O]] = ???

  given harnessStructure[I, O]: OptimizableStructure[Harness[I, O]] = structure[I, O]

  /** The runner of the optimizers: it reads the record of the inputs through the signature, runs the harness, and puts
    * the encoded output in `RawPrediction.values`, where a metric reads it.
    */
  def runner[I, O]: ProgramRunner[Harness[I, O]] = ???

  given harnessRunner[I, O]: ProgramRunner[Harness[I, O]] = runner[I, O]
