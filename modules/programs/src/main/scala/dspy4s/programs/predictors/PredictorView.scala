package dspy4s.programs.predictors

import dspy4s.core.contracts.SignatureLayout

/** Read-only information about one predictor.
  *
  * [[structure]] contains the signature name and fields but never its instructions: instructions are writable
  * [[PredictorState]]. [[moduleName]] is the execution/lifecycle name. Neither field is accepted by
  * [[Predictors.replace]], so inspecting a predictor cannot accidentally turn architecture into optimizer state.
  */
final case class PredictorMetadata(structure: SignatureLayout, moduleName: String) derives CanEqual:
  require(structure.instructions.isEmpty, "PredictorMetadata.structure must not contain writable instructions")

object PredictorMetadata:
  /** Capture a layout's read-only structure, stripping its writable instructions. */
  def from(layout: SignatureLayout, moduleName: String): PredictorMetadata =
    PredictorMetadata(layout.withInstructions(None), moduleName)

/** A non-executable snapshot combining read-only metadata with current writable state. */
final case class PredictorView(metadata: PredictorMetadata, state: PredictorState) derives CanEqual:
  /** The current effective layout, reconstructed from the read-only structure and writable instructions. */
  def layout: SignatureLayout = metadata.structure.withInstructions(state.instructions)

  /** The lifecycle/module name exposed by the underlying executable predictor. */
  def moduleName: String = metadata.moduleName
