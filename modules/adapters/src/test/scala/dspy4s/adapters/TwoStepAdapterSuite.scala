package dspy4s.adapters

import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.:=
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import munit.FunSuite

class TwoStepAdapterSuite extends FunSuite:

  /** Records the prompt it was called with and returns a fixed completion. */
  private final class ScriptedLm(response: String) extends LanguageModel:
    @volatile var lastPrompt: Option[String] = None
    override val id: String   = "extractor"
    override val mode: LmMode  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      lastPrompt = Some(request.messages.flatMap(_.text).mkString("\n"))
      Right(LmResponse(outputs = Vector(LmOutput(text = response))))

  private val layout: SignatureLayout =
    SignatureLayout.create(
      name = "QA",
      fields = Vector(FieldSpec("question", FieldRole.Input), FieldSpec("answer", FieldRole.Output))
    ).toOption.get

  test("TwoStepAdapter formats a plain natural-language prompt for the main LM (no structured markers)") {
    val adapter = TwoStepAdapter(new ScriptedLm(""))
    given RuntimeContext = RuntimeContext()

    val invocation = AdapterInvocation(
      layout = layout,
      demos  = Vector.empty,
      inputs = Example(values = DynamicValues.record("question" := "What is the capital of France?"), inputKeys = Set("question")),
      request = LmRequest(model = "main")
    )
    val text = adapter.format(invocation).toOption.get.messages.flatMap(_.text).mkString("\n")
    assert(text.contains("What is the capital of France?"), text)
    assert(text.contains("answer"), s"the field to be extracted should be named: $text")
    assert(!text.contains("[[ ##"), s"the main LM must NOT be asked for ChatAdapter markers: $text")
  }

  test("TwoStepAdapter extracts structured fields from a free-form completion via the extraction model") {
    // The extraction model receives a ChatAdapter `text -> answer` prompt and replies in marker format.
    val extractionLm = new ScriptedLm("[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]")
    val adapter = TwoStepAdapter(extractionLm)
    given RuntimeContext = RuntimeContext()

    val mainCompletion = "Well, after some thought, the capital of France is Paris."
    val parsed = adapter.parse(layout, LmOutput(text = mainCompletion)).toOption.get

    assertEquals(DynamicValues.recordGet(parsed.values, "answer").map(DynamicValues.renderText), Some("Paris"))
    // The extraction step was handed the main LM's free-form completion as its `text` input.
    assert(extractionLm.lastPrompt.exists(_.contains("capital of France is Paris")), extractionLm.lastPrompt.toString)
  }

  test("TwoStepAdapter surfaces an extraction-model failure") {
    val failing = new LanguageModel:
      override val id: String  = "x"
      override val mode: LmMode = LmMode.Chat
      override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
        Left(dspy4s.core.contracts.RuntimeError("extractor", "boom"))
    given RuntimeContext = RuntimeContext()
    val result = TwoStepAdapter(failing).parse(layout, LmOutput(text = "anything"))
    assert(result.isLeft)
    assertEquals(result.left.toOption.get.message, "boom")
  }
