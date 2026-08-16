package dspy4s.programs.compose

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.TransparentModule
import dspy4s.programs.optimization.*
import dspy4s.programs.contracts.Prediction

import scala.compiletime.ops.int.+

/** `a >>> b` — sequential (dependent) composition: run `a`, thread its output value into `b`. The Category operation.
  */
final case class AndThen[I, X, O, A <: Module[I, X], B <: Module[X, O]](
    first : A,
    second: B
) extends TransparentModule[I, O]:
  override val moduleName: String = "and_then"

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    first(call).flatMap { predX =>
      // The outer call's controls pass through unchanged; combine the evidence envelopes after the carrier runs.
      second(call.mapInput(_ => predX.output))
        .map(predO => predO.copy(raw = predX.raw.followedBy(predO.raw)))
    }

object AndThen:
  /** Structural `read(a) ++ read(b)`; `replace` slices the updates by `first`'s read-arity. */
  given andThenOptimizableStructure[
      I,
      X,
      O,
      A <: Module[I, X],
      B <: Module[X, O],
      NA <: Int,
      NB <: Int
  ](
      using
      pa: OptimizableStructure.WithArity[A, NA],
      pb: OptimizableStructure.WithArity[B, NB]
  ): OptimizableStructure.Of[AndThen[I, X, O, A, B], NA + NB] =
    PairOptimizableStructure.structure[AndThen[I, X, O, A, B], A, B, NA, NB](
      "AndThen",
      "first",
      "second",
      _.first,
      _.second,
      (program, first, second) => program.copy(first = first, second = second),
      pa,
      pb
    )
