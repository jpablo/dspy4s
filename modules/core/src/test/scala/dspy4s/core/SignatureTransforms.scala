package dspy4s.core

import dspy4s.algebra.{IsEq, Law, Monoid, <->}
import dspy4s.core.contracts.{FieldSpec, SignatureLayout}
import dspy4s.core.contracts.SignatureOps.*

// ── The two monoids of field changes, with their Monoid instances ───────────────────────────────────────────
// The values of the signature algebra are functions from a layout to a layout (`SignatureLayout => SignatureLayout`).
// They are not layouts, because layouts do not make a monoid. The field changes have two parts, one for each cohort:
// `InputTransform` changes the inputs, and `OutputTransform` changes the outputs. Each part is a monoid under function
// composition. The two parts commute, because they change different cohorts (laws L1 and L3).
// Each type wraps a function and has a `Monoid` instance. `SignatureOpsLawSuite` compares two functions by their
// outputs. It does not use `==` on the functions. Only the tests use these types, so they are in the test sources.

/** The monoid of the output changes: `prependOutput` (idempotent by name) and `replaceOutputs` (left-absorbing) under
  * composition. The identity is the change that does nothing. Apply a change with [[runOn]].
  */
final case class OutputTransform(runOn: SignatureLayout => SignatureLayout)

object OutputTransform:

  /** Generator: prepend an output field (idempotent by name). */
  def prepend(field: FieldSpec): OutputTransform = OutputTransform(_.prependOutput(field))

  /** Generator: replace all output fields (left-absorbing). */
  def replace(fields: Vector[FieldSpec]): OutputTransform = OutputTransform(_.replaceOutputs(fields))

  given monoid: Monoid[OutputTransform] with
    def empty: OutputTransform = OutputTransform(identity)
    extension (a: OutputTransform)
      infix def combine(b: OutputTransform): OutputTransform = OutputTransform(a.runOn.andThen(b.runOn))

/** The monoid of the input changes: `appendInput` (idempotent by name) under composition. The identity is the change
  * that does nothing. Apply a change with [[runOn]].
  */
final case class InputTransform(runOn: SignatureLayout => SignatureLayout)

object InputTransform:

  /** Generator: append an input field (idempotent by name). */
  def append(field: FieldSpec): InputTransform = InputTransform(_.appendInput(field))

  given monoid: Monoid[InputTransform] with
    def empty: InputTransform = InputTransform(identity)
    extension (a: InputTransform)
      infix def combine(b: InputTransform): InputTransform = InputTransform(a.runOn.andThen(b.runOn))

/** The law between the two monoids. It is not a law of one monoid, so it is not on `Monoid`. The two monoids commute,
  * because they change different cohorts. At the level of the wrapper types, it is the law
  * `SignatureOps.laws.crossCohortCommute`.
  */
object SignatureTransformLaws:
  @Law("the input and output cohort submonoids commute")
  def submonoidsCommute(i: InputTransform, o: OutputTransform, s: SignatureLayout): IsEq[SignatureLayout] =
    o.runOn(i.runOn(s)) <-> i.runOn(o.runOn(s))
