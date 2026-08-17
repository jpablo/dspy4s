package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.programs.contracts.Prediction

/** Typed ensemble construction over homogeneous visible member programs. */
object Ensemble:

  /** Run all members from left to right and reduce their complete typed prediction evidence. */
  def apply[I, O, R](
      members: Vector[ProgramWithEnv[I, O, R]]
  )(
      reduce: Vector[Prediction[O]] => Either[DspyError, O]
  ): ProgramWithEnv[I, O, R] =
    require(members.nonEmpty, "Ensemble members must not be empty")
    Program.collectAll(members.map(_.withEvidence)) >>> Program.liftEither(reduce)
