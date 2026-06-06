package dspy4s.programs.support

import dspy4s.adapters.contracts.Adapter
import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.programs.Predict
import dspy4s.typed.Signature

/** Offline decode-test harness: drive the REAL adapter + typed decode against a canned LM completion, with no
  * live LM and no API key. Deterministic, so it can pin down the codec/adapter contract for a structured type
  * (the enum / list / option / number paths that historically broke).
  */
object DecodeFixtures:

  /** A minimal `LanguageModel` whose `call` always returns the supplied completion text as the single output. */
  private final class ScriptedLm(completion: String) extends LanguageModel:
    override val id: String   = "scripted"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = completion))))

  /** A `LanguageModel` that records every message text it is asked to send, then returns an empty completion. */
  private final class CapturingLm(sink: String => Unit) extends LanguageModel:
    override val id: String   = "capturing"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      request.messages.foreach(m => m.text.foreach(sink))
      Right(LmResponse(outputs = Vector(LmOutput(text = ""))))

  /** Install a scripted LM (returning `completion`) plus `adapter` as the active context and run `body` — which
    * invokes whatever program you're testing (`Predict`, `ChainOfThought`, `ReAct`, …). Returns the body's
    * result. This is the program-agnostic core: it doesn't constrain the output type, so it works for
    * `ChainOfThought`'s augmented named-tuple output as readily as a plain `Predict`. */
  def runWith[A](adapter: Adapter, completion: String)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(
      RuntimeContext(lm = Some(new ScriptedLm(completion)), adapter = Some(adapter))
    ) {
      body(using RuntimeEnvironment.current)
    }

  /** Run `body` (which invokes some program) under a capturing LM + `adapter`, discard its result, and return
    * the concatenated text of every message the program sent to the LM. Use it to assert *what a program asks
    * the model* — e.g. that enum values or a nested output schema reached the prompt — with no live LM. */
  def capturePrompt(adapter: Adapter)(body: RuntimeContext ?=> Any): String =
    val buf = scala.collection.mutable.ArrayBuffer.empty[String]
    RuntimeEnvironment.withSettings(
      RuntimeContext(lm = Some(new CapturingLm(buf.append)), adapter = Some(adapter))
    ) {
      val _ = body(using RuntimeEnvironment.current)
    }
    buf.mkString("\n")

  /** Feed a canned LM completion (the exact text a model would emit) through the REAL adapter + typed decode,
    * with no live LM. Returns the decoded typed output. */
  def decodeCompletion[I, O](
      signature: Signature[I, O],
      adapter: Adapter,
      input: I,
      completion: String
  ): Either[DspyError, O] =
    runWith(adapter, completion) {
      Predict(signature).apply(input).map(_.output)
    }

  /** Codec self-consistency for an output type: encode a sample via the signature's output Shape, then decode
    * it back. Proves the dspy4s codec round-trips the type (catches "this type doesn't survive the codec"). */
  def roundTripOutput[I, O](signature: Signature[I, O], sample: O): Either[DspyError, O] =
    signature.outputShape.decode(signature.outputShape.encode(sample))
