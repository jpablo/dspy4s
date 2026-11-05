package dspy4s.programs.optimization

import dspy4s.core.contracts.{PredictorKey, SignatureLayout}

/** A non-executable snapshot combining read-only metadata with current optimizable parameters.
  *
  * The metadata is the key that the predictor of this leaf writes into its trace entries. Its `structure` contains the
  * signature name and fields but never its instructions: instructions are writable [[OptimizableParameters]]. Its
  * `moduleName` is the execution/lifecycle name. Neither field is accepted by [[OptimizableStructure.replace]], so
  * inspection cannot accidentally turn architecture into optimizer parameters. `replace` cannot change the metadata
  * (the `frame` law of [[OptimizableLeaf]]), so the key is the same for each candidate of one program.
  *
  * `defaultInstructions` is the text of a leaf whose default text differs from the default text of its structure
  * ([[OptimizableLeaf.defaultInstructions]]). It is not a parameter, and it is not in the key.
  */
final case class OptimizableView(
    metadata           : PredictorKey,
    parameters         : OptimizableParameters,
    defaultInstructions: Option[String] = None
) derives CanEqual:
  /** The current effective layout, reconstructed from the read-only structure and writable instructions. Read the text
    * of a leaf from [[effectiveInstructions]]: the default text of this layout names the fields of the structure, and a
    * leaf with [[defaultInstructions]] runs another text.
    */
  def layout: SignatureLayout = metadata.structure.withInstructions(parameters.instructions)

  /** The text that the leaf runs: its instructions, else [[defaultInstructions]], else the default text of [[layout]].
    */
  def effectiveInstructions: String =
    parameters.instructions.orElse(defaultInstructions).getOrElse(layout.effectiveInstructions)

  /** The lifecycle/module name exposed by the underlying executable predictor. */
  def moduleName: String = metadata.moduleName
