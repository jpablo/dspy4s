package dspy4s.core

import dspy4s.core.contracts.{FieldRole, FieldSpec, IsEq, SignatureLayout}
import dspy4s.core.contracts.SignatureOps.laws
import org.scalacheck.{Gen, Prop}

/** The laws of the signature algebra (see docs/refactor/algebra.md). The equations now live ON the structure
  * as `@Law` statements ([[dspy4s.core.contracts.SignatureOps.laws]]); this suite EXECUTES those statements
  * over random layouts, checking each under the honest observation. The example-based `SignatureOpsSuite`
  * stays as readable documentation; this suite is the contract. */
class SignatureOpsLawSuite extends munit.ScalaCheckSuite:

  // ── Generators (built via the public constructor; small, OVERLAPPING name pools so the dedup /
  //    idempotence paths are actually exercised). Input and output pools are disjoint so the layout's
  //    unique-name invariant always holds. ──────────────────────────────────────────────────────────────
  private val outputPool = List("x", "y", "z")

  private val genLayout: Gen[SignatureLayout] =
    for
      extraIns <- Gen.someOf(List("b", "c"))
      outs     <- Gen.someOf(outputPool)
    yield
      val ins    = ("a" +: extraIns.toVector).distinct
      val fields = ins.map(FieldSpec(_, FieldRole.Input)) ++ outs.toVector.distinct.map(FieldSpec(_, FieldRole.Output))
      SignatureLayout.create("test", fields).fold(e => throw new IllegalStateException(e.message), identity)

  // Transform fields draw from pools that OVERLAP the layout's (so a prepended/appended name is sometimes
  // already present -> the idempotent / dedup branch is hit) and sometimes fresh ("r" / "n").
  private val genOutField: Gen[FieldSpec]          = Gen.oneOf("x", "y", "r").map(FieldSpec(_, FieldRole.Output))
  private val genInField: Gen[FieldSpec]           = Gen.oneOf("a", "b", "n").map(FieldSpec(_, FieldRole.Input))
  private val genOutFields: Gen[Vector[FieldSpec]] =
    Gen.someOf(outputPool).map(_.toVector.distinct.map(FieldSpec(_, FieldRole.Output)))
  private val genInstr: Gen[String]                = Gen.oneOf("inst-1", "inst-2", "inst-3")

  // ── The two honest observations the @Law statements are checked under ────────────────────────────────────

  // Observational equality of layouts: equal iff no observation (in / out / instructions / name) can tell
  // them apart. NOT structural `==` (cross-cohort commutativity reorders the underlying field vector while
  // leaving every observation identical). `sameElements` keeps strict-equality off the call site.
  private def obsEq(a: SignatureLayout, b: SignatureLayout): Boolean =
    a.inputFields.sameElements(b.inputFields) &&
      a.outputFields.sameElements(b.outputFields) &&
      a.instructions.iterator.sameElements(b.instructions.iterator) &&
      a.name == b.name

  /** Execute a stated layout equation under observational equality. */
  private def holdsLayout(eq: IsEq[SignatureLayout]): Boolean = obsEq(eq.lhs, eq.rhs)

  /** Execute a stated field-cohort equation under `sameElements`. */
  private def holdsFields(eq: IsEq[Vector[FieldSpec]]): Boolean = eq.lhs.sameElements(eq.rhs)

  // Each property runs the corresponding @Law statement from SignatureOps.laws over generated inputs.

  property("L1a prependOutput preserves the inputs") {
    Prop.forAll(genLayout, genOutField)((s, f) => holdsFields(laws.prependOutputKeepsInputs(s, f)))
  }
  property("L1b appendInput preserves the outputs") {
    Prop.forAll(genLayout, genInField)((s, g) => holdsFields(laws.appendInputKeepsOutputs(s, g)))
  }
  property("L2a prependOutput is idempotent") {
    Prop.forAll(genLayout, genOutField)((s, f) => holdsLayout(laws.prependOutputIdempotent(s, f)))
  }
  property("L2b appendInput is idempotent") {
    Prop.forAll(genLayout, genInField)((s, g) => holdsLayout(laws.appendInputIdempotent(s, g)))
  }
  property("L3 appendInput and prependOutput commute") {
    Prop.forAll(genLayout, genInField, genOutField)((s, g, f) => holdsLayout(laws.crossCohortCommute(s, g, f)))
  }
  property("L4a replaceOutputs absorbs a prior prependOutput") {
    Prop.forAll(genLayout, genOutField, genOutFields)((s, g, fs) => holdsLayout(laws.replaceOutputsAbsorbs(s, g, fs)))
  }
  property("L4b replaceOutputs sets the outputs") {
    Prop.forAll(genLayout, genOutFields)((s, fs) => holdsFields(laws.replaceOutputsSetsOutputs(s, fs)))
  }
  property("L4c replaceOutputs keeps the inputs") {
    Prop.forAll(genLayout, genOutFields)((s, fs) => holdsFields(laws.replaceOutputsKeepsInputs(s, fs)))
  }
  property("L5 prependOutput adds f at the head unless its name is already present") {
    Prop.forAll(genLayout, genOutField)((s, f) => holdsFields(laws.prependOutputEffect(s, f)))
  }
  property("L6 withInstructions: the last write wins") {
    Prop.forAll(genLayout, genInstr, genInstr)((s, a, b) => holdsLayout(laws.instructionsLastWriteWins(s, a, b)))
  }
