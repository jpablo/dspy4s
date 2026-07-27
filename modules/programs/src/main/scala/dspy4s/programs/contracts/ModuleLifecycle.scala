package dspy4s.programs.contracts

import dspy4s.core.data.DynamicPrediction
import dspy4s.typed.{Prediction, Shape}
import zio.blocks.schema.DynamicValue

/** The observable projections of one module boundary.
  *
  * This is a value-level strategy rather than a typeclass because observation is not uniquely determined by `Call` and
  * `Result`: a signature-backed `Predict` and an outer wrapper such as `BestOfN` have the same call/result types but
  * intentionally record different input payloads.
  */
trait CallObservation[-Call, -Result]:
  def inputs(call: Call): DynamicValue.Record
  def traceEnabled(call: Call): Boolean
  def outputs(result: Result): DynamicValue.Record

object CallObservation:
  def apply[Call, Result](
      inputProjection: Call => DynamicValue.Record,
      traceEnabledProjection: Call => Boolean,
      outputProjection: Result => DynamicValue.Record
  ): CallObservation[Call, Result] =
    new CallObservation[Call, Result]:
      def inputs(call: Call): DynamicValue.Record       = inputProjection(call)
      def traceEnabled(call: Call): Boolean             = traceEnabledProjection(call)
      def outputs(result: Result): DynamicValue.Record   = outputProjection(result)

/** Whether a module boundary is observable and, when it is, how its uniform [[ProgramCall]] and result project into
  * runtime records. Like [[Module]], the first type parameter is the semantic input inside `ProgramCall[I]`.
  * Structural composition uses [[ModuleLifecycle.Transparent]]; executable leaves and observable wrappers use
  * [[ModuleLifecycle.Observed]].
  */
sealed trait ModuleLifecycle[I, Result]

object ModuleLifecycle:
  final case class Transparent[I, Result]() extends ModuleLifecycle[I, Result]
  final case class Observed[I, Result](observation: CallObservation[ProgramCall[I], Result])
      extends ModuleLifecycle[I, Result]

  def transparent[I, Result]: ModuleLifecycle[I, Result] = Transparent()

  def observed[I, Result](
      inputs: ProgramCall[I] => DynamicValue.Record,
      traceEnabled: ProgramCall[I] => Boolean,
      outputs: Result => DynamicValue.Record
  ): ModuleLifecycle[I, Result] =
    Observed(CallObservation(inputs, traceEnabled, outputs))

  /** Standard dynamic-spine observation: the call already contains a record and the prediction exposes one. */
  val dynamic: ModuleLifecycle[DynamicValue.Record, DynamicPrediction] =
    observed(_.input, _.traceEnabled, _.values)

  /** Standard typed observation, with module-specific input encoding and the shared typed prediction projection. */
  def typed[I, O](inputs: ProgramCall[I] => DynamicValue.Record): ModuleLifecycle[I, Prediction[O]] =
    observed(inputs, _.traceEnabled, _.raw.values)

  /** Signature-backed typed observation. */
  def typed[I, O](shape: Shape[I]): ModuleLifecycle[I, Prediction[O]] =
    typed(call => call.encodedInput(shape))

  /** Typed observation for an outer wrapper that has no authoritative shape for encoding its input. */
  def typedWithoutInputs[I, O]: ModuleLifecycle[I, Prediction[O]] =
    typed(_ => DynamicValue.Record.empty)
