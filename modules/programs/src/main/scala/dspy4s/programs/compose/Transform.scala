package dspy4s.programs.compose

import dspy4s.programs.optimization.*
import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
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
          Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
        ))

/** Shared optimizable structure for a transparent unary wrapper. */
private[compose] object UnaryOptimizableStructure:
  def passthrough[W, P, N <: Int](get: W => P)(replaceInner: (W, P) => W)(using
      inner: OptimizableStructure.WithArity[P, N]
  ): OptimizableStructure.Of[W, N] =
    new OptimizableStructure.Of[W, N]:
      def arity(program  : W): Int                                       = inner.arity(get(program))
      def inspect(program: W): Vector[OptimizableView]                   = inner.inspect(get(program))
      def replace(program: W, updates: Vector[OptimizableParameters]): W =
        replaceInner(program, inner.replace(get(program), updates))
      override def inspectNamed(program: W): Vector[(String, OptimizableView)] = inner.inspectNamed(get(program))

/** Lift a total Scala function into a parameter-free, lifecycle-transparent program. */
final case class Lift[I, O](run: I => O) extends TransparentModule[I, O]:
  override val moduleName: String = "lift"

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    TransformResult.guard("program_lift")(Right(Prediction(run(call.input), RawPrediction.empty)))

object Lift:
  given liftOptimizableStructure[I, O]: OptimizableStructure.WithArity[Lift[I, O], 0] = OptimizableStructure.empty

/** Lift an explicitly fallible Scala function into a parameter-free, lifecycle-transparent program. */
final case class LiftEither[I, O](run: I => Either[DspyError, O])
    extends TransparentModule[I, O]:
  override val moduleName: String = "lift_either"

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    TransformResult.guard("program_lift_either")(run(call.input).map(Prediction(_, RawPrediction.empty)))

object LiftEither:
  given liftEitherOptimizableStructure[I, O]: OptimizableStructure.WithArity[LiftEither[I, O], 0] =
    OptimizableStructure.empty

/** Covariantly transform a program's semantic output while preserving its raw prediction envelope. */
final case class MapOutput[I, O, B, P <: Module[I, O]](program: P, map: O => B)
    extends TransparentModule[I, B]:
  override val moduleName: String = "map_output"

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[B]] =
    program(call).flatMap { prediction =>
      TransformResult.guard("program_map_output")(Right(prediction.map(map)))
    }

object MapOutput:
  given mapOutputOptimizableStructure[I, O, B, P <: Module[I, O], N <: Int](using
      inner: OptimizableStructure.WithArity[P, N]
  ): OptimizableStructure.Of[MapOutput[I, O, B, P], N] =
    UnaryOptimizableStructure.passthrough[MapOutput[I, O, B, P], P, N](_.program)((wrapper, updated) =>
      wrapper.copy(program = updated)
    )

/** Contravariantly adapt a program's input, forwarding the outer call controls unchanged. */
final case class ContramapInput[J, I, O, P <: Module[I, O]](program: P, contramap: J => I)
    extends TransparentModule[J, O]:
  override val moduleName: String = "contramap_input"

  override protected def forward(call: ProgramCall[J])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    TransformResult.guard("program_contramap_input")(Right(contramap(call.input))).flatMap { input =>
      program(call.mapInput(_ => input))
    }

object ContramapInput:
  given contramapInputOptimizableStructure[J, I, O, P <: Module[I, O], N <: Int](using
      inner: OptimizableStructure.WithArity[P, N]
  ): OptimizableStructure.Of[ContramapInput[J, I, O, P], N] =
    UnaryOptimizableStructure.passthrough[ContramapInput[J, I, O, P], P, N](_.program)((wrapper, updated) =>
      wrapper.copy(program = updated)
    )

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
    UnaryOptimizableStructure.passthrough[Dimap[J, I, O, B, P], P, N](_.program)((wrapper, updated) =>
      wrapper.copy(program = updated)
    )

extension [I, O, P <: Module[I, O]](self: P)
  /** Transform the semantic output and retain the program's raw prediction. */
  def mapOutput[B](f: O => B): MapOutput[I, O, B, P] = MapOutput(self, f)

  /** Adapt a new input type into the program's input type. */
  def contramapInput[J](f: J => I): ContramapInput[J, I, O, P] = ContramapInput(self, f)

  /** Adapt input and output in one transparent, optimizer-addressable wrapper. */
  def dimap[J, B](before: J => I)(after: O => B): Dimap[J, I, O, B, P] = Dimap(self, before, after)
