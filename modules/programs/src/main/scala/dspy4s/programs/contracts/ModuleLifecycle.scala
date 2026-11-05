package dspy4s.programs.contracts

import dspy4s.core.contracts.PredictorKey
import dspy4s.signatures.Shape
import zio.blocks.schema.DynamicValue

/** Whether a module boundary is observable and, when it is, how its uniform [[ProgramCall]] projects into runtime
  * records. The type parameter is the semantic input inside `ProgramCall[I]`, as in [[Module]]. Structural composition
  * uses [[ModuleLifecycle.Transparent]]; executable leaves and observable wrappers use [[ModuleLifecycle.Observed]].
  *
  * This is a value-level strategy rather than a typeclass because observation is not uniquely determined by `I`: a
  * signature-backed `Predict` and an outer wrapper such as `BestOfN` have the same call type but intentionally record
  * different input payloads.
  */
sealed trait ModuleLifecycle[I]

object ModuleLifecycle:
  final case class Transparent[I]() extends ModuleLifecycle[I]

  /** An observed boundary. The output record of each observed module is the value record of its raw prediction, and the
    * `traceEnabled` flag of the call controls its trace and its history.
    *
    * @param inputs
    *   the input record of a call
    * @param predictor
    *   the key that this boundary writes into its trace entries when the module is a predictor (an optimizable leaf).
    *   `None` for each other module. See [[ModuleLifecycle.predictor]].
    */
  final case class Observed[I](
      inputs   : ProgramCall[I] => DynamicValue.Record,
      predictor: Option[PredictorKey] = None
  ) extends ModuleLifecycle[I]

  def transparent[I]: ModuleLifecycle[I] = Transparent()

  /** Standard observation for record-valued programs: the input record of a call is its semantic input. */
  val dynamic: ModuleLifecycle[DynamicValue.Record] = Observed(_.input)

  /** Standard observation, with module-specific input encoding and the shared prediction projection. */
  def typed[I](inputs: ProgramCall[I] => DynamicValue.Record): ModuleLifecycle[I] =
    Observed(inputs)

  /** Signature-backed observation. */
  def typed[I](shape: Shape[I]): ModuleLifecycle[I] =
    typed(call => call.encodedInput(shape))

  /** Observation for a predictor: a module that is one optimizable leaf. Each trace entry of the module carries `key`,
    * and an optimizer uses the key to find the entries of the leaf. `key` must be equal to the read-only metadata that
    * the `OptimizableLeaf` instance of the module reports ([[dspy4s.programs.optimization.OptimizableLeaf.metadata]]).
    * An optimizer rejects a leaf type that records no key.
    */
  def predictor[I](shape: Shape[I], key: PredictorKey): ModuleLifecycle[I] =
    Observed(call => call.encodedInput(shape), Some(key))

  /** [[predictor]] for a record-valued program. */
  def dynamicPredictor(key: PredictorKey): ModuleLifecycle[DynamicValue.Record] =
    Observed(_.input, Some(key))

  /** Observation for an outer wrapper that has no authoritative shape for encoding its input. */
  def typedWithoutInputs[I]: ModuleLifecycle[I] =
    typed(_ => DynamicValue.Record.empty)
