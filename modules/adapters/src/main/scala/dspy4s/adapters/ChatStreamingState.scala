package dspy4s.adapters

import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FieldChunk
import dspy4s.core.contracts.FieldSpec

import scala.collection.mutable

/** Streaming state machine for [[ChatAdapter]] output.
  *
  * Detects `[[ ## field_name ## ]]` markers in the streamed text and routes
  * the content between them to per-field [[FieldChunk]]s. Mirrors Python
  * DSPy's `ChatAdapter` framing (start markers + `[[ ## completed ## ]]`
  * terminator).
  *
  * Streaming discipline:
  *   - Recognised markers must be at the start of a (logical) line — i.e.
  *     immediately following a newline or at the beginning of the stream.
  *     This matches Python's `line.strip()` + `re.match()` behaviour.
  *   - Mid-stream emission holds back the tail of the buffer that could
  *     still be the start of a marker. The hold-back window is the
  *     longest possible marker, which guarantees a partial `[[ ## foo`
  *     prefix is never emitted as content.
  *   - On `finish()`, anything held back is flushed to the current field
  *     and the last chunk is marked `isLast = true`.
  *   - When the model never emits any marker and the signature has a
  *     single output field, all received text is attributed to that
  *     field (matches the parser's single-output fallback).
  */
final class ChatStreamingState(outputFields: Vector[FieldSpec]) extends AdapterStreamingState:

  private val outputNames: Set[String] = outputFields.map(_.name).toSet
  private val singleFieldFallback: Option[FieldSpec] =
    Option.when(outputFields.size == 1)(outputFields.head)

  // Longest possible marker is the completed sentinel; we hold back that
  // many trailing chars on every mid-stream emission so any partial marker
  // is preserved for the next receive() call.
  private val markerMaxLen: Int = ChatAdapter.CompletedMarker.length

  private val buffer = new StringBuilder
  private var currentField: Option[String] = None
  private var sawAnyMarker: Boolean = false
  private var finished: Boolean = false

  override def receive(textDelta: String): Vector[FieldChunk] =
    if finished || textDelta.isEmpty then Vector.empty
    else
      buffer.append(textDelta)
      drain(forceFlush = false)

  override def finish(): Vector[FieldChunk] =
    if finished then Vector.empty
    else
      finished = true
      val flushed = drain(forceFlush = true)
      if flushed.isEmpty then Vector.empty
      else flushed.init :+ flushed.last.copy(isLast = true)

  private def drain(forceFlush: Boolean): Vector[FieldChunk] =
    val out = mutable.ArrayBuffer.empty[FieldChunk]
    var done = false
    while !done do
      findMarker(buffer.toString) match
        case Some(MarkerHit(field, markerStart, markerEnd)) =>
          // Emit any content preceding the marker to the current field
          // (closing it).
          val precedingContent =
            stripFramingNewlines(buffer.substring(0, markerStart))
          effectiveActiveField match
            case Some(name) if precedingContent.nonEmpty =>
              out += FieldChunk(name, precedingContent, isLast = true)
            case _ => ()
          buffer.delete(0, markerEnd)
          sawAnyMarker = true
          // Skip the trailing newline that follows the marker, if present,
          // so emitted content doesn't begin with an empty leading line.
          while buffer.nonEmpty && buffer.charAt(0) == '\n' do buffer.deleteCharAt(0)
          currentField = field match
            case Some(name) if outputNames.contains(name) => Some(name)
            case _                                        => None
          // Loop — there may be another marker already in the buffer.
        case None =>
          effectiveActiveField match
            case None =>
              // We haven't entered any tracked field yet. Discard any safe
              // prefix (text before a possible marker start) and hold the
              // tail that might still become one.
              val emittable = safeEmittablePrefixLength(buffer.toString, forceFlush)
              if emittable > 0 then buffer.delete(0, emittable)
            case Some(name) =>
              val text = buffer.toString
              if forceFlush then
                val flushed = stripFramingNewlines(text)
                if flushed.nonEmpty then
                  out += FieldChunk(name, flushed, isLast = false)
                buffer.clear()
              else
                val emittable = safeEmittablePrefixLength(text, forceFlush = false)
                if emittable > 0 then
                  out += FieldChunk(name, text.substring(0, emittable), isLast = false)
                  buffer.delete(0, emittable)
          done = true
    out.toVector

  /** What's the active output field, taking the single-output fallback into
    * account when the model has not yet emitted any marker. Once any marker
    * has been seen we trust the marker stream exclusively — no fallback. */
  private def effectiveActiveField: Option[String] =
    currentField.orElse(if sawAnyMarker then None else singleFieldFallback.map(_.name))

  /** Returns the length of the prefix of `text` that is safe to emit (or
    * discard) without risking truncation of a partial marker. When
    * `forceFlush` is true, the whole buffer is safe. Otherwise, we must
    * hold back any tail that could be the start of a marker. */
  private def safeEmittablePrefixLength(text: String, forceFlush: Boolean): Int =
    if forceFlush then text.length
    else
      // The safest, simplest rule: hold the trailing `markerMaxLen - 1`
      // chars. They might still grow into a marker on the next receive.
      math.max(0, text.length - (markerMaxLen - 1))

  /** Find the earliest line-aligned `[[ ## name ## ]]` marker in `text`,
    * or `None`. "Line-aligned" means the match must start at position 0
    * or be preceded by a newline + optional whitespace. */
  private def findMarker(text: String): Option[MarkerHit] =
    var result: Option[MarkerHit] = None
    val it = ChatAdapter.MarkerPattern.findAllMatchIn(text)
    while result.isEmpty && it.hasNext do
      val m = it.next()
      if isLineAligned(text, m.start) then
        result = Some(MarkerHit(Some(m.group(1)), markerStart = lineAlignedStart(text, m.start), markerEnd = m.end))
    result

  /** True if position `i` is at the start of a line — either position 0,
    * or preceded only by spaces / tabs since the most recent newline. */
  private def isLineAligned(text: String, i: Int): Boolean =
    var k = i - 1
    while k >= 0 && (text.charAt(k) == ' ' || text.charAt(k) == '\t') do k -= 1
    k < 0 || text.charAt(k) == '\n'

  /** Returns the position of the newline that precedes the marker (or 0
    * if the marker starts at the beginning of `text`). Used to ensure we
    * don't leave dangling indentation in the buffer after consuming the
    * marker. */
  private def lineAlignedStart(text: String, markerStart: Int): Int =
    var k = markerStart - 1
    while k >= 0 && (text.charAt(k) == ' ' || text.charAt(k) == '\t') do k -= 1
    // Step past the newline itself so the trimmed marker line is fully
    // consumed. If we're at start-of-text, return 0.
    if k < 0 then 0 else k + 1

  /** Trim a single leading/trailing newline (left over from marker
    * stripping) from emitted content. We only strip one because field
    * values can legitimately contain blank lines. */
  private def stripFramingNewlines(s: String): String =
    val left = if s.startsWith("\n") then s.drop(1) else s
    if left.endsWith("\n") then left.dropRight(1) else left

  private final case class MarkerHit(field: Option[String], markerStart: Int, markerEnd: Int)
