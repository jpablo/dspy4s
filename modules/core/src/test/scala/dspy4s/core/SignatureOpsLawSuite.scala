package dspy4s.core

import dspy4s.core.contracts.{FieldRole, FieldSpec, SignatureLayout}
import dspy4s.core.contracts.SignatureOps.*
import org.scalacheck.{Gen, Prop}

/** The laws of the signature algebra (see docs/refactor/algebra.md), as ScalaCheck properties. These are
  * the executable form of the spec: each `prependOutput` / `appendInput` / `replaceOutputs` /
  * `withInstructions` equation, checked over random layouts. The example-based `SignatureOpsSuite` stays as
  * readable documentation; this suite is the contract. */
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

  // Observational equality: two layouts are equal iff no observation (in / out / instructions / name) can
  // tell them apart. NOT structural `==` (cross-cohort commutativity reorders the underlying field vector
  // while leaving every observation identical). `sameElements` keeps strict-equality off the call site.
  private def obsEq(a: SignatureLayout, b: SignatureLayout): Boolean =
    a.inputFields.sameElements(b.inputFields) &&
      a.outputFields.sameElements(b.outputFields) &&
      a.instructions.iterator.sameElements(b.instructions.iterator) &&
      a.name == b.name

  // L1 — cohort isolation: each combinator touches exactly one cohort.
  property("L1a prependOutput preserves the inputs") {
    Prop.forAll(genLayout, genOutField) { (s, f) =>
      s.prependOutput(f).inputFields.sameElements(s.inputFields)
    }
  }
  property("L1b appendInput preserves the outputs") {
    Prop.forAll(genLayout, genInField) { (s, g) =>
      s.appendInput(g).outputFields.sameElements(s.outputFields)
    }
  }

  // L2 — idempotence by name.
  property("L2a prependOutput is idempotent") {
    Prop.forAll(genLayout, genOutField) { (s, f) =>
      obsEq(s.prependOutput(f).prependOutput(f), s.prependOutput(f))
    }
  }
  property("L2b appendInput is idempotent") {
    Prop.forAll(genLayout, genInField) { (s, g) =>
      obsEq(s.appendInput(g).appendInput(g), s.appendInput(g))
    }
  }

  // L3 — input and output combinators commute (they act on disjoint cohorts).
  property("L3 appendInput and prependOutput commute") {
    Prop.forAll(genLayout, genInField, genOutField) { (s, g, f) =>
      obsEq(s.appendInput(g).prependOutput(f), s.prependOutput(f).appendInput(g))
    }
  }

  // L4 — replaceOutputs absorbs any prior output edit, sets the outputs, and preserves the inputs.
  property("L4a replaceOutputs absorbs a prior prependOutput") {
    Prop.forAll(genLayout, genOutField, genOutFields) { (s, g, fs) =>
      obsEq(s.prependOutput(g).replaceOutputs(fs), s.replaceOutputs(fs))
    }
  }
  property("L4b replaceOutputs sets the outputs and keeps the inputs") {
    Prop.forAll(genLayout, genOutFields) { (s, fs) =>
      val r = s.replaceOutputs(fs)
      r.outputFields.sameElements(fs) && r.inputFields.sameElements(s.inputFields)
    }
  }

  // L5 — the precise effect of prependOutput on the output cohort.
  property("L5 prependOutput adds f at the head unless its name is already present") {
    Prop.forAll(genLayout, genOutField) { (s, f) =>
      val expected = if s.outputFields.exists(_.name == f.name) then s.outputFields else f +: s.outputFields
      s.prependOutput(f).outputFields.sameElements(expected)
    }
  }

  // L6 — instructions: last write wins.
  property("L6 withInstructions: the last write wins") {
    Prop.forAll(genLayout, genInstr, genInstr) { (s, a, b) =>
      obsEq(s.withInstructions(Some(b)).withInstructions(Some(a)), s.withInstructions(Some(a)))
    }
  }
