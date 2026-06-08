package dspy4s.adapters

import dspy4s.adapters.contracts.AdapterConstraints
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.FieldRole
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
      fields = Vector(
        FieldSpec("question", FieldRole.Input),
        FieldSpec(
          name = "age",
          role = FieldRole.Output,
          typeRef = TypeRef.int,
          constraints = if constrained then Vector(dspy4s.core.contracts.FieldConstraints.gt(0)) else Vector.empty
        )
      )
    ).toOption.get

  private def invocation(constrained: Boolean): AdapterInvocation =
    AdapterInvocation(
      layout = layout(constrained),
      demos  = Vector.empty,
      inputs = Example(values = DynamicValues.record("question" := "q"), inputKeys = Set("question")),
      request = LmRequest(model = "x")
    )

  private def systemText(prompt: dspy4s.adapters.contracts.FormattedPrompt): String =
    prompt.messages.headOption.flatMap(_.text).getOrElse("")

  test("AdapterConstraints.block lists constrained fields, and is None when none are constrained") {
    assertEquals(AdapterConstraints.block(layout(constrained = false).outputFields), Option.empty[String])
    val block = AdapterConstraints.block(layout(constrained = true).outputFields).getOrElse(fail("expected a block"))
    assert(block.contains("Field constraints:"), block)
    assert(block.contains("`age`: greater than: 0"), block)
  }

  test("XMLAdapter appends the field-constraints block to its system instruction") {
    given RuntimeContext = RuntimeContext()
    val sys = systemText(XMLAdapter().format(invocation(constrained = true)).toOption.get)
    assert(sys.contains("Field constraints:") && sys.contains("greater than: 0"), sys)

    val plain = systemText(XMLAdapter().format(invocation(constrained = false)).toOption.get)
    assert(!plain.contains("Field constraints:"), plain)
  }

  test("JSONAdapter appends the field-constraints block to its system instruction") {
    given RuntimeContext = RuntimeContext()
    val sys = systemText(JSONAdapter().format(invocation(constrained = true)).toOption.get)
    assert(sys.contains("Field constraints:") && sys.contains("greater than: 0"), sys)

    val plain = systemText(JSONAdapter().format(invocation(constrained = false)).toOption.get)
    assert(!plain.contains("Field constraints:"), plain)
  }
