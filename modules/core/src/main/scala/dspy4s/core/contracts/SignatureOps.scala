package dspy4s.core.contracts

import dspy4s.algebra.{IsEq, Law, <->}

/** The value-level "signature algebra": idempotent, cohort-aware, named transforms over [[SignatureLayout]].
  *
  * The low-level `withInputFields` / `withOutputFields` mutators on [[SignatureLayout]] stay `private[dspy4s]`; this
  * object names the augmentations composite programs actually perform, guarantees their idempotence, and gives them
  * laws, so `ChainOfThought` / `ReAct` / `MultiChainComparison` stop hand-rolling layout surgery. Kept
  * `private[dspy4s]` for the same reason as the mutators: user code shapes I/O through the `Signature` API, not by
  * editing a layout.
  *
  * '''Instructions.''' A combinator that changes the fields keeps the effective instructions of the layout that it
  * changes ([[SignatureLayout.effectiveInstructions]]). Upstream computes the default instructions when it makes a
  * signature, and `Signature.append`, `prepend`, and `insert` keep them (`dspy/signatures/signature.py`). Thus a
  * `ChainOfThought` over `question -> answer` keeps the objective "Given the fields `question`, produce the fields
  * `answer`.", and the objective does not name `reasoning`.
  */
private[dspy4s] object SignatureOps:

  extension (layout: SignatureLayout)

    /** Prepend `field` at the head of the output cohort, unless a field of the same name already exists (idempotent).
      */
    def prependOutput(field: FieldSpec): SignatureLayout =
      if layout.fields.exists(_.name == field.name) then layout
      else keepInstructions(layout.withOutputFields(field +: layout.outputFields))

    /** Append `field` to the end of the input cohort, unless a field of the same name already exists (idempotent). */
    def appendInput(field: FieldSpec): SignatureLayout =
      if layout.fields.exists(_.name == field.name) then layout
      else keepInstructions(layout.withInputFields(layout.inputFields :+ field))

    /** Keep the inputs, replace every output field with `fields`. The loop-step signature of `ReAct` uses this to drop
      * the base outputs (which its extractor produces) in favor of the per-iteration control outputs.
      */
    def replaceOutputs(fields: Vector[FieldSpec]): SignatureLayout =
      keepInstructions(layout.withOutputFields(fields))

    /** `changed` with the effective instructions of `layout`, the layout before the change. */
    private def keepInstructions(changed: SignatureLayout): SignatureLayout =
      changed.withInstructions(Some(layout.effectiveInstructions))

  /** The signature-algebra laws stated ON the structure as `@Law` methods returning [[IsEq]] (the math-with-scala
    * statement style; see `core.algebra.Laws` and `docs/refactor/algebra.md`). `SignatureOpsLawSuite` EXECUTES these
    * over generated layouts, checking layout equations by their public cohort observations (`in` / `out` /
    * `instructions` / `name`) and field-cohort equations by `sameElements`. The equations are the contract; the suite
    * is how they run.
    *
    * Cohort membership is structural, so these laws are total over every [[FieldSpec]].
    */
  private[dspy4s] object laws:

    // L1: cohort isolation: each combinator touches exactly one cohort.
    @Law("L1a cohort isolation: prependOutput preserves the inputs")
    def prependOutputKeepsInputs(s: SignatureLayout, f: FieldSpec): IsEq[Vector[FieldSpec]] =
      s.prependOutput(f).inputFields <-> s.inputFields

    @Law("L1b cohort isolation: appendInput preserves the outputs")
    def appendInputKeepsOutputs(s: SignatureLayout, g: FieldSpec): IsEq[Vector[FieldSpec]] =
      s.appendInput(g).outputFields <-> s.outputFields

    // L2: idempotence by name.
    @Law("L2a prependOutput is idempotent")
    def prependOutputIdempotent(s: SignatureLayout, f: FieldSpec): IsEq[SignatureLayout] =
      s.prependOutput(f).prependOutput(f) <-> s.prependOutput(f)

    @Law("L2b appendInput is idempotent")
    def appendInputIdempotent(s: SignatureLayout, g: FieldSpec): IsEq[SignatureLayout] =
      s.appendInput(g).appendInput(g) <-> s.appendInput(g)

    // L3: the input and output combinators commute (disjoint cohorts).
    @Law("L3 appendInput and prependOutput commute")
    def crossCohortCommute(s: SignatureLayout, g: FieldSpec, f: FieldSpec): IsEq[SignatureLayout] =
      s.appendInput(g).prependOutput(f) <-> s.prependOutput(f).appendInput(g)

    // L4: replaceOutputs absorbs any prior output edit, sets the outputs, and preserves the inputs.
    @Law("L4a replaceOutputs absorbs a prior prependOutput")
    def replaceOutputsAbsorbs(s: SignatureLayout, g: FieldSpec, fs: Vector[FieldSpec]): IsEq[SignatureLayout] =
      s.prependOutput(g).replaceOutputs(fs) <-> s.replaceOutputs(fs)

    @Law("L4b replaceOutputs sets the outputs")
    def replaceOutputsSetsOutputs(s: SignatureLayout, fs: Vector[FieldSpec]): IsEq[Vector[FieldSpec]] =
      s.replaceOutputs(fs).outputFields <-> fs

    @Law("L4c replaceOutputs keeps the inputs")
    def replaceOutputsKeepsInputs(s: SignatureLayout, fs: Vector[FieldSpec]): IsEq[Vector[FieldSpec]] =
      s.replaceOutputs(fs).inputFields <-> s.inputFields

    // L5: the precise effect of prependOutput on the output cohort.
    @Law("L5 prependOutput adds f at the head unless its name is already present in the layout")
    def prependOutputEffect(s: SignatureLayout, f: FieldSpec): IsEq[Vector[FieldSpec]] =
      s.prependOutput(f).outputFields <->
        (if s.fields.exists(_.name == f.name) then s.outputFields else f +: s.outputFields)

    // L6: instructions: last write wins.
    @Law("L6 withInstructions: the last write wins")
    def instructionsLastWriteWins(s: SignatureLayout, a: String, b: String): IsEq[SignatureLayout] =
      s.withInstructions(Some(b)).withInstructions(Some(a)) <-> s.withInstructions(Some(a))

    // L7: a field combinator keeps the effective instructions (upstream `append`, `prepend`, and `insert`).
    @Law("L7a prependOutput keeps the effective instructions")
    def prependOutputKeepsInstructions(s: SignatureLayout, f: FieldSpec): IsEq[String] =
      s.prependOutput(f).effectiveInstructions <-> s.effectiveInstructions

    @Law("L7b appendInput keeps the effective instructions")
    def appendInputKeepsInstructions(s: SignatureLayout, g: FieldSpec): IsEq[String] =
      s.appendInput(g).effectiveInstructions <-> s.effectiveInstructions

    @Law("L7c replaceOutputs keeps the effective instructions")
    def replaceOutputsKeepsInstructions(s: SignatureLayout, fs: Vector[FieldSpec]): IsEq[String] =
      s.replaceOutputs(fs).effectiveInstructions <-> s.effectiveInstructions
