package dspy4s.programs.compose

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.TransparentModule
import dspy4s.programs.optimization.*
import dspy4s.programs.contracts.Prediction

import scala.annotation.tailrec
import scala.compiletime.ops.int.+

/** `a >>> b` — sequential (dependent) composition: run `a`, thread its output value into `b`. The Category operation.
  */
final case class AndThen[I, X, O, A <: Module[I, X], B <: Module[X, O]](
    first : A,
    second: B
) extends TransparentModule[I, O]:
  override val moduleName: String = "and_then"

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    AndThen.run(call, unfoldOnto(AndThen.Pending.Done()), RawPrediction.empty)

  /** Put the two children before the modules that remain. The intermediate type `X` is in scope here, so the compiler
    * checks that the output of `first` is the input of `second`.
    */
  private def unfoldOnto[Z](rest: AndThen.Pending[O, Z]): AndThen.Pending[I, Z] =
    AndThen.Pending.Step(first, AndThen.Pending.Step(second, rest))

object AndThen:
  /** The modules that remain, as a type-aligned heap stack: the output of each step is the input of the next step. */
  private enum Pending[A, Z]:
    case Done[A]()                                              extends Pending[A, A]
    case Step[A, X, Z](head: Module[A, X], rest: Pending[X, Z]) extends Pending[A, Z]

  /** Execute a sequential syntax tree with an explicit heap stack.
    *
    * [[Pending]] keeps each intermediate type, so the interpreter needs no cast. Leaf modules still run through
    * [[Module.apply]], so their callbacks, trace, history, and error behavior do not change.
    */
  @tailrec
  private def run[A, Z](call: ProgramCall[A], pending: Pending[A, Z], raw: RawPrediction)(using
      RuntimeContext
  ): Either[DspyError, Prediction[Z]] =
    pending match
      case Pending.Done()              => Right(Prediction(call.input, raw))
      case step: Pending.Step[A, x, Z] => step.head match
          case sequential: AndThen[A, ?, `x`, ?, ?] => run(call, sequential.unfoldOnto(step.rest), raw)
          case leaf                                 => leaf(call) match
              case Left(error)       => Left(error)
              case Right(prediction) =>
                // The outer call's controls pass through unchanged; combine the evidence envelopes in execution order.
                run(call.mapInput(_ => prediction.output), step.rest, raw.followedBy(prediction.raw))

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
