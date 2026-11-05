package dspy4s.programs.compose

import dspy4s.programs.optimization.*
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TransparentModule
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.Prediction

import scala.util.control.NonFatal

/** Pure and fallible local transformations lifted into the program carrier.
  *
  * These nodes are lifecycle-transparent and parameter-free: they perform no LM/tool work, emit no callbacks of their
  * own, and introduce no optimizer-addressable predictors. Non-fatal exceptions from user functions are normalized into
  * the program's `Either` error channel; use [[LiftEither]] when failure is part of the function's declared API.
  */
private[compose] object TransformResult:
  def guard[A](component: String)(result: => Either[DspyError, A]): Either[DspyError, A] =
    try result
    catch
      case NonFatal(error) => Left(RuntimeError(
          component,
          DspyError.messageOf(error)
        ))

/** Lift a total Scala function into a parameter-free, lifecycle-transparent program. */
final case class Lift[I, O](run: I => O) extends TransparentModule[I, O]:
  override val moduleName: String = "lift"

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    TransformResult.guard("program_lift")(Right(Prediction.pure(run(call.input))))

object Lift:
  given liftOptimizableStructure[I, O]: OptimizableStructure.WithArity[Lift[I, O], 0] = OptimizableStructure.empty

/** Lift an explicitly fallible Scala function into a parameter-free, lifecycle-transparent program. */
final case class LiftEither[I, O](run: I => Either[DspyError, O])
    extends TransparentModule[I, O]:
  override val moduleName: String = "lift_either"

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    TransformResult.guard("program_lift_either")(run(call.input).map(Prediction.pure(_)))

object LiftEither:
  given liftEitherOptimizableStructure[I, O]: OptimizableStructure.WithArity[LiftEither[I, O], 0] =
    OptimizableStructure.empty

/** Adapt both sides of a program in one transparent node, preserving the inner prediction envelope. */
final case class Dimap[J, I, O, B, P <: Module[I, O]](
    program  : P,
    contramap: J => I,
    map      : O => B
) extends TransparentModule[J, B]:
  override val moduleName: String = "dimap"

  override protected def forward(call: ProgramCall[J])(using RuntimeContext): Either[DspyError, Prediction[B]] =
    for
      input      <- TransformResult.guard("program_dimap_input")(Right(contramap(call.input)))
      prediction <- program(call.mapInput(_ => input))
      output     <- TransformResult.guard("program_dimap_output")(Right(map(prediction.output)))
    yield prediction.map(_ => output)

object Dimap:
  given dimapOptimizableStructure[J, I, O, B, P <: Module[I, O], N <: Int](using
      inner: OptimizableStructure.WithArity[P, N]
  ): OptimizableStructure.Of[Dimap[J, I, O, B, P], N] =
    OptimizableStructure.unary(
      "Transparent wrapper",
      _.program,
      (wrapper, updated) => wrapper.copy(program = updated),
      inner
    )

extension [I, O, P <: Module[I, O]](self: P)
  /** Transform the semantic output and retain the program's raw prediction. */
  def mapOutput[B](f: O => B): Dimap[I, I, O, B, P] = Dimap(self, identity, f)

  /** Adapt a new input type into the program's input type. The outer call controls go to the program unchanged. */
  def contramapInput[J](f: J => I): Dimap[J, I, O, O, P] = Dimap(self, f, identity)

  /** Adapt input and output in one transparent, optimizer-addressable wrapper. */
  def dimap[J, B](before: J => I)(after: O => B): Dimap[J, I, O, B, P] = Dimap(self, before, after)
