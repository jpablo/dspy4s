package dspy4s.adapters

import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FieldChunk
import dspy4s.core.contracts.FieldSpec

import scala.collection.mutable

/** Streaming state machine for [[ChatAdapter]] output.
  *
  * `ChatAdapter` renders outputs as `prefix: value` lines, one per field
  * (see `ChatAdapter.renderFields`). This state machine:
  *
  *   1. Buffers incoming text fragments.
  *   2. Switches into the next field whenever a new line begins with a known
  *      output-field label (case-insensitive, leading whitespace tolerated).
  *   3. Emits text chunks only up to the most recent newline so a partial line
  *      that may yet turn out to be a label is never leaked to the listener.
  *   4. On `finish()`, flushes the remaining buffer (sans any trailing label
  *      prefix that arrived without a value) and marks the last chunk
  *      `isLast = true`.
  *
  * If the model never emits a recognizable label and the signature has a
  * single output field, all received text is attributed to that field — this
  * matches `ChatAdapter.extractField`'s single-output fallback.
  */
final class ChatStreamingState(outputFields: Vector[FieldSpec]) extends AdapterStreamingState:

  private val labels: Vector[(String, FieldSpec)] = outputFields.map { field =>
    val rawLabel = field.prefix.getOrElse(s"${field.name}:")
    rawLabel.trim.toLowerCase -> field
  }
  private val singleFieldFallback: Option[FieldSpec] =
    Option.when(outputFields.size == 1)(outputFields.head)

  private val buffer = new StringBuilder
  private var currentField: Option[FieldSpec] = None
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

  /** Iteratively scans the buffer for the next label boundary or safely
    * flushable region and trims everything emitted from the buffer. */
  private def drain(forceFlush: Boolean): Vector[FieldChunk] =
    val out = mutable.ArrayBuffer.empty[FieldChunk]
    var done = false
    while !done do
      findLabel(buffer.toString) match
        case Some(LabelHit(field, labelStart, valueStart)) =>
          val preceding = buffer.substring(0, labelStart)
          currentField.orElse(singleFieldFallback).foreach { f =>
            if preceding.nonEmpty then
              out += FieldChunk(f.name, stripTrailingNewline(preceding), isLast = true)
          }
          buffer.delete(0, valueStart)
          currentField = Some(field)
          // Loop again — there may be more labels already in the buffer.
        case None =>
          currentField.orElse(singleFieldFallback).foreach { field =>
            val text = buffer.toString
            if forceFlush then
              if text.nonEmpty then
                out += FieldChunk(field.name, text, isLast = false)
                buffer.clear()
            else
              // Mid-stream: emit up to — but not including — the last newline.
              // The newline itself is kept in the buffer so it can act as a
              // line boundary for the next label scan; if it turns out to be
              // structural (followed by a label), `stripTrailingNewline` on
              // the label-hit path drops it.
              val lastNl = text.lastIndexOf('\n')
              if lastNl > 0 then
                out += FieldChunk(field.name, text.substring(0, lastNl), isLast = false)
                buffer.delete(0, lastNl)
          }
          done = true
    out.toVector

  private def findLabel(text: String): Option[LabelHit] =
    val lower = text.toLowerCase
    var result: Option[LabelHit] = None
    var i = 0
    while result.isEmpty && i < lower.length do
      val lineStart = i
      // Advance past leading whitespace on the line (spaces, tabs only — not
      // newlines, since those delimit lines).
      var j = lineStart
      while j < lower.length && (lower.charAt(j) == ' ' || lower.charAt(j) == '\t') do j += 1
      val matched = labels.collectFirst {
        case (label, field) if lower.startsWith(label, j) => field -> (j + label.length)
      }
      matched match
        case Some((field, valueStart)) =>
          // Skip a single space following the label so the emitted value
          // chunk does not begin with the separator.
          val skippedSpace =
            if valueStart < text.length && text.charAt(valueStart) == ' ' then valueStart + 1
            else valueStart
          result = Some(LabelHit(field, labelStart = lineStart, valueStart = skippedSpace))
        case None =>
          val nextNewline = lower.indexOf('\n', i)
          if nextNewline < 0 then i = lower.length
          else i = nextNewline + 1
    result

  private def stripTrailingNewline(s: String): String =
    if s.endsWith("\n") then s.dropRight(1) else s

  private final case class LabelHit(field: FieldSpec, labelStart: Int, valueStart: Int)
