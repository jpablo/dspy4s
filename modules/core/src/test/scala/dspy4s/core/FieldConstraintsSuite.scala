package dspy4s.core

import dspy4s.core.contracts.FieldConstraints
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import munit.FunSuite

class FieldConstraintsSuite extends FunSuite:

  test("FieldConstraints helpers render the exact upstream strings") {
    assertEquals(FieldConstraints.gt(0).render, "greater than: 0")
    assertEquals(FieldConstraints.ge(1).render, "greater than or equal to: 1")
    assertEquals(FieldConstraints.lt(100).render, "less than: 100")
    assertEquals(FieldConstraints.le(10).render, "less than or equal to: 10")
    assertEquals(FieldConstraints.minLength(3).render, "minimum length: 3")
    assertEquals(FieldConstraints.maxLength(10).render, "maximum length: 10")
    assertEquals(FieldConstraints.multipleOf(5).render, "a multiple of the given number: 5")
  }

  test("FieldConstraints renders whole numbers without trailing .0 noise") {
    assertEquals(FieldConstraints.gt(0.0).render, "greater than: 0")
    assertEquals(FieldConstraints.lt(2.5).render, "less than: 2.5")
    assertEquals(FieldConstraints.multipleOf(0.25).render, "a multiple of the given number: 0.25")
  }

  test("FieldConstraints map to JSON-Schema keywords") {
    assertEquals(FieldConstraints.gt(0).schemaKeyword, "exclusiveMinimum")
    assertEquals(FieldConstraints.ge(1).schemaKeyword, "minimum")
    assertEquals(FieldConstraints.lt(100).schemaKeyword, "exclusiveMaximum")
    assertEquals(FieldConstraints.le(10).schemaKeyword, "maximum")
    assertEquals(FieldConstraints.minLength(3).schemaKeyword, "minLength")
    assertEquals(FieldConstraints.maxLength(10).schemaKeyword, "maxLength")
    assertEquals(FieldConstraints.multipleOf(5).schemaKeyword, "multipleOf")
  }

  test("FieldSpec carries constraints and normalize preserves them") {
    val field = FieldSpec(
      name        = "score",
      role        = FieldRole.Output,
      typeRef     = TypeRef.int,
      constraints = Vector(FieldConstraints.gt(0), FieldConstraints.maxLength(10))
    )
    assertEquals(field.constraints.map(_.render), Vector("greater than: 0", "maximum length: 10"))
    val normalized = FieldSpec.normalize(field)
    assertEquals(normalized.constraints.map(_.render), Vector("greater than: 0", "maximum length: 10"))
  }

  test("SignatureLayout dumpState/fromState round-trips a field's constraints") {
    val layout = SignatureLayout
      .create(
        name = "Constrained",
        fields = Vector(
          FieldSpec(name = "question", role = FieldRole.Input),
          FieldSpec(
            name        = "score",
            role        = FieldRole.Output,
            typeRef     = TypeRef.int,
            constraints = Vector(FieldConstraints.gt(0), FieldConstraints.maxLength(10))
          )
        )
      )
      .toOption
      .get

    val rebuilt = SignatureLayout.fromState(layout.dumpState).toOption.get
    val scoreField = rebuilt.outputFields.find(_.name == "score").get
    // Structured round-trip: the rebuilt constraints equal the originals (not just their rendering).
    assertEquals(scoreField.constraints, Vector(FieldConstraints.gt(0), FieldConstraints.maxLength(10)))
    assertEquals(scoreField.constraints.map(_.render), Vector("greater than: 0", "maximum length: 10"))

    // A field without constraints round-trips to an empty vector.
    val questionField = rebuilt.inputFields.find(_.name == "question").get
    assertEquals(questionField.constraints, Vector.empty[dspy4s.core.contracts.Constraint])
  }

  test("SignatureLayout fromState defaults constraints to empty when absent") {
    // dumpState from a layout with no constraints must read back as empty (not fail).
    val layout = SignatureLayout
      .create(
        name = "Plain",
        fields = Vector(
          FieldSpec(name = "question", role = FieldRole.Input),
          FieldSpec(name = "answer", role = FieldRole.Output)
        )
      )
      .toOption
      .get
    val rebuilt = SignatureLayout.fromState(layout.dumpState).toOption.get
    assert(rebuilt.fields.forall(_.constraints.isEmpty))
  }
