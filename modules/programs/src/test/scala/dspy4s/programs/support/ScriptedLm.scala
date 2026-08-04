package dspy4s.programs.support

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse

import java.util.concurrent.atomic.AtomicInteger

/** Shared sequential-script LM fake: the i-th `call` returns `responses(i)`; past the end of the script it returns
  * empty text. `calls` exposes how many calls were made. One fixture instead of a per-suite copy so a contract change
  * to [[LanguageModel]] (or a fake-behavior fix) lands in exactly one place.
  */
final class ScriptedLm(responses: Vector[String], override val id: String = "scripted-lm") extends LanguageModel:
  val calls: AtomicInteger                                                                   = AtomicInteger(0)
  override val mode: LmMode                                                                  = LmMode.Chat
  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    val i    = calls.getAndIncrement()
    val text = if i < responses.size then responses(i) else ""
    Right(LmResponse(outputs = Vector(LmOutput(text = text))))
