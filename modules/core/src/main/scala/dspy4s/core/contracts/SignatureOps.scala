package dspy4s.core.contracts

/** The value-level "signature algebra": idempotent, role-aware, named transforms over [[SignatureLayout]].
  *
  * The low-level `append` / `prepend` / `insert` / `withFields` mutators on [[SignatureLayout]] stay
  * `private[dspy4s]`; this object names the augmentations composite programs actually perform, guarantees
  * their idempotence, and gives them laws, so `ChainOfThought` / `ReAct` / `CodeAct` /
  * `MultiChainComparison` stop hand-rolling layout surgery. Kept `private[dspy4s]` for the same reason as
  * the mutators: user code shapes I/O at the typed `Signature` surface, not by editing a layout.
  */
private[dspy4s] object SignatureOps:

  extension (layout: SignatureLayout)

    /** Prepend `field` at the head of the output cohort, unless an output field of the same name already
      * exists (idempotent). For an output-role field this is the prior `ChainOfThought.augmentLayout`
      * (`insert(0, _)`) and the `MultiChainComparison` guarded `prepend`, generalized off the hard-coded
      * field: both reconstruct the layout as `inputs ++ (field +: outputs)`.
      */
    def prependOutput(field: FieldSpec): SignatureLayout =
      if layout.outputFields.exists(_.name == field.name) then layout
      else layout.prepend(field)

    /** Append `field` to the end of the input cohort, unless an input field of the same name already exists
      * (idempotent).
      */
    def appendInput(field: FieldSpec): SignatureLayout =
      if layout.inputFields.exists(_.name == field.name) then layout
      else layout.append(field)

    /** Keep the inputs, replace every output field with `fields`. The loop-step signatures of `ReAct` and
      * `CodeAct` use this to drop the base outputs (which their extractor produces) in favor of the
      * per-iteration control outputs.
      */
    def replaceOutputs(fields: Vector[FieldSpec]): SignatureLayout =
      layout.withFields(layout.inputFields ++ fields)
