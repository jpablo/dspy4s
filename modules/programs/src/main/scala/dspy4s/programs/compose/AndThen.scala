package dspy4s.programs.compose

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.TransparentModule
import dspy4s.programs.optimization.*
import dspy4s.programs.contracts.Prediction

import scala.compiletime.ops.int.+
import scala.collection.mutable.ArrayDeque

/** `a >>> b` — sequential (dependent) composition: run `a`, thread its output value into `b`. The Category operation.
  */
final case class AndThen[I, X, O, A <: Module[I, X], B <: Module[X, O]](
    first : A,
    second: B
) extends TransparentModule[I, O]:
  override val moduleName: String = "and_then"

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    AndThen.run(this, call)

object AndThen:
  /** Execute a sequential syntax tree with an explicit heap stack.
    *
    * Runtime type erasure hides each intermediate type, but construction proves that every node's output is the next
    * node's input. The casts below only remove and restore those already-checked intermediate types. Leaf modules still
    * run through [[Module.apply]], so their callbacks, trace, history, and error behavior do not change.
    */
  private def run[I, O](root: AndThen[I, ?, O, ?, ?], call: ProgramCall[I])(using
      RuntimeContext
  ): Either[DspyError, Prediction[O]] =
    val pending = ArrayDeque.empty[Module[Any, Any]]

    def push(module: Module[?, ?]): Unit =
      pending.prepend(module.asInstanceOf[Module[Any, Any]])

    push(root)
    var output: Any                   = call.input
    var currentCall: ProgramCall[Any] = call.asInstanceOf[ProgramCall[Any]]
    var accumulated: RawPrediction    = RawPrediction.empty

    while pending.nonEmpty do
      pending.removeHead() match
        case sequential: AndThen[?, ?, ?, ?, ?] =>
          push(sequential.second)
          push(sequential.first)
        case leaf => leaf(currentCall) match
            case Left(error)       => return Left(error)
            case Right(prediction) =>
              output = prediction.output
              currentCall = call.mapInput(_ => output)
              accumulated = accumulated.followedBy(prediction.raw)

    Right(Prediction(output.asInstanceOf[O], accumulated))

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
