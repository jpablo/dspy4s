package dspy4s.adapters

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, DelegatingAdapter}
import dspy4s.core.contracts.{:=, DynamicValues, FieldSpec, RuntimeContext, SignatureLayout}
import dspy4s.core.data.Example
import dspy4s.lm.contracts.{LmOutput, LmRequest}
import munit.FunSuite

/** A wrapper that overrides nothing behaves as its delegate on each member of [[Adapter]]. The test failed before the
  * change: the trait did not exist.
  */
class DelegatingAdapterSuite extends FunSuite:

  private given RuntimeContext = RuntimeContext()

  private final case class Wrapped(delegate: Adapter) extends DelegatingAdapter:
    override protected def wrap(next: Adapter): Adapter = Wrapped(next)

  private val layout = SignatureLayout.of(
    name = "Sig",
    inputFields = Vector(FieldSpec("question")),
    outputFields = Vector(FieldSpec("answer")),
    instructions = None
  )

  private val invocation = AdapterInvocation(
    layout = layout,
    demos = Vector.empty,
    inputs = Example(values = DynamicValues.record("question" := "q"), inputKeys = Set("question")),
    request = LmRequest(model = "m")
  )

  test("a wrapper gives the results of its delegate") {
    val inner   = XMLAdapter()
    val wrapper = Wrapped(inner)
    val output  = LmOutput(text = "<answer>Paris</answer>")
    val values  = DynamicValues.record("question" := "q")
    assertEquals(wrapper.name, inner.name)
    assertEquals(wrapper.format(invocation), inner.format(invocation))
    assertEquals(wrapper.parse(invocation, output), inner.parse(invocation, output))
    assertEquals(wrapper.parse(layout, output), inner.parse(layout, output))
    assertEquals(wrapper.formatUserMessageContent(layout, values), inner.formatUserMessageContent(layout, values))
  }

  test("a wrapper keeps the stream state of its delegate") {
    val state  = Wrapped(XMLAdapter()).streamingState(layout).getOrElse(fail("the wrapper has no stream state"))
    val chunks = state.receive("<answer>Paris</answer>") ++ state.finish()
    assertEquals(chunks.map(_.fieldName).distinct, Vector("answer"))
  }

  test("the fallback of a wrapper is the fallback of its delegate in the same wrapper") {
    Wrapped(XMLAdapter()).fallback match
      case Some(Wrapped(_: JSONAdapter)) => ()
      case other                         => fail(s"expected the JSON adapter in the wrapper, got $other")
    assertEquals(Wrapped(XMLAdapter(useJsonAdapterFallback = false)).fallback, None)
  }
