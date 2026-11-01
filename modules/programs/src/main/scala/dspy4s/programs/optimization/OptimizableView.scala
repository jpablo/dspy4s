package dspy4s.programs.optimization

import dspy4s.core.contracts.{PredictorKey, SignatureLayout}

/** A non-executable snapshot combining read-only metadata with current optimizable parameters.
  *
  * The metadata is the key that the predictor of this leaf writes into its trace entries. Its `structure` contains the
  * signature name and fields but never its instructions: instructions are writable [[OptimizableParameters]]. Its
  * `moduleName` is the execution/lifecycle name. Neither field is accepted by [[OptimizableStructure.replace]], so
  * inspection cannot accidentally turn architecture into optimizer parameters. `replace` cannot change the metadata
  * (the `frame` law of [[OptimizableLeaf]]), so the key is the same for each candidate of one program.
  */
final case class OptimizableView(metadata: PredictorKey, parameters: OptimizableParameters) derives CanEqual:
  /** The current effective layout, reconstructed from the read-only structure and writable instructions. */
  def layout: SignatureLayout = metadata.structure.withInstructions(parameters.instructions)

  /** The lifecycle/module name exposed by the underlying executable predictor. */
  def moduleName: String = metadata.moduleName
