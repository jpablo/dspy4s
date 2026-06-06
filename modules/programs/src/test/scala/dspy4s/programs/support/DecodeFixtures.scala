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

  /** Feed a canned LM completion (the exact text a model would emit) through the REAL adapter + typed decode,
    * with no live LM. Returns the decoded typed output. */
  def decodeCompletion[I, O](
      signature: Signature[I, O],
      adapter: Adapter,
      input: I,
      completion: String
  ): Either[DspyError, O] =
    RuntimeEnvironment.withSettings(
      RuntimeContext(lm = Some(new ScriptedLm(completion)), adapter = Some(adapter))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      Predict(signature).apply(input).map(_.output)
    }

  /** Codec self-consistency for an output type: encode a sample via the signature's output Shape, then decode
    * it back. Proves the dspy4s codec round-trips the type (catches "this type doesn't survive the codec"). */
  def roundTripOutput[I, O](signature: Signature[I, O], sample: O): Either[DspyError, O] =
    signature.outputShape.decode(signature.outputShape.encode(sample))
