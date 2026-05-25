package dspy4s.adapters.contracts

import dspy4s.core.contracts.AdapterRef
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.Message

final case class AdapterInvocation(
    layout: SignatureLayout,
    demos: Vector[Example],
    inputs: Example,
    request: LmRequest
)

final case class FormattedPrompt(messages: Vector[Message], metadata: Map[String, Any] = Map.empty)

final case class ParsedOutput(
    values: Map[String, Any],
    rawText: Option[String] = None,
    metadata: Map[String, Any] = Map.empty
)

/** A chunk of output text routed to a specific signature field by an
  * [[AdapterStreamingState]]. `isLast = true` marks the final emission for the
  * field (either because the adapter detected the next field's boundary or
  * because the stream is finishing).
  */
final case class FieldChunk(fieldName: String, text: String, isLast: Boolean = false)

/** Per-call state machine that consumes streamed LM text fragments and emits
  * per-field chunks based on the adapter's framing.
  *
  *   - `receive(textDelta)` appends a fresh token fragment and returns the
  *     chunks that have become safe to emit (i.e. not held back to disambiguate
  *     a partial field marker).
  *   - `finish()` flushes any remaining buffered content and must mark the
  *     final emitted chunk with `isLast = true`.
  *
  * Implementations are single-use per LM call; a fresh instance is created per
  * [[dspy4s.streaming.Streamify]] producer thread.
  */
trait AdapterStreamingState:
  def receive(textDelta: String): Vector[FieldChunk]
  def finish(): Vector[FieldChunk]

trait Adapter extends AdapterRef:
  def name: String

  def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt]

  def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput]

  /** Streaming-aware adapters override this to return a per-call state
    * machine. The default returns [[None]] and the streaming pipeline falls
    * back to emitting raw tokens with an empty field name.
    */
  def streamingState(layout: SignatureLayout): Option[AdapterStreamingState] = None

  def execute(languageModel: LanguageModel, invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, Vector[ParsedOutput]] =
    for
      prompt <- format(invocation)
      response <- languageModel.call(invocation.request.copy(messages = prompt.messages))
      parsed <- parseOutputs(invocation.layout, response.outputs)
    yield parsed

  private def parseOutputs(layout: SignatureLayout, outputs: Vector[LmOutput])(using
      RuntimeContext
  ): Either[DspyError, Vector[ParsedOutput]] =
    outputs.foldLeft(Right(Vector.empty): Either[DspyError, Vector[ParsedOutput]]) { (acc, output) =>
      for
        soFar <- acc
        parsed <- parse(layout, output)
      yield soFar :+ parsed
    }

trait AdapterFallbackPolicy:
  def fallbackFor(error: DspyError, attemptedAdapter: String): Option[String]

object AdapterErrors:
  def missingField(fieldName: String): DspyError =
    ParseError(component = "adapter", message = s"Missing required output field: $fieldName")
