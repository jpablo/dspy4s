package dspy4s.evaluate.metrics

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import munit.FunSuite
import zio.blocks.schema.DynamicValue

class AutoEvaluationSuite extends FunSuite:
  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)

  /** A dummy LM that does nothing useful — the scripted adapter produces the canned output regardless. */
  private object DummyLm extends LanguageModel:
    override val id: String = "dummy-judge-lm"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = ""))))

  /** A scripted adapter: ignores the prompt and emits the supplied output fields (e.g. recall/precision)
    * verbatim as the parsed record, so the judge sub-program's outputs are deterministic and offline. */
  private final class ScriptedAdapter(fields: Map[String, DynamicValue]) extends Adapter:
    override val name: String = "scripted-adapter"
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("judge")))))
    override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
      Right(ParsedOutput(values = DynamicValues.recordFromEntries(fields.toSeq)))

  private def runWith[A](fields: Map[String, DynamicValue])(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.resetForTests()
    try
      RuntimeEnvironment.withSettings(
        RuntimeContext(lm = Some(DummyLm), adapter = Some(new ScriptedAdapter(fields)))
      ) {
        given RuntimeContext = RuntimeEnvironment.current
        body
      }
    finally RuntimeEnvironment.resetForTests()

  private def example(question: String, response: String): Example =
    Example(rec("question" := question, "response" := response)).withInputs(Set("question"))

  private def prediction(response: String): DynamicPrediction =
    DynamicPrediction(rec("response" := response))

  // f1_score(precision=0.5, recall=1.0) = 2*0.5*1.0/(0.5+1.0) = 1/1.5 ≈ 0.6667
  test("f1_score helper computes the clamped harmonic mean") {
    assert(math.abs(AutoEvaluation.f1Score(0.5, 1.0) - (1.0 / 1.5)) < 1e-9)
    assertEquals(AutoEvaluation.f1Score(0.0, 1.0), 0.0)
    assertEquals(AutoEvaluation.f1Score(0.5, 0.0), 0.0)
    // clamps out-of-range inputs to [0, 1]
    assertEquals(AutoEvaluation.f1Score(2.0, 2.0), 1.0)
    assertEquals(AutoEvaluation.f1Score(-1.0, 0.5), 0.0)
  }

  test("SemanticF1 returns f1_score(precision, recall) from the judged outputs") {
    val metric = SemanticF1()
    val ex = example("What is the capital of France?", "Paris is the capital of France.")
    val pred = prediction("The capital is Paris.")
    val result = runWith(Map("recall" := 1.0, "precision" := 0.5)) {
      metric.score(ex, pred)
    }
    assert(result.isRight, s"expected Right, got $result")
    assert(math.abs(result.toOption.get - (1.0 / 1.5)) < 1e-9, s"got ${result.toOption.get}")
  }

  test("SemanticF1 returns 0.0 when precision is 0") {
    val metric = SemanticF1()
    val ex = example("Q?", "ground truth")
    val pred = prediction("system response")
    val result = runWith(Map("recall" := 1.0, "precision" := 0.0)) {
      metric.score(ex, pred)
    }
    assertEquals(result, Right(0.0))
  }

  test("SemanticF1 returns Left when a judged score does not parse as a number") {
    val metric = SemanticF1()
    val ex = example("Q?", "ground truth")
    val pred = prediction("system response")
    val result = runWith(Map("recall" := "not-a-number", "precision" := 0.5)) {
      metric.score(ex, pred)
    }
    assert(result.isLeft, s"expected Left for unparseable score, got $result")
  }

  test("CompleteAndGrounded combines completeness and groundedness via f1_score") {
    val metric = CompleteAndGrounded()
    val ex = example("What is the capital of France?", "Paris is the capital of France.")
    val pred = DynamicPrediction(
      rec("response" := "Paris is the capital.", "context" := "France's capital city is Paris.")
    )
    // completeness=1.0, groundedness=0.5 -> f1_score(0.5, 1.0) ≈ 0.6667
    val result = runWith(Map("completeness" := 1.0, "groundedness" := 0.5)) {
      metric.score(ex, pred)
    }
    assert(result.isRight, s"expected Right, got $result")
    assert(math.abs(result.toOption.get - (1.0 / 1.5)) < 1e-9, s"got ${result.toOption.get}")
  }
