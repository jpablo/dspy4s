package dspy4s.programs.optimization

import dspy4s.core.contracts.SignatureLayout

/** Read-only information about one optimizable leaf.
  *
  * [[structure]] contains the signature name and fields but never its instructions: instructions are writable
  * [[OptimizableParameters]]. [[moduleName]] is the execution component name. Neither field is part of parameter
  * replacement, so optimization cannot turn architecture into writable state.
  */
final case class OptimizableMetadata(structure: SignatureLayout, moduleName: String) derives CanEqual:
  require(structure.instructions.isEmpty, "OptimizableMetadata.structure must not contain writable instructions")

object OptimizableMetadata:
  /** Capture a layout's read-only structure, stripping its writable instructions. */
  def from(layout: SignatureLayout, moduleName: String): OptimizableMetadata =
    OptimizableMetadata(layout.withInstructions(None), moduleName)
