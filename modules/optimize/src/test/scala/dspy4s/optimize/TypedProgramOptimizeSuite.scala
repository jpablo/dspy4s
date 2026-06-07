package dspy4s.optimize

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, FormattedPrompt, ParsedOutput}
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.{DspyError, DynamicPrediction, DynamicValues, Example, RuntimeContext, SignatureLayout}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, LmUsage, Message, MessageRole}
import dspy4s.programs.Predict
import dspy4s.typed.Signature
import munit.FunSuite
import zio.blocks.schema.Schema

// Top-level fixtures (Mirror / Schema derivation requires top-level types).
final case class QAInput(question: String) derives Schema
final case class QAOutput(answer: String) derives Schema

/** A small USER composite of two typed `Predict` programs. It is a plain `case class` of typed programs:
  *   - `Predictors[TwoStageQA]` is structurally derived (each `Predict` field resolves to the `predictPredictor`
  *     leaf, so the composite reads as 2 predictors and replaces them positionally);
  *   - it supplies its OWN `Runnable` (a one-liner) because a bare composite does not expose a signature.
  * This is the worked example of how user composites participate in the unified optimize spine. */
final case class TwoStageQA(
    classify: Predict[QAInput, QAOutput],
    answer:   Predict[QAInput, QAOutput]
)

object TwoStageQA:
  /** User-supplied Runnable: decode inputs once via the first stage's signature, run both stages in
    * sequence, and return the second stage's raw prediction. Real composites would thread intermediate
    * outputs; here the two stages share the QA signature, which is enough to exercise the spine. */
  given Runnable[TwoStageQA] with
    def run(program: TwoStageQA, inputs: zio.blocks.schema.DynamicValue.Record)(using RuntimeContext)
        : Either[DspyError, DynamicPrediction] =
      for
        i  <- program.classify.signature.inputShape.decode(inputs)
        _  <- program.classify.apply(i)
        p2 <- program.answer.apply(i)
      yield p2.raw

class TypedProgramOptimizeSuite extends FunSuite:

  // ── Offline scripted LM + adapter ─────────────────────────────────────────

  private object EchoAdapter extends Adapter:
    override val name: String = "echo"
    override def format(invocation: AdapterInvocation)(using RuntimeContext)
        : Either[DspyError, FormattedPrompt] =
      val q = DynamicValues.recordGet(invocation.inputs.values, "question").map(DynamicValues.renderText).getOrElse("")
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some(q)))))
    override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext)
        : Either[DspyError, ParsedOutput] =
      Right(ParsedOutput(values = rec("answer" := output.text)))

  /** Answers each question with the answer scripted for it (defaults to "unknown"). */
  private final class ScriptedLm(answers: Map[String, String]) extends LanguageModel:
    override val id: String   = "scripted-lm"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val q = request.messages.lastOption.flatMap(_.text).getOrElse("")
      Right(LmResponse(
        outputs = Vector(LmOutput(text = answers.getOrElse(q, "unknown"))),
        usage   = Some(LmUsage(totalTokens = 1, promptTokens = 1, completionTokens = 0))
      ))

  private def settings(answers: Map[String, String]): RuntimeContext =
    RuntimeContext(lm = Some(new ScriptedLm(answers)), adapter = Some(EchoAdapter))

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  private val sig = Signature.derived[QAInput, QAOutput]("QA")

  private val trainset = Vector(
    Example(rec("question" := "q1", "answer" := "a1"), inputKeys = Set("question")),
    Example(rec("question" := "q2", "answer" := "a2"), inputKeys = Set("question")),
    Example(rec("question" := "q3", "answer" := "a3"), inputKeys = Set("question"))
  )

  // ── 1. Bootstrap over a typed Predict[I, O] student ───────────────────────

  test("BootstrapFewShot optimizes a TYPED Predict[I, O] via the Runnable spine") {
    val student = Predict[QAInput, QAOutput](sig)
    // Teacher (== student here) answers each training question correctly, so every example bootstraps.
    val answers = Map("q1" -> "a1", "q2" -> "a2", "q3" -> "a3")
    val optimizer = new BootstrapFewShot[Predict[QAInput, QAOutput]](
      BootstrapFewShotConfig(
        metric = Some(new dspy4s.evaluate.metrics.ExactMatch(answerField = "answer")),
        maxBootstrappedDemos = 3,
        maxLabeledDemos = 0
      )
    )
    RuntimeEnvironment.withSettings(settings(answers)) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = optimizer.compile(student, trainset)
      assert(result.isRight, s"compile failed: ${result.left.toOption}")
      val best = result.toOption.get.bestProgram
      // Demos were attached to the typed Predict (predictPredictor.set is demos-only).
      assertEquals(best.demos.size, 3)
      assert(best.demos.forall(_.augmented), "all bootstrapped demos should be augmented")
    }
  }

  // ── 2. LabeledFewShot over a user composite of typed programs ─────────────

  test("LabeledFewShot attaches demos to BOTH predictors of a user composite (derived Predictors)") {
    val student = TwoStageQA(
      classify = Predict[QAInput, QAOutput](sig, name = Some("classify")),
      answer   = Predict[QAInput, QAOutput](sig, name = Some("answer"))
    )
    val optimizer = LabeledFewShot[TwoStageQA](LabeledFewShotConfig(k = 2, seed = 7L))
    RuntimeEnvironment.withSettings(settings(Map.empty)) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = optimizer.compile(student, trainset)
      assert(result.isRight, s"compile failed: ${result.left.toOption}")
      val best = result.toOption.get.bestProgram
      // Predictors derivation reads 2 predictors; replace writes the same 2 demos to each Predict leaf.
      assertEquals(best.classify.demos.size, 2)
      assertEquals(best.answer.demos.size, 2)
      // Round-trip sanity: read after replace still yields exactly 2 predictors.
      assertEquals(summon[Predictors[TwoStageQA]].read(best).size, 2)
    }
  }

  // ── 3. End-to-end: bootstrap a composite, then run it offline ─────────────

  test("BootstrapFewShot over a user composite runs the spine and produces a runnable program") {
    val student = TwoStageQA(
      classify = Predict[QAInput, QAOutput](sig, name = Some("classify")),
      answer   = Predict[QAInput, QAOutput](sig, name = Some("answer"))
    )
    val answers = Map("q1" -> "a1", "q2" -> "a2", "q3" -> "a3")
    val optimizer = new BootstrapFewShot[TwoStageQA](
      BootstrapFewShotConfig(
        metric = Some(new dspy4s.evaluate.metrics.ExactMatch(answerField = "answer")),
        maxBootstrappedDemos = 2,
        maxLabeledDemos = 0
      )
    )
    RuntimeEnvironment.withSettings(settings(answers)) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = optimizer.compile(student, trainset)
      assert(result.isRight, s"compile failed: ${result.left.toOption}")
      val best = result.toOption.get.bestProgram
      assertEquals(best.classify.demos.size, 2)
      // The compiled composite is still runnable end-to-end through its Runnable.
      val ran = summon[Runnable[TwoStageQA]].run(best, rec("question" := "q1"))
      assert(ran.isRight, s"run failed: ${ran.left.toOption}")
      assertEquals(DynamicValues.recordGet(ran.toOption.get.values, "answer").map(DynamicValues.renderText), Some("a1"))
    }
  }
