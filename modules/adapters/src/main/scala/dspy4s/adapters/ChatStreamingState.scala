package dspy4s.adapters

import dspy4s.adapters.contracts.FieldChunk
import dspy4s.adapters.internal.SingleUseAdapterStreamingState
import dspy4s.core.contracts.FieldSpec

import scala.collection.mutable

/** Streaming state machine for [[ChatAdapter]] output.
  *
  * Detects `[[ ## field_name ## ]]` markers in the streamed text and routes the content between them to per-field
  * [[FieldChunk]]s. Mirrors Python DSPy's `ChatAdapter` framing (start markers + `[[ ## completed ## ]]` terminator).
  *
  * Streaming discipline:
  *   - Recognised markers must be at the start of a (logical) line — i.e. immediately following a newline or at the
  *     beginning of the stream. This matches Python's `line.strip()` + `re.match()` behaviour.
  *   - Mid-stream emission holds back the tail of the buffer that could still be the start of a marker. The hold-back
  *     window is the longest possible marker, which guarantees a partial `[[ ## foo` prefix is never emitted as
  *     content.
  *   - On `finish()`, anything held back is flushed to the current field and the last chunk is marked `isLast = true`.
  *   - The first header of a field wins, as in the parser (upstream `ChatAdapter.parse`). A later header of the same
  *     field starts a section that the stream drops.
  *   - The single-output fallback of the parser (a port rule): with one output field, the state holds the text until
  *     the first header of that field, and drops the held text at that header. When the field has no header at the end,
  *     the whole text, stripped, is the value of the field, in one last chunk. Upstream streams only the text after the
  *     start header (`StreamListener.receive`).
  *   - Boundary whitespace (upstream `_default_handle_stream_chunk`): the parser strips each field value, so the chunks
  *     of a field do too. The leading whitespace of a field is dropped. A run of whitespace is held until more text of
  *     the same field arrives, and is dropped at the end of the field. Thus the text of the chunks of a field, joined,
  *     is the value that [[ChatAdapter]] parses for that field, for each split of the response into deltas.
  */
final class ChatStreamingState(outputFields: Vector[FieldSpec]) extends SingleUseAdapterStreamingState:

  private val outputNames: Set[String]               = outputFields.map(_.name).toSet
  private val singleFieldFallback: Option[FieldSpec] = Option.when(outputFields.size == 1)(outputFields.head)

  // Longest possible marker — the completed sentinel or any output-field
  // marker (`[[ ## name ## ]]`); we hold back that many trailing chars on
  // every mid-stream emission so any partial marker is preserved for the
  // next receive() call. Sizing to the completed sentinel alone would let a
  // partial marker for a field name longer than `completed` be split and
  // emitted as content, permanently losing the field boundary.
  private val markerMaxLen: Int =
    (outputFields.map(f => s"[[ ## ${f.name} ## ]]".length) :+ ChatAdapter.CompletedMarker.length).max

  private val buffer                       = new StringBuilder
  private val whitespace                   = new ChatStreamingState.BoundaryWhitespace
  private var currentField: Option[String] = None

  /** The output fields that had a header. */
  private val openedFields: mutable.Set[String] = mutable.Set.empty

  /** The whole text of the response while the single-output fallback can apply. */
  private val fallbackText = new StringBuilder

  /** True while no output field had a header, with one output field. */
  private def fallbackOpen: Boolean = singleFieldFallback.isDefined && openedFields.isEmpty

  override protected def receiveOpen(textDelta: String): Vector[FieldChunk] =
    if fallbackOpen then fallbackText.append(textDelta)
    buffer.append(textDelta)
    drain(forceFlush = false)

  override protected def finishOpen(): Vector[FieldChunk] =
    val flushed = drain(forceFlush = true) ++ fallbackValue
    if flushed.isEmpty then Vector.empty
    else flushed.init :+ flushed.last.copy(isLast = true)

  /** The value of the single-output fallback at the end: the whole text, when the one output field had no header. */
  private def fallbackValue: Vector[FieldChunk] =
    val out = mutable.ArrayBuffer.empty[FieldChunk]
    if fallbackOpen then singleFieldFallback.foreach(field => closeField(field.name, fallbackText.toString, out))
    out.toVector

  private def drain(forceFlush: Boolean): Vector[FieldChunk] =
    val out  = mutable.ArrayBuffer.empty[FieldChunk]
    var done = false
    while !done do
      findMarker(buffer.toString) match
        case Some(MarkerHit(field, markerStart, markerEnd)) =>
          // The content before the marker is the end of the current field.
          currentField.foreach(name => closeField(name, buffer.substring(0, markerStart), out))
          buffer.delete(0, markerEnd)
          currentField = Option.when(outputNames.contains(field) && !openedFields.contains(field))(field)
          currentField.foreach { name =>
            openedFields += name
            fallbackText.clear()
          }
          // Loop — there may be another marker already in the buffer.
        case None =>
          currentField match
            case None =>
              // No tracked field is open. Discard any safe prefix (text
              // before a possible marker start) and hold the tail that
              // might still become one.
              val emittable = safeEmittablePrefixLength(buffer.toString, forceFlush)
              if emittable > 0 then buffer.delete(0, emittable)
            case Some(name) =>
              val text = buffer.toString
              if forceFlush then
                closeField(name, text, out)
                buffer.clear()
              else
                val emittable = safeEmittablePrefixLength(text, forceFlush = false)
                if emittable > 0 then
                  val emitted = whitespace.accept(text.substring(0, emittable))
                  if emitted.nonEmpty then out += FieldChunk(name, emitted, isLast = false)
                  buffer.delete(0, emittable)
          done = true
    out.toVector

  /** End the field `name` with its last `content`. A field with text gets a last chunk with `isLast = true`, which can
    * be empty: the held whitespace at the end of a field is dropped. A field with no text gets no chunk.
    */
  private def closeField(name: String, content: String, out: mutable.ArrayBuffer[FieldChunk]): Unit =
    val emitted = whitespace.accept(content)
    if whitespace.started then out += FieldChunk(name, emitted, isLast = true)
    whitespace.reset()

  /** Returns the length of the prefix of `text` that is safe to emit (or discard) without risking truncation of a
    * partial marker. When `forceFlush` is true, the whole buffer is safe. Otherwise, we must hold back any tail that
    * could be the start of a marker.
    */
  private def safeEmittablePrefixLength(text: String, forceFlush: Boolean): Int =
    if forceFlush then text.length
    else
      // The safest, simplest rule: hold the trailing `markerMaxLen - 1`
      // chars. They might still grow into a marker on the next receive.
      math.max(0, text.length - (markerMaxLen - 1))

  /** Find the earliest line-aligned `[[ ## name ## ]]` marker in `text`, or `None`. "Line-aligned" means the match must
    * start at position 0 or be preceded by a newline + optional whitespace.
    */
  private def findMarker(text: String): Option[MarkerHit] =
    var result: Option[MarkerHit] = None
    val it                        = ChatAdapter.MarkerPattern.findAllMatchIn(text)
    while result.isEmpty && it.hasNext do
      val m = it.next()
      if isLineAligned(text, m.start) then
        result = Some(MarkerHit(m.group(1), markerStart = lineAlignedStart(text, m.start), markerEnd = m.end))
    result

  /** True if position `i` is at the start of a line — either position 0, or preceded only by spaces / tabs since the
    * most recent newline.
    */
  private def isLineAligned(text: String, i: Int): Boolean =
    var k = i - 1
    while k >= 0 && (text.charAt(k) == ' ' || text.charAt(k) == '\t') do k -= 1
    k < 0 || text.charAt(k) == '\n'

  /** Returns the position of the newline that precedes the marker (or 0 if the marker starts at the beginning of
    * `text`). Used to ensure we don't leave dangling indentation in the buffer after consuming the marker.
    */
  private def lineAlignedStart(text: String, markerStart: Int): Int =
    var k = markerStart - 1
    while k >= 0 && (text.charAt(k) == ' ' || text.charAt(k) == '\t') do k -= 1
    // Step past the newline itself so the trimmed marker line is fully
    // consumed. If we're at start-of-text, return 0.
    if k < 0 then 0 else k + 1

  private final case class MarkerHit(field: String, markerStart: Int, markerEnd: Int)

object ChatStreamingState:

  /** The boundary whitespace rule for the chunks of one field (upstream `_default_handle_stream_chunk`). The rule drops
    * the leading whitespace of the field. It holds a run of whitespace until more text of the field arrives. Whitespace
    * is a character for which `Character.isWhitespace` is true, as `String.strip` in the parser.
    */
  private final class BoundaryWhitespace:
    private val held: StringBuilder = new StringBuilder
    private var hasText: Boolean    = false

    /** True when the field has a character that is not whitespace. */
    def started: Boolean = hasText

    /** The text to emit for the next `content` of the field. */
    def accept(content: String): String =
      val text = if hasText then content else content.stripLeading
      val body = text.stripTrailing
      if body.isEmpty then
        held.append(text)
        ""
      else
        hasText = true
        val emitted = held.toString + body
        held.clear()
        held.append(text.substring(body.length))
        emitted

    /** Drop the held whitespace, and start again for the next field. */
    def reset(): Unit =
      held.clear()
      hasText = false

  /** True iff `text`'s tail or interior could still grow into a `[[ ## field_name ## ]]` (or `[[ ## completed ## ]]`)
    * marker. Used by the per-token holdback heuristic — when this returns `false`, every pending token in the wrapper's
    * queue is safe to flush.
    *
    * Ported from Python `StreamListener._could_form_end_identifier` for `ChatAdapter`. Returns true when the buffer
    * ends with any of the marker-prefix snippets, or already contains `[[ ##` anywhere (a partial-marker tail can no
    * longer be distinguished without further tokens).
    */
  def couldFormEndIdentifier(text: String): Boolean =
    val endPrefixes = Seq("[", "[[", "[[ ", "[[ #", "[[ ##")
    endPrefixes.exists(text.endsWith) || text.contains("[[ ##")
