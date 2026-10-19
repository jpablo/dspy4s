package dspy4s.adapters

import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.:=
import dspy4s.lm.contracts.LmRequest
import munit.FunSuite

class AdapterConstraintsSuite extends FunSuite:

  private def layout(constrained: Boolean): SignatureLayout =
    SignatureLayout.create(
      name = "S",
      inputFields = Vector(FieldSpec("question")),
      outputFields = Vector(
        FieldSpec(
          name = "age",
          typeRef = TypeRef.int,
          constraints = if constrained then Vector(dspy4s.core.contracts.FieldConstraints.gt(0)) else Vector.empty
        )
      )
    ).toOption.get

  private def invocation(constrained: Boolean): AdapterInvocation =
    AdapterInvocation(
      layout = layout(constrained),
      demos = Vector.empty,
      inputs = Example(values = DynamicValues.record("question" := "q"), inputKeys = Set("question")),
      request = LmRequest(model = "x")
    )

  private def systemText(prompt: dspy4s.adapters.contracts.FormattedPrompt): String =
    prompt.messages.headOption.flatMap(_.text).getOrElse("")

  private val adapters: Vector[(String, dspy4s.adapters.contracts.Adapter)] =
    Vector("ChatAdapter" -> ChatAdapter(), "JSONAdapter" -> JSONAdapter(), "XMLAdapter" -> XMLAdapter())

  // Upstream `get_field_description_string`: the constraints are on the line after the field, in each adapter.
  test("each adapter shows the constraints of a field on the line after the field") {
    given RuntimeContext = RuntimeContext()
    adapters.foreach { (label, adapter) =>
      val sys = systemText(adapter.format(invocation(constrained = true)).toOption.get)
      assert(sys.contains("Your output fields are:\n1. `age` (int): \nConstraints: greater than: 0\n"), s"$label: $sys")
      assert(!sys.contains("Field constraints:"), s"$label: $sys")
    }
  }

  test("an unconstrained field has no constraints line") {
    given RuntimeContext = RuntimeContext()
    adapters.foreach { (label, adapter) =>
      val plain = systemText(adapter.format(invocation(constrained = false)).toOption.get)
      assert(!plain.contains("Constraints:"), s"$label: $plain")
    }
  }
