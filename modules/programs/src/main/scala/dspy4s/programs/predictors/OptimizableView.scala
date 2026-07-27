package dspy4s.programs.predictors

import dspy4s.core.contracts.SignatureLayout

/** Read-only information about one optimizable leaf.
  *
  * [[structure]] contains the signature name and fields but never its instructions: instructions are writable
  * [[OptimizableParameters]]. [[moduleName]] is the execution/lifecycle name. Neither field is accepted by
  * [[OptimizableTraversal.replace]], so inspection cannot accidentally turn architecture into optimizer parameters.
  */
final case class OptimizableMetadata(structure: SignatureLayout, moduleName: String) derives CanEqual:
  require(structure.instructions.isEmpty, "OptimizableMetadata.structure must not contain writable instructions")

object OptimizableMetadata:
  /** Capture a layout's read-only structure, stripping its writable instructions. */
  def from(layout: SignatureLayout, moduleName: String): OptimizableMetadata =
    OptimizableMetadata(layout.withInstructions(None), moduleName)

/** A non-executable snapshot combining read-only metadata with current optimizable parameters. */
final case class OptimizableView(metadata: OptimizableMetadata, parameters: OptimizableParameters) derives CanEqual:
  /** The current effective layout, reconstructed from the read-only structure and writable instructions. */
  def layout: SignatureLayout = metadata.structure.withInstructions(parameters.instructions)

  /** The lifecycle/module name exposed by the underlying executable predictor. */
  def moduleName: String = metadata.moduleName
