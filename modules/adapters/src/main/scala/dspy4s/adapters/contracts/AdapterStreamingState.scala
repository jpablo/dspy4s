package dspy4s.adapters.contracts

/** A chunk of output text routed to a specific signature field by an [[AdapterStreamingState]]. `isLast = true` marks
  * the final emission for the field (either because the adapter detected the next field's boundary or because the
  * stream is finishing).
  */
final case class FieldChunk(fieldName: String, text: String, isLast: Boolean = false)

/** Per-call state machine that consumes streamed LM text fragments and emits per-field chunks based on the adapter's
  * framing.
  *
  *   - `receive(textDelta)` appends a fresh token fragment and returns the chunks that have become safe to emit (i.e.
  *     not held back to disambiguate a partial field marker).
  *   - `finish()` flushes any remaining buffered content and must mark the final emitted chunk with `isLast = true`.
  *
  * Implementations are single-use per LM call; a fresh instance is created per [[dspy4s.streaming.Streamify]] producer
  * thread.
  */
trait AdapterStreamingState:
  def receive(textDelta: String): Vector[FieldChunk]
  def finish(): Vector[FieldChunk]
