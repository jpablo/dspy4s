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

  /** The signature-algebra laws stated ON the structure as `@Law` methods returning [[IsEq]] (the
    * math-with-scala statement style; see `core.contracts.Laws` and `docs/refactor/algebra.md`). `SignatureOpsLawSuite`
    * EXECUTES these over generated layouts, checking each under the honest observation: layout equations by
    * observational equality (`in` / `out` / `instructions` / `name`, since cross-cohort commutativity reorders
    * the underlying field vector while leaving every observation identical), field-cohort equations by
    * `sameElements`. The equations are the contract; the suite is how they run. */
  private[dspy4s] object laws:

    // L1 — cohort isolation: each combinator touches exactly one cohort.
    @Law("L1a cohort isolation: prependOutput preserves the inputs")
    def prependOutputKeepsInputs(s: SignatureLayout, f: FieldSpec): IsEq[Vector[FieldSpec]] =
      s.prependOutput(f).inputFields <-> s.inputFields

    @Law("L1b cohort isolation: appendInput preserves the outputs")
    def appendInputKeepsOutputs(s: SignatureLayout, g: FieldSpec): IsEq[Vector[FieldSpec]] =
      s.appendInput(g).outputFields <-> s.outputFields

    // L2 — idempotence by name.
    @Law("L2a prependOutput is idempotent")
    def prependOutputIdempotent(s: SignatureLayout, f: FieldSpec): IsEq[SignatureLayout] =
      s.prependOutput(f).prependOutput(f) <-> s.prependOutput(f)

    @Law("L2b appendInput is idempotent")
    def appendInputIdempotent(s: SignatureLayout, g: FieldSpec): IsEq[SignatureLayout] =
      s.appendInput(g).appendInput(g) <-> s.appendInput(g)

    // L3 — the input and output combinators commute (disjoint cohorts).
    @Law("L3 appendInput and prependOutput commute")
    def crossCohortCommute(s: SignatureLayout, g: FieldSpec, f: FieldSpec): IsEq[SignatureLayout] =
      s.appendInput(g).prependOutput(f) <-> s.prependOutput(f).appendInput(g)

    // L4 — replaceOutputs absorbs any prior output edit, sets the outputs, and preserves the inputs.
    @Law("L4a replaceOutputs absorbs a prior prependOutput")
    def replaceOutputsAbsorbs(s: SignatureLayout, g: FieldSpec, fs: Vector[FieldSpec]): IsEq[SignatureLayout] =
      s.prependOutput(g).replaceOutputs(fs) <-> s.replaceOutputs(fs)

    @Law("L4b replaceOutputs sets the outputs")
    def replaceOutputsSetsOutputs(s: SignatureLayout, fs: Vector[FieldSpec]): IsEq[Vector[FieldSpec]] =
      s.replaceOutputs(fs).outputFields <-> fs

    @Law("L4c replaceOutputs keeps the inputs")
    def replaceOutputsKeepsInputs(s: SignatureLayout, fs: Vector[FieldSpec]): IsEq[Vector[FieldSpec]] =
      s.replaceOutputs(fs).inputFields <-> s.inputFields

    // L5 — the precise effect of prependOutput on the output cohort.
    @Law("L5 prependOutput adds f at the head unless its name is already present")
    def prependOutputEffect(s: SignatureLayout, f: FieldSpec): IsEq[Vector[FieldSpec]] =
      s.prependOutput(f).outputFields <->
        (if s.outputFields.exists(_.name == f.name) then s.outputFields else f +: s.outputFields)

    // L6 — instructions: last write wins.
    @Law("L6 withInstructions: the last write wins")
    def instructionsLastWriteWins(s: SignatureLayout, a: String, b: String): IsEq[SignatureLayout] =
      s.withInstructions(Some(b)).withInstructions(Some(a)) <-> s.withInstructions(Some(a))
