package dspy4s.adapters

import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FieldChunk
import dspy4s.core.contracts.FieldSpec

import scala.collection.mutable
import scala.util.control.NonFatal

/** Streaming state machine for [[XMLAdapter]] output.
  *
  * Parses a streamed XML document of the form
  * `<outputs><field1>v1</field1><field2>v2</field2></outputs>` character by
  * character. Any preamble before the first `<` is skipped, so fenced
  * ```xml blocks are tolerated. Tags not matching a declared output field
  * (including the `<outputs>` wrapper itself) are walked through without
  * emission. Named entities (`&amp;`, `&lt;`, `&gt;`, `&quot;`, `&apos;`)
  * and numeric character references (`&#nnn;`, `&#xHHHH;`) inside content
  * are decoded inline. `finish()` flushes any in-progress field with
  * `isLast = true`.
  *
  * Designed for incremental input: receive boundaries that split a tag
  * name, an entity, or content are all resumed cleanly on the next call.
  */
final class XmlStreamingState(outputFields: Vector[FieldSpec]) extends AdapterStreamingState:
  private val fieldNames: Set[String] = outputFields.map(_.name).toSet

  private enum Phase:
    case PreDoc, BetweenTags, TagStart, ReadingOpenTag, SkippingAttrs,
         ReadingCloseTag, InContent, InContentSeenLt, InEntity, PostDoc

  import Phase.*

  private var phase: Phase = PreDoc
  private var currentField: Option[String] = None
  private val tagBuilder = new StringBuilder
  private val contentBuffer = new StringBuilder
  private val entityBuilder = new StringBuilder
  private var pendingTagMatch: Boolean = false
  private var finished: Boolean = false

  override def receive(delta: String): Vector[FieldChunk] =
    if finished || delta.isEmpty then Vector.empty
    else
      val out = mutable.ArrayBuffer.empty[FieldChunk]
      var i = 0
      while i < delta.length do
        processChar(delta.charAt(i), out)
        i += 1
      out.toVector

  override def finish(): Vector[FieldChunk] =
    if finished then Vector.empty
    else
      finished = true
      val out = mutable.ArrayBuffer.empty[FieldChunk]
      // If the model stopped mid-content, flush whatever we collected so far.
      if currentField.isDefined && contentBuffer.nonEmpty then
        emitFinal(out)
      out.toVector

  private def emitFinal(out: mutable.ArrayBuffer[FieldChunk]): Unit =
    currentField.foreach { name =>
      out += FieldChunk(name, contentBuffer.toString, isLast = true)
    }
    contentBuffer.clear()
    currentField = None
    pendingTagMatch = false

  private def processChar(c: Char, out: mutable.ArrayBuffer[FieldChunk]): Unit =
    phase match
      case PreDoc =>
        if c == '<' then phase = TagStart
      case BetweenTags =>
        if c == '<' then phase = TagStart
        // else: skip whitespace / stray chars between tags
      case TagStart =>
        c match
          case '/' =>
            tagBuilder.clear()
            phase = ReadingCloseTag
          case _ if isNameStart(c) =>
            tagBuilder.clear()
            tagBuilder.append(c)
            phase = ReadingOpenTag
          case _ =>
            // Unrecognized — `<?xml ... ?>`, `<!-- ... -->`, etc. Skip to next '>'.
            phase = SkippingAttrs
            pendingTagMatch = false
      case ReadingOpenTag =>
        if isNameChar(c) then tagBuilder.append(c)
        else
          val tag = tagBuilder.toString
          pendingTagMatch = fieldNames.contains(tag)
          if pendingTagMatch then
            currentField = Some(tag)
            contentBuffer.clear()
          c match
            case '>' =>
              phase = if pendingTagMatch then InContent else BetweenTags
            case _ =>
              phase = SkippingAttrs
      case SkippingAttrs =>
        if c == '>' then
          phase = if pendingTagMatch then InContent else BetweenTags
        // else: skip attribute chars, including self-closing '/'
      case ReadingCloseTag =>
        c match
          case '>' =>
            val tag = tagBuilder.toString
            if currentField.contains(tag) then emitFinal(out)
            phase = BetweenTags
          case ' ' | '\t' | '\r' | '\n' => () // tolerate whitespace before '>'
          case _ => tagBuilder.append(c)
      case InContent =>
        c match
          case '<' => phase = InContentSeenLt
          case '&' =>
            entityBuilder.clear()
            phase = InEntity
          case _ => contentBuffer.append(c)
      case InContentSeenLt =>
        if c == '/' then
          tagBuilder.clear()
          phase = ReadingCloseTag
        else
          // Treat '<x' as literal content — the model emitted '<' without
          // escaping it. (Nested non-output tags inside a field aren't
          // expected from the dspy4s XML adapter.)
          contentBuffer.append('<')
          contentBuffer.append(c)
          phase = InContent
      case InEntity =>
        if c == ';' then
          contentBuffer.append(decodeEntity(entityBuilder.toString))
          phase = InContent
        else if isEntityChar(c) then entityBuilder.append(c)
        else
          // Malformed entity: emit `&` + collected + the char as literal.
          contentBuffer.append('&')
          contentBuffer.append(entityBuilder.toString)
          contentBuffer.append(c)
          phase = InContent
      case PostDoc => ()

  private def isNameStart(c: Char): Boolean = c.isLetter || c == '_'
  private def isNameChar(c: Char): Boolean = c.isLetterOrDigit || c == '_' || c == '-' || c == '.'
  private def isEntityChar(c: Char): Boolean = c.isLetterOrDigit || c == '#'

  private def decodeEntity(name: String): String = name match
    case "amp"  => "&"
    case "lt"   => "<"
    case "gt"   => ">"
    case "quot" => "\""
    case "apos" => "'"
    case s if s.startsWith("#x") || s.startsWith("#X") =>
      try Character.toString(Integer.parseInt(s.drop(2), 16))
      catch case NonFatal(_) => s"&$name;"
    case s if s.startsWith("#") =>
      try Character.toString(Integer.parseInt(s.drop(1)))
      catch case NonFatal(_) => s"&$name;"
    case _ => s"&$name;"
