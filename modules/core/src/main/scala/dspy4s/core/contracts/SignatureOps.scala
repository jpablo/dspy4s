package dspy4s.core.contracts

import dspy4s.core.algebra.{IsEq, Law, Monoid, <->}

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
      *
      * Requires an Output-role field: a wrong-role spec would silently land in the wrong cohort and break
      * L1a (cohort isolation), so the precondition fails fast instead. The laws are total over the guarded
      * carrier.
      */
    def prependOutput(field: FieldSpec): SignatureLayout =
      require(field.role == FieldRole.Output, s"prependOutput requires an Output-role field, got: $field")
      if layout.outputFields.exists(_.name == field.name) then layout
      else layout.prepend(field)

    /** Append `field` to the end of the input cohort, unless an input field of the same name already exists
      * (idempotent).
      *
      * Requires an Input-role field, for the same reason as `prependOutput` (a wrong role breaks L1b).
      */
    def appendInput(field: FieldSpec): SignatureLayout =
      require(field.role == FieldRole.Input, s"appendInput requires an Input-role field, got: $field")
      if layout.inputFields.exists(_.name == field.name) then layout
      else layout.append(field)

    /** Keep the inputs, replace every output field with `fields`. The loop-step signatures of `ReAct` and
      * `CodeAct` use this to drop the base outputs (which their extractor produces) in favor of the
      * per-iteration control outputs.
      *
      * Requires Output-role fields: an Input-role spec here would corrupt both cohorts at once (L4b/L4c).
      */
    def replaceOutputs(fields: Vector[FieldSpec]): SignatureLayout =
      require(
        fields.forall(_.role == FieldRole.Output),
        s"replaceOutputs requires Output-role fields, got: ${fields.filterNot(_.role == FieldRole.Output)}"
      )
      layout.withFields(layout.inputFields ++ fields)

  /** The signature-algebra laws stated ON the structure as `@Law` methods returning [[IsEq]] (the
    * math-with-scala statement style; see `core.algebra.Laws` and `docs/refactor/algebra.md`). `SignatureOpsLawSuite`
    * EXECUTES these over generated layouts, checking each under the honest observation: layout equations by
    * observational equality (`in` / `out` / `instructions` / `name`, since cross-cohort commutativity reorders
    * the underlying field vector while leaving every observation identical), field-cohort equations by
    * `sameElements`. The equations are the contract; the suite is how they run.
    *
    * Carrier note: the laws quantify over role-correct fields, and since the ops now `require` the role, that
    * subset is enforced at the operation rather than assumed by the suite's generators. A wrong-role field is
    * an `IllegalArgumentException` at the call site, not a silent law violation. */
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

// ── Algebra 1's two commuting submonoids, as explicit Monoid instances ──────────────────────────────────────
// The signature algebra's carrier is LAYOUT ENDOMORPHISMS (`SignatureLayout => SignatureLayout`), not layouts
// (layouts form no monoid). Its field-transform submonoid factors as InputTransform × OutputTransform — two
// commuting submonoids of End(SignatureLayout), one per cohort (L1 keeps them disjoint, L3 makes them commute).
// Each is wrapped as a newtype carrying a `Monoid` instance; laws hold up to OUTPUT-observational equality of
// the wrapped transform (the same discipline as `Mode` / `SignatureOpsLawSuite`), not `==` on the function.

/** The output-cohort endomorphism submonoid: `prependOutput` (idempotent by name) and `replaceOutputs`
  * (left-absorbing) under composition, identity = the no-op transform. Apply with [[runOn]]. */
private[dspy4s] final case class OutputTransform(runOn: SignatureLayout => SignatureLayout)

private[dspy4s] object OutputTransform:
  import SignatureOps.*
  /** Generator: prepend an output field (idempotent by name). */
  def prepend(field: FieldSpec): OutputTransform = OutputTransform(_.prependOutput(field))
  /** Generator: replace all output fields (left-absorbing). */
  def replace(fields: Vector[FieldSpec]): OutputTransform = OutputTransform(_.replaceOutputs(fields))

  given monoid: Monoid[OutputTransform] with
    def empty: OutputTransform = OutputTransform(identity)
    extension (a: OutputTransform)
      infix def combine(b: OutputTransform): OutputTransform = OutputTransform(a.runOn.andThen(b.runOn))

/** The input-cohort endomorphism submonoid: `appendInput` (idempotent by name) under composition, identity =
  * the no-op transform. Apply with [[runOn]]. */
private[dspy4s] final case class InputTransform(runOn: SignatureLayout => SignatureLayout)

private[dspy4s] object InputTransform:
  import SignatureOps.*
  /** Generator: append an input field (idempotent by name). */
  def append(field: FieldSpec): InputTransform = InputTransform(_.appendInput(field))

  given monoid: Monoid[InputTransform] with
    def empty: InputTransform = InputTransform(identity)
    extension (a: InputTransform)
      infix def combine(b: InputTransform): InputTransform = InputTransform(a.runOn.andThen(b.runOn))

/** The law RELATING the two submonoids (not a within-monoid law, so not on `Monoid`): they commute, because
  * they act on disjoint field cohorts (the direct-product factorization; = `SignatureOps.laws.crossCohortCommute`
  * at the newtype level). */
private[dspy4s] object SignatureTransformLaws:
  @Law("the input and output cohort submonoids commute")
  def submonoidsCommute(i: InputTransform, o: OutputTransform, s: SignatureLayout): IsEq[SignatureLayout] =
    o.runOn(i.runOn(s)) <-> i.runOn(o.runOn(s))
