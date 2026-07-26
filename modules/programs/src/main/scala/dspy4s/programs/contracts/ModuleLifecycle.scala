package dspy4s.programs.contracts

import dspy4s.core.data.DynamicPrediction
import dspy4s.typed.{Prediction, Shape}
import zio.blocks.schema.DynamicValue

/** The observable projections of one module boundary.
  *
  * This is a value-level strategy rather than a typeclass because observation is not uniquely determined by `I` and
  * `O`: a signature-backed `Predict` and an outer wrapper such as `BestOfN` have the same call/result types but
  * intentionally record different input payloads.
  */
trait CallObservation[-I, -O]:
  def inputs(input: I): DynamicValue.Record
  def traceEnabled(input: I): Boolean
  def outputs(output: O): DynamicValue.Record

object CallObservation:
  def apply[I, O](
      inputProjection: I => DynamicValue.Record,
      traceEnabledProjection: I => Boolean,
      outputProjection: O => DynamicValue.Record
  ): CallObservation[I, O] =
    new CallObservation[I, O]:
      def inputs(input: I): DynamicValue.Record   = inputProjection(input)
      def traceEnabled(input: I): Boolean         = traceEnabledProjection(input)
      def outputs(output: O): DynamicValue.Record = outputProjection(output)

/** Whether a module boundary is observable and, when it is, how its uniform [[ProgramCall]] and result project into
  * runtime records. Like [[Module]], the first type parameter is the semantic input inside `ProgramCall[I]`.
  * Structural composition uses [[ModuleLifecycle.Transparent]]; executable leaves and observable wrappers use
  * [[ModuleLifecycle.Observed]].
  */
sealed trait ModuleLifecycle[I, O]

object ModuleLifecycle:
  final case class Transparent[I, O]() extends ModuleLifecycle[I, O]
  final case class Observed[I, O](observation: CallObservation[ProgramCall[I], O]) extends ModuleLifecycle[I, O]

  def transparent[I, O]: ModuleLifecycle[I, O] = Transparent()

  def observed[I, O](
      inputs: ProgramCall[I] => DynamicValue.Record,
      traceEnabled: ProgramCall[I] => Boolean,
      outputs: O => DynamicValue.Record
  ): ModuleLifecycle[I, O] =
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
