package dspy4s.adapters

import dspy4s.adapters.contracts.FieldChunk
import dspy4s.adapters.internal.SingleUseAdapterStreamingState
import dspy4s.core.contracts.{FieldSpec, PythonText}

import scala.collection.mutable

/** Streaming state machine for [[ChatAdapter]] output.
  *
  * Detects `[[ ## field_name ## ]]` markers in the streamed text and routes the content between them to per-field
  * [[FieldChunk]]s. Mirrors Python DSPy's `ChatAdapter` framing (start markers + `[[ ## completed ## ]]` terminator).
  *
  * Streaming discipline:
  *   - A marker is a header only at the start of a line, as in the parser (upstream `ChatAdapter.parse`:
  *     `field_header_pattern.match(line.strip())` on the lines of `str.splitlines`). Only Python whitespace
  *     (`str.isspace`) can come between the start of the line and the marker. A line starts at the start of the stream
  *     and after each line break of `str.splitlines`. The text before the buffer counts, so a marker after a header or
  *     after a long run of whitespace on the same line is text. Upstream `StreamListener` finds the markers in any
  *     place of the text; the port follows the parser, so that the chunks give the parsed values.
  *   - Mid-stream emission holds back the tail of the buffer that could still be the start of a marker. The hold-back
  *     window is the longest possible marker, which guarantees a partial `[[ ## foo` prefix is never emitted as
  *     content.
  *   - On `finish()`, anything held back is flushed to the current field and the last chunk is marked `isLast = true`.
  *   - The first header of a field wins, as in the parser (upstream `ChatAdapter.parse`). A later header of the same
  *     field starts a section that the stream drops.
  *   - The single-output fallback of the parser (a port rule): with one output field, the state holds the text until
  *     the first header of that field, and drops the held text at that header. When the field has no header at the end,
  *     the whole text, with the line ends of a section and stripped, is the value of the field, in one last chunk.
  *     Upstream streams only the text after the start header (`StreamListener.receive`).
  *   - Boundary whitespace (upstream `_default_handle_stream_chunk`): the parser strips each field value, so the chunks
  *     of a field do too. The leading whitespace of a field is dropped. A run of whitespace is held until more text of
  *     the same field arrives, and is dropped at the end of the field. Thus the text of the chunks of a field, joined,
  *     is the value that [[ChatAdapter]] parses for that field, for each split of the response into deltas.
  *
  * @param outputFields
  *   the output fields that the text of the reply gives ([[ChatAdapterParser.textFields]])
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
    (outputFields.map(f => ChatAdapter.marker(f.name).length) :+ ChatAdapter.CompletedMarker.length).max

  private val buffer                       = new StringBuilder
  private val whitespace                   = new ChatStreamingState.BoundaryWhitespace
  private var currentField: Option[String] = None

  /** True when the text before the buffer has no character after its last line break, or only whitespace. Then the
    * start of the buffer is in the indentation of a line, and a marker there can be a header. In [[drain]], the start
    * of the buffer is the start of the text that the loop did not consume.
    */
  private var bufferAtLineStart: Boolean = true

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

  /** The value of the single-output fallback at the end: the whole text, when the one output field had no header. The
    * value has the line ends of the parser (`ChatAdapterParser.fallbackText`).
    */
  private def fallbackValue: Vector[FieldChunk] =
    val out = mutable.ArrayBuffer.empty[FieldChunk]
    if fallbackOpen then
      singleFieldFallback.foreach(field =>
        closeField(field.name, ChatAdapterParser.fallbackText(fallbackText.toString), out)
      )
    out.toVector

  /** The chunks of the buffer. The loop reads the buffer from the offset `from`, the start of the text that it did not
    * consume, and removes the consumed text one time at the end.
    */
  private def drain(forceFlush: Boolean): Vector[FieldChunk] =
    val out  = mutable.ArrayBuffer.empty[FieldChunk]
    var from = 0
    var done = false
    while !done do
      findMarker(from) match
        case Some(MarkerHit(field, markerStart, markerEnd)) =>
          // The content before the marker is the end of the current field.
          currentField.foreach(name => closeField(name, buffer.substring(from, markerStart), out))
          from = consume(from, markerEnd)
          currentField = Option.when(outputNames.contains(field) && !openedFields.contains(field))(field)
          currentField.foreach { name =>
            openedFields += name
            fallbackText.clear()
          }
          // Loop — there may be another marker already in the buffer.
        case None =>
          val end = safeEnd(from, forceFlush)
          currentField match
            case None =>
              // No tracked field is open. Discard any safe prefix (text
              // before a possible marker start) and hold the tail that
              // might still become one.
              from = consume(from, end)
            case Some(name) =>
              if forceFlush then
                closeField(name, buffer.substring(from), out)
                from = buffer.length
              else if end > from then
                val emitted = whitespace.accept(buffer.substring(from, end))
                if emitted.nonEmpty then out += FieldChunk(name, emitted, isLast = false)
                from = consume(from, end)
          done = true
    buffer.delete(0, from)
    out.toVector

  /** Consume the characters of the buffer from `from` to `to`, and update [[bufferAtLineStart]] for them. The result is
    * `to`, the new start of the text that the loop did not consume.
    */
  private def consume(from: Int, to: Int): Int =
    var index = from
    while index < to do
      val char = buffer.charAt(index)
      if PythonText.isLineBreak(char) then bufferAtLineStart = true
      else if !PythonText.isSpace(char) then bufferAtLineStart = false
      index += 1
    to

  /** End the field `name` with its last `content`. A field with text gets a last chunk with `isLast = true`, which can
    * be empty: the held whitespace at the end of a field is dropped. A field with no text gets no chunk.
    */
  private def closeField(name: String, content: String, out: mutable.ArrayBuffer[FieldChunk]): Unit =
    val emitted = whitespace.accept(content)
    if whitespace.started then out += FieldChunk(name, emitted, isLast = true)
    whitespace.reset()

  /** The end of the text from `from` that is safe to emit (or discard) without risking truncation of a partial marker.
    * When `forceFlush` is true, the whole buffer is safe. Otherwise, we must hold back any tail that could be the start
    * of a marker.
    */
  private def safeEnd(from: Int, forceFlush: Boolean): Int =
    if forceFlush then buffer.length
    else
      // The safest, simplest rule: hold the trailing `markerMaxLen - 1`
      // chars. They might still grow into a marker on the next receive.
      math.max(from, buffer.length - (markerMaxLen - 1))

  /** Find the earliest line-aligned `[[ ## name ## ]]` marker in the buffer after the offset `from`, or `None`. The
    * pattern reads the buffer in place. A line-aligned marker has only the indentation of its line before it
    * ([[indentStart]]).
    */
  private def findMarker(from: Int): Option[MarkerHit] =
    var result: Option[MarkerHit] = None
    val matcher                   = ChatAdapter.MarkerPattern.pattern.matcher(buffer).region(from, buffer.length)
    while result.isEmpty && matcher.find() do
      val start      = indentStart(from, matcher.start)
      val lineStarts = if start == from then bufferAtLineStart else PythonText.isLineBreak(buffer.charAt(start - 1))
      if lineStarts then result = Some(MarkerHit(matcher.group(1), markerStart = start, markerEnd = matcher.end))
    result

  /** The start of the indentation before the position `markerStart` of the buffer: the run of whitespace with no line
    * break that ends at `markerStart`, at the offset `from` or after. The marker line starts there, so the content of
    * the field before the marker ends there.
    */
  private def indentStart(from: Int, markerStart: Int): Int =
    var start = markerStart
    while start > from && isIndent(buffer.charAt(start - 1)) do start -= 1
    start

  /** True for a Python whitespace character that is not a line break of `str.splitlines`. */
  private def isIndent(char: Char): Boolean = PythonText.isSpace(char) && !PythonText.isLineBreak(char)

  private final case class MarkerHit(field: String, markerStart: Int, markerEnd: Int)

object ChatStreamingState:

  /** The boundary whitespace rule for the chunks of one field (upstream `_default_handle_stream_chunk`). The rule drops
    * the leading whitespace of the field. It holds a run of whitespace until more text of the field arrives. Whitespace
    * is Python whitespace (`str.isspace`), as upstream `token.lstrip()` and `token.rstrip()`, and as the parser strips
    * a value ([[PythonText.strip]]).
    */
  private final class BoundaryWhitespace:
    private val held: StringBuilder = new StringBuilder
    private var hasText: Boolean    = false

    /** True when the field has a character that is not whitespace. */
    def started: Boolean = hasText

    /** The text to emit for the next `content` of the field. */
    def accept(content: String): String =
      val text = if hasText then content else PythonText.lstrip(content)
      val body = PythonText.rstrip(text)
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
