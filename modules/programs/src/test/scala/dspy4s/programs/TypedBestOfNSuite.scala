package dspy4s.programs

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
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.Prediction
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

class TypedBestOfNSuite extends FunSuite:

  private case class Q(q: String)
  private case class Cand(answer: String, score: Double)

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)

  /** A typed program stub returning scripted `Prediction[Cand]`s, tracking call count + the rolloutIds it saw. */
  private final class TypedStub(results: Vector[Either[DspyError, Prediction[Cand]]])
      extends Module[TypedCall[Q], Prediction[Cand]]:
    val rolloutIds: ArrayBuffer[Int] = ArrayBuffer.empty
    val calls: AtomicInteger         = AtomicInteger(0)
    private val counter              = AtomicInteger(0)
    override val moduleName: String  = "typed_stub"
    override protected def callInputs(call: TypedCall[Q]): DynamicValue.Record = DynamicValue.Record.empty
    override protected def callTraceEnabled(call: TypedCall[Q]): Boolean       = call.traceEnabled
    override protected def tracePayload(p: Prediction[Cand]): DynamicValue.Record = p.raw.values
    override protected def forward(call: TypedCall[Q])(using RuntimeContext): Either[DspyError, Prediction[Cand]] =
      calls.incrementAndGet()
      rolloutIds += call.rolloutId.getOrElse(-1)
      results(Math.min(counter.getAndIncrement(), results.size - 1))

  private def candidate(answer: String, score: Double): Prediction[Cand] =
    Prediction(output = Cand(answer, score), raw = DynamicPrediction(values = rec("answer" := answer, "score" := score)))

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  test("typed BestOfN returns the highest-reward typed prediction and threads rolloutIds") {
    val stub = TypedStub(Vector(
      Right(candidate("A", 0.1)),
      Right(candidate("B", 0.9)),
      Right(candidate("C", 0.5))
    ))
    val bestOfN = BestOfN[Q, Cand](
      module    = stub,
      n         = 3,
      rewardFn  = (_, pred) => pred.output.score,
      threshold = 1.0
    )

    given RuntimeContext = RuntimeEnvironment.current
    val result = bestOfN.apply(TypedCall(Q("x"), rolloutId = Some(7)))

    assert(result.isRight, s"expected success, got: $result")
    assertEquals(result.toOption.get.output, Cand("B", 0.9))
    assertEquals(stub.calls.get(), 3)
    assertEquals(stub.rolloutIds.toVector, Vector(7, 8, 9))
    // Winner's (propagated) trace entry, then BestOfN's own wrapped entry.
    assertEquals(RuntimeEnvironment.current.trace.size, 2)
    assertEquals(
      DynamicValues.recordToMap(RuntimeEnvironment.current.trace.head.outputs).get("answer"),
      Some("B": Any)
    )
  }

  test("typed BestOfN short-circuits once the reward reaches the threshold") {
    val stub = TypedStub(Vector(
      Right(candidate("A", 0.95)),   // >= threshold -> stop after the first attempt
      Right(candidate("B", 0.10))
    ))
    val bestOfN = BestOfN[Q, Cand](stub, n = 3, rewardFn = (_, p) => p.output.score, threshold = 0.9)

    given RuntimeContext = RuntimeEnvironment.current
    val result = bestOfN.apply(TypedCall(Q("x")))
    assertEquals(result.toOption.map(_.output), Some(Cand("A", 0.95)))
    assertEquals(stub.calls.get(), 1)
  }

  test("typed BestOfN surfaces the last error after exhausting the default fail budget") {
    val stub = TypedStub(Vector(
      Left(RuntimeError("typed_stub", "f1")),
      Left(RuntimeError("typed_stub", "f2")),
      Left(RuntimeError("typed_stub", "f3"))
    ))
    val bestOfN = BestOfN[Q, Cand](stub, n = 3, rewardFn = (_, _) => 1.0, threshold = 0.0)

    given RuntimeContext = RuntimeEnvironment.current
    val result = bestOfN.apply(TypedCall(Q("x")))
    assert(result.isLeft)
    assertEquals(result.left.toOption.get.message, "f3")
  }

  test("typed BestOfN with a custom fail count raises earlier") {
    val stub = TypedStub(Vector(
      Left(RuntimeError("typed_stub", "f1")),
      Left(RuntimeError("typed_stub", "f2")),
      Right(candidate("ok", 1.0))
    ))
    val bestOfN = BestOfN[Q, Cand](stub, n = 3, rewardFn = (_, _) => 1.0, threshold = 0.0, failCount = Some(1))

    given RuntimeContext = RuntimeEnvironment.current
    val result = bestOfN.apply(TypedCall(Q("x")))
    assert(result.isLeft)
    assertEquals(stub.calls.get(), 2)
    assertEquals(result.left.toOption.get.message, "f2")
  }

  test("typed Refine preserves best-of-N parity on the no-advice path (threshold met on the first attempt)") {
    // When attempt 1 already meets the threshold, Refine short-circuits with no feedback step — identical to
    // BestOfN. No LM/adapter is configured, proving the OfferFeedback sub-program is never reached here.
    val stub = TypedStub(Vector(
      Right(candidate("A", 0.8)),
      Right(candidate("B", 0.2))
    ))
    val refine = Refine[Q, Cand](stub, n = 2, rewardFn = (_, p) => p.output.score, threshold = 0.5)

    given RuntimeContext = RuntimeEnvironment.current
    val result = refine.apply(TypedCall(Q("x")))
    assertEquals(result.toOption.map(_.output), Some(Cand("A", 0.8)))
    assertEquals(stub.calls.get(), 1)
  }

  // ── Refine feedback loop (OfferFeedback + HintInjectingAdapter) ──────────────────────────────────────────────

  import TypedBestOfNSuite.AdviceText

  /** A scripted adapter that round-trips any layout generically: it renders every input field (so an injected
    * `hint_` shows up in the prompt) and parses each output field from `metadata`. It records the prompt text of
    * the last inner-`Cand` format so the test can assert the hint reached attempt 2's prompt. */
  private final class ScriptingAdapter extends Adapter:
    val innerPrompts: ArrayBuffer[String] = ArrayBuffer.empty
    override val name: String             = "scripting"

    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      val rendered = invocation.layout.inputFields.map { field =>
        val v = DynamicValues.recordGet(invocation.inputs.values, field.name).map(DynamicValues.renderText).getOrElse("")
        s"${field.name}: $v"
      }.mkString("\n")
      // Tag the prompt so the LM can route Cand vs OfferFeedback, and so the test can inspect the inner prompt.
      val isOfferFeedback = invocation.layout.outputFields.exists(_.name == "advice")
      val text            = (if isOfferFeedback then "OFFER_FEEDBACK\n" else "INNER\n") + rendered
      if !isOfferFeedback then innerPrompts += text
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some(text)))))

    override def parse(layout: SignatureLayout, output: LmOutput)(using
        RuntimeContext
    ): Either[DspyError, ParsedOutput] =
      // The LM packs each output field's value into `metadata` under its field name.
      val entries = layout.outputFields.map { field =>
        val raw = DynamicValues.recordGet(output.metadata, field.name).getOrElse(DynamicValue.Null)
        field.name -> raw
      }
      Right(ParsedOutput(values = DynamicValues.recordFromEntries(entries)))

  /** A scripted LM: for an OfferFeedback prompt it returns the canned [[AdviceText]]; for an inner-`Cand` prompt
    * it returns the GOOD answer (score 1.0) iff the prompt already contains the advice hint, else the BAD answer
    * (score 0.2). Tracks how many OfferFeedback calls it served. */
  private final class ScriptingLm extends LanguageModel:
    val offerFeedbackCalls: AtomicInteger = AtomicInteger(0)
    override val id: String               = "scripting-lm"
    override val mode: LmMode             = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val promptText = request.messages.flatMap(_.text).mkString("\n")
      if promptText.startsWith("OFFER_FEEDBACK") then
        offerFeedbackCalls.incrementAndGet()
        Right(LmResponse(outputs = Vector(LmOutput(
          text     = "",
          metadata = DynamicValues.record("discussion" := "needs a hint", "advice" := AdviceText)
        ))))
      else
        val sawHint = promptText.contains(AdviceText)
        val (answer, score) = if sawHint then ("Brussels", 1.0d) else ("Antwerp", 0.2d)
        Right(LmResponse(outputs = Vector(LmOutput(
          text     = answer,
          metadata = DynamicValues.record("answer" := answer, "score" := score)
        ))))

  /** A scripted LM whose OfferFeedback (advice) call FAILS with a `Left`, while inner-`Cand` calls succeed with the
    * BAD answer (score 0.2). Used to prove that an auxiliary advice-generation failure does NOT discard an
    * already-successful sub-threshold `best`. */
  private final class AdviceFailingLm extends LanguageModel:
    val offerFeedbackCalls: AtomicInteger = AtomicInteger(0)
    val innerCalls: AtomicInteger         = AtomicInteger(0)
    override val id: String               = "advice-failing-lm"
    override val mode: LmMode             = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val promptText = request.messages.flatMap(_.text).mkString("\n")
      if promptText.startsWith("OFFER_FEEDBACK") then
        offerFeedbackCalls.incrementAndGet()
        Left(RuntimeError("advice-failing-lm", "offer-feedback boom"))
      else
        innerCalls.incrementAndGet()
        Right(LmResponse(outputs = Vector(LmOutput(
          text     = "Antwerp",
          metadata = DynamicValues.record("answer" := "Antwerp", "score" := 0.2d)
        ))))

  /** Inner typed program that drives the AMBIENT adapter + LM (so an injected `hint_` actually reaches the prompt
    * the LM sees). Mirrors what a real `Predict[Q, Cand]` does, minus the static-Signature codec. */
  private final class InnerPredict extends Module[TypedCall[Q], Prediction[Cand]]:
    private val layout = SignatureLayout.parse("q -> answer, score").toOption.get
    override val moduleName: String = "inner_predict"
    override protected def callInputs(call: TypedCall[Q]): DynamicValue.Record = rec("q" := call.input.q)
    override protected def callTraceEnabled(call: TypedCall[Q]): Boolean       = call.traceEnabled
    override protected def tracePayload(p: Prediction[Cand]): DynamicValue.Record = p.raw.values

    override protected def forward(call: TypedCall[Q])(using RuntimeContext): Either[DspyError, Prediction[Cand]] =
      val ctx     = RuntimeEnvironment.current
      val adapter = ctx.adapter.collect { case a: Adapter => a }.get
      val lm      = ctx.lm.collect { case m: LanguageModel => m }.get
      val invocation = AdapterInvocation(
        layout  = layout,
        demos   = Vector.empty,
        inputs  = Example(values = rec("q" := call.input.q), inputKeys = Set("q")),
        request = LmRequest(model = lm.id)
      )
      for
        prompt   <- adapter.format(invocation)
        response <- lm.call(invocation.request.copy(messages = prompt.messages))
        parsed   <- adapter.parse(layout, response.outputs.head)
        answer   <- DynamicPrediction(values = parsed.values).asString("answer")
        score    <- DynamicPrediction(values = parsed.values).asDouble("score")
      yield Prediction(output = Cand(answer, score), raw = DynamicPrediction(values = parsed.values))

  test("Refine generates advice and injects it as a hint so a retry improves above threshold") {
    val adapter = ScriptingAdapter()
    val lm      = ScriptingLm()
    val refine  = Refine[Q, Cand](
      module    = InnerPredict(),
      n         = 2,
      rewardFn  = (_, p) => p.output.score,
      threshold = 1.0
    )

    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(adapter))) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = refine.apply(TypedCall(Q("Capital of Belgium?")))

      // attempt 1 (no hint) scored 0.2; attempt 2 saw the hint and scored 1.0 -> the improved prediction wins.
      assertEquals(result.toOption.map(_.output), Some(Cand("Brussels", 1.0)))
      // The OfferFeedback sub-program was invoked exactly once (between the two attempts).
      assertEquals(lm.offerFeedbackCalls.get(), 1)
      // Two inner attempts ran; attempt 2's prompt actually carried the injected advice.
      assertEquals(adapter.innerPrompts.size, 2)
      assert(!adapter.innerPrompts(0).contains(AdviceText), "attempt 1 must NOT carry a hint")
      assert(adapter.innerPrompts(1).contains("hint_"), "attempt 2 prompt must include the hint_ field")
      assert(adapter.innerPrompts(1).contains(AdviceText), "attempt 2 prompt must include the advice text")
    }
  }

  test("Refine generates NO advice when the first attempt already meets the threshold") {
    // Threshold 0.0 means even the BAD answer (0.2) clears it on attempt 1 -> no feedback, best-of-N parity.
    val adapter = ScriptingAdapter()
    val lm      = ScriptingLm()
    val refine  = Refine[Q, Cand](InnerPredict(), n = 3, rewardFn = (_, p) => p.output.score, threshold = 0.0)

    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(adapter))) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = refine.apply(TypedCall(Q("Capital of Belgium?")))

      assertEquals(result.toOption.map(_.output.answer), Some("Antwerp"))
      assertEquals(lm.offerFeedbackCalls.get(), 0)
      assertEquals(adapter.innerPrompts.size, 1)
    }
  }

  test("Refine preserves the best-so-far when advice generation fails (does not discard a good result)") {
    // attempt 1 scores 0.2 (< threshold 1.0) and becomes `best`; generating advice for attempt 2 then fails.
    // The advice call is AUXILIARY, so its failure must NOT discard the already-successful prediction.
    // Regression: the advice-failure branch used to `return Left(error)`, throwing away `best`.
    val adapter = ScriptingAdapter()
    val lm      = AdviceFailingLm()
    val refine  = Refine[Q, Cand](InnerPredict(), n = 2, rewardFn = (_, p) => p.output.score, threshold = 1.0)

    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(adapter))) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = refine.apply(TypedCall(Q("Capital of Belgium?")))

      assert(result.isRight, s"advice failure must not discard the best-so-far; got: $result")
      assertEquals(result.toOption.map(_.output), Some(Cand("Antwerp", 0.2)))
      assertEquals(lm.offerFeedbackCalls.get(), 1) // the advice call was attempted (and failed)
    }
  }

  test("Refine surfaces the last error after exhausting a custom fail budget") {
    val stub = TypedStub(Vector(
      Left(RuntimeError("typed_stub", "f1")),
      Left(RuntimeError("typed_stub", "f2")),
      Right(candidate("ok", 1.0))
    ))
    val refine = Refine[Q, Cand](stub, n = 3, rewardFn = (_, _) => 1.0, threshold = 0.0, failCount = Some(1))

    given RuntimeContext = RuntimeEnvironment.current
    val result = refine.apply(TypedCall(Q("x")))
    assert(result.isLeft)
    assertEquals(stub.calls.get(), 2)
    assertEquals(result.left.toOption.get.message, "f2")
  }

end TypedBestOfNSuite

object TypedBestOfNSuite:
  /** Canonical text the OfferFeedback LM emits as advice and the inner LM looks for in its prompt. Lives in the
    * companion so the strict initialization checker sees it as a stable, transitively-initialized constant. */
  private val AdviceText = "ALWAYS-ANSWER-BRUSSELS"
