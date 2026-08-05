package dspy4s.programs.contracts

import dspy4s.typed.{Prediction, Shape}
import zio.blocks.schema.DynamicValue

/** The observable projections of one module boundary.
  *
  * This is a value-level strategy rather than a typeclass because observation is not uniquely determined by `Call` and
  * `Output`: a signature-backed `Predict` and an outer wrapper such as `BestOfN` have the same call/output types but
  * intentionally record different input payloads.
  */
trait CallObservation[-Call, Output]:
  def inputs(call       : Call): DynamicValue.Record
  def traceEnabled(call : Call): Boolean
  def outputs(prediction: Prediction[Output]): DynamicValue.Record

object CallObservation:
  def apply[Call, Output](
      inputProjection       : Call => DynamicValue.Record,
      traceEnabledProjection: Call => Boolean,
      outputProjection      : Prediction[Output] => DynamicValue.Record
  ): CallObservation[Call, Output] =
    new CallObservation[Call, Output]:
      def inputs(call       : Call): DynamicValue.Record               = inputProjection(call)
      def traceEnabled(call : Call): Boolean                           = traceEnabledProjection(call)
      def outputs(prediction: Prediction[Output]): DynamicValue.Record = outputProjection(prediction)

/** Whether a module boundary is observable and, when it is, how its uniform [[ProgramCall]] and result project into
  * runtime records. Like [[Module]], the first type parameter is the semantic input inside `ProgramCall[I]`. Structural
  * composition uses [[ModuleLifecycle.Transparent]]; executable leaves and observable wrappers use
  * [[ModuleLifecycle.Observed]].
  */
sealed trait ModuleLifecycle[I, O]

object ModuleLifecycle:
  final case class Transparent[I, O]()                                             extends ModuleLifecycle[I, O]
  final case class Observed[I, O](observation: CallObservation[ProgramCall[I], O]) extends ModuleLifecycle[I, O]

  def transparent[I, O]: ModuleLifecycle[I, O] = Transparent()

  def observed[I, O](
      inputs      : ProgramCall[I] => DynamicValue.Record,
      traceEnabled: ProgramCall[I] => Boolean,
      outputs     : Prediction[O] => DynamicValue.Record
  ): ModuleLifecycle[I, O] =
    Observed(CallObservation(inputs, traceEnabled, outputs))

  /** Standard dynamic-spine observation: both semantic values are records and the result retains its raw envelope. */
  val dynamic: ModuleLifecycle[DynamicValue.Record, DynamicValue.Record] =
    observed(_.input, _.traceEnabled, _.raw.values)

  /** Standard typed observation, with module-specific input encoding and the shared typed prediction projection. */
  def typed[I, O](inputs: ProgramCall[I] => DynamicValue.Record): ModuleLifecycle[I, O] =
    observed(inputs, _.traceEnabled, _.raw.values)

  /** Signature-backed typed observation. */
  def typed[I, O](shape: Shape[I]): ModuleLifecycle[I, O] =
    typed(call => call.encodedInput(shape))

  /** Typed observation for an outer wrapper that has no authoritative shape for encoding its input. */
  def typedWithoutInputs[I, O]: ModuleLifecycle[I, O] =
    typed(_ => DynamicValue.Record.empty)
