package dspy4s.programs.contracts

import dspy4s.core.contracts.PredictorKey
import dspy4s.signatures.Shape
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

  /** The key that this boundary writes into its trace entries when the module is a predictor (an optimizable leaf).
    * `None` for each other module. See [[ModuleLifecycle.predictor]].
    */
  def predictor: Option[PredictorKey] = None

object CallObservation:
  def apply[Call, Output](
      inputProjection       : Call => DynamicValue.Record,
      traceEnabledProjection: Call => Boolean,
      outputProjection      : Prediction[Output] => DynamicValue.Record,
      predictorKey          : Option[PredictorKey] = None
  ): CallObservation[Call, Output] =
    new CallObservation[Call, Output]:
      def inputs(call       : Call): DynamicValue.Record               = inputProjection(call)
      def traceEnabled(call : Call): Boolean                           = traceEnabledProjection(call)
      def outputs(prediction: Prediction[Output]): DynamicValue.Record = outputProjection(prediction)
      override def predictor: Option[PredictorKey]                     = predictorKey

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

  /** Standard observation for record-valued programs: both semantic values are records and the result retains its raw
    * envelope.
    */
  val dynamic: ModuleLifecycle[DynamicValue.Record, DynamicValue.Record] =
    observed(_.input, _.traceEnabled, _.raw.values)

  /** Standard observation, with module-specific input encoding and the shared prediction projection. */
  def typed[I, O](inputs: ProgramCall[I] => DynamicValue.Record): ModuleLifecycle[I, O] =
    observed(inputs, _.traceEnabled, _.raw.values)

  /** Signature-backed observation. */
  def typed[I, O](shape: Shape[I]): ModuleLifecycle[I, O] =
    typed(call => call.encodedInput(shape))

  /** Observation for a predictor: a module that is one optimizable leaf. Each trace entry of the module carries `key`,
    * and an optimizer uses the key to find the entries of the leaf. `key` must be equal to the key of the read-only
    * metadata that the `OptimizableLeaf` instance of the module reports
    * ([[dspy4s.programs.optimization.OptimizableMetadata.key]]). An optimizer rejects a leaf type that records no key.
    */
  def predictor[I, O](shape: Shape[I], key: PredictorKey): ModuleLifecycle[I, O] =
    Observed(CallObservation(call => call.encodedInput(shape), _.traceEnabled, _.raw.values, Some(key)))

  /** [[predictor]] for a record-valued program. */
  def dynamicPredictor(key: PredictorKey): ModuleLifecycle[DynamicValue.Record, DynamicValue.Record] =
    Observed(CallObservation(_.input, _.traceEnabled, _.raw.values, Some(key)))

  /** Observation for an outer wrapper that has no authoritative shape for encoding its input. */
  def typedWithoutInputs[I, O]: ModuleLifecycle[I, O] =
    typed(_ => DynamicValue.Record.empty)
