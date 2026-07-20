package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TransparentModule
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.Prediction

import scala.util.control.NonFatal

/** Pure and fallible local transformations lifted into the typed program carrier.
  *
  * These nodes are lifecycle-transparent and parameter-free: they perform no LM/tool work, emit no callbacks of their
  * own, and introduce no optimizer-addressable predictors. Non-fatal exceptions from user functions are normalized
  * into the program's `Either` error channel; use [[LiftEither]] when failure is part of the function's declared API.
  */
private[programs] object TransformResult:
  def guard[A](component: String)(result: => Either[DspyError, A]): Either[DspyError, A] =
    try result
    catch
      case NonFatal(error) =>
        Left(RuntimeError(component, Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)))

/** Shared optimizer traversal for a transparent unary wrapper. */
private[programs] object UnaryPredictors:
  def passthrough[W, P](get: W => P)(replaceInner: (W, P) => W)(using inner: Predictors[P]): Predictors[W] =
    new Predictors[W]:
      def inspect(program: W): Vector[PredictorView] = inner.inspect(get(program))
      def replace(program: W, updates: Vector[PredictorState]): W =
        replaceInner(program, inner.replace(get(program), updates))
      override def inspectNamed(program: W): Vector[(String, PredictorView)] = inner.inspectNamed(get(program))

/** Lift a total Scala function into a parameter-free, lifecycle-transparent program. */
final case class Lift[I, O](run: I => O) extends TransparentModule[TypedCall[I], Prediction[O]]:
  override val moduleName: String = "lift"

  override protected def forward(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    TransformResult.guard("program_lift")(Right(Prediction(run(call.input), DynamicPrediction.empty)))

object Lift:
  given liftPredictors[I, O]: Predictors[Lift[I, O]] = Predictors.empty

/** Lift an explicitly fallible Scala function into a parameter-free, lifecycle-transparent program. */
final case class LiftEither[I, O](run: I => Either[DspyError, O])
    extends TransparentModule[TypedCall[I], Prediction[O]]:
  override val moduleName: String = "lift_either"

  override protected def forward(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    TransformResult.guard("program_lift_either")(run(call.input).map(Prediction(_, DynamicPrediction.empty)))

object LiftEither:
  given liftEitherPredictors[I, O]: Predictors[LiftEither[I, O]] = Predictors.empty

/** Covariantly transform a program's semantic output while preserving its raw prediction envelope. */
final case class MapOutput[I, O, B, P <: Module[TypedCall[I], Prediction[O]]](program: P, map: O => B)
    extends TransparentModule[TypedCall[I], Prediction[B]]:
  override val moduleName: String = "map_output"

  override protected def forward(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[B]] =
    program.apply(call).flatMap { prediction =>
      TransformResult.guard("program_map_output")(Right(Prediction(map(prediction.output), prediction.raw)))
    }

object MapOutput:
  given mapOutputPredictors[I, O, B, P <: Module[TypedCall[I], Prediction[O]]](using
      inner: Predictors[P]
  ): Predictors[MapOutput[I, O, B, P]] =
    UnaryPredictors.passthrough[MapOutput[I, O, B, P], P](_.program)((wrapper, updated) =>
      wrapper.copy(program = updated)
    )

/** Contravariantly adapt a program's input, forwarding the outer call controls unchanged. */
final case class ContramapInput[J, I, O, P <: Module[TypedCall[I], Prediction[O]]](program: P, contramap: J => I)
    extends TransparentModule[TypedCall[J], Prediction[O]]:
  override val moduleName: String = "contramap_input"

  override protected def forward(call: TypedCall[J])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    TransformResult.guard("program_contramap_input")(Right(contramap(call.input))).flatMap { input =>
      program.apply(TypedCall(input, call.config, call.traceEnabled, call.rolloutId))
    }

object ContramapInput:
  given contramapInputPredictors[J, I, O, P <: Module[TypedCall[I], Prediction[O]]](using
      inner: Predictors[P]
  ): Predictors[ContramapInput[J, I, O, P]] =
    UnaryPredictors.passthrough[ContramapInput[J, I, O, P], P](_.program)((wrapper, updated) =>
      wrapper.copy(program = updated)
    )

/** Adapt both sides of a program in one transparent node, preserving the inner prediction envelope. */
final case class Dimap[J, I, O, B, P <: Module[TypedCall[I], Prediction[O]]](
    program: P,
    contramap: J => I,
    map: O => B
) extends TransparentModule[TypedCall[J], Prediction[B]]:
  override val moduleName: String = "dimap"

  override protected def forward(call: TypedCall[J])(using RuntimeContext): Either[DspyError, Prediction[B]] =
    for
      input <- TransformResult.guard("program_dimap_input")(Right(contramap(call.input)))
      prediction <- program.apply(TypedCall(input, call.config, call.traceEnabled, call.rolloutId))
      output <- TransformResult.guard("program_dimap_output")(Right(map(prediction.output)))
    yield Prediction(output, prediction.raw)

object Dimap:
  given dimapPredictors[J, I, O, B, P <: Module[TypedCall[I], Prediction[O]]](using
      inner: Predictors[P]
  ): Predictors[Dimap[J, I, O, B, P]] =
    UnaryPredictors.passthrough[Dimap[J, I, O, B, P], P](_.program)((wrapper, updated) =>
      wrapper.copy(program = updated)
    )

extension [I, O, P <: Module[TypedCall[I], Prediction[O]]](self: P)
  /** Transform the semantic output and retain the program's raw prediction. */
  def mapOutput[B](f: O => B): MapOutput[I, O, B, P] = MapOutput(self, f)

  /** Adapt a new input type into the program's input type. */
  def contramapInput[J](f: J => I): ContramapInput[J, I, O, P] = ContramapInput(self, f)

  /** Adapt input and output in one transparent, optimizer-addressable wrapper. */
  def dimap[J, B](before: J => I)(after: O => B): Dimap[J, I, O, B, P] = Dimap(self, before, after)
