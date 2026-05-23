package dspy4s.adapters

import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FieldChunk
import dspy4s.core.contracts.FieldSpec

import scala.collection.mutable

/** Streaming state machine for [[JSONAdapter]] output.
  *
  * Parses a streamed top-level JSON object character by character and emits
  * per-field value fragments as they arrive. Behaviour:
  *
  *   - Any preamble before the first `{` is skipped, so fenced ```json blocks
  *     are tolerated.
  *   - String values are emitted with their escape sequences decoded
  *     (`\n`, `\t`, `\"`, `\\`, `\/`, `\uXXXX`, …). The surrounding quotes
  *     are stripped.
  *   - Non-string scalars (numbers, booleans, `null`) emit their literal
  *     textual form, trimmed of surrounding whitespace at value boundaries.
  *   - Nested object / array values emit the raw sub-document, tracking
  *     brace / bracket nesting and string state so internal `{` or `,`
  *     characters do not terminate the value early.
  *   - Keys not present in the signature's output fields are parsed but
  *     produce no [[FieldChunk]]s.
  *   - `finish()` flushes any in-progress field with `isLast = true` —
  *     useful when the model stops mid-value or omits the closing brace.
  *
  * Designed for incremental input: every receive boundary is safe — partial
  * `\uXXXX` escapes, half-buffered keys, and mid-value pauses all resume
  * cleanly on the next call.
  */
final class JsonStreamingState(outputFields: Vector[FieldSpec]) extends AdapterStreamingState:
  private val fieldNames: Set[String] = outputFields.map(_.name).toSet

  private enum Phase:
    case PreObj, ObjStart, InKey, AfterKey, BeforeValue, InStringValue, InOtherValue, AfterValue, PostObj

  import Phase.*

  private var phase: Phase = PreObj
  private val keyBuilder = new StringBuilder
  private var currentKey: String = ""
  private val contentBuffer = new StringBuilder
  private var currentIsString: Boolean = false

  // String-value sub-state.
  private var stringEscape: Boolean = false
  private var unicodeRemaining: Int = 0
  private val unicodeBuf = new StringBuilder

  // Other-value sub-state.
  private var otherDepth: Int = 0
  private var otherInString: Boolean = false
  private var otherStringEscape: Boolean = false

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
      if isInValuePhase && contentBuffer.nonEmpty && isCurrentTracked then
        out += FieldChunk(currentKey, finalContent(), isLast = true)
        contentBuffer.clear()
      out.toVector

  private def isCurrentTracked: Boolean = fieldNames.contains(currentKey)

  private def isInValuePhase: Boolean =
    phase == InStringValue || phase == InOtherValue

  private def emitFinal(out: mutable.ArrayBuffer[FieldChunk]): Unit =
    if isCurrentTracked then
      out += FieldChunk(currentKey, finalContent(), isLast = true)
    contentBuffer.clear()

  private def finalContent(): String =
    if currentIsString then contentBuffer.toString else contentBuffer.toString.strip

  private def processChar(c: Char, out: mutable.ArrayBuffer[FieldChunk]): Unit =
    phase match
      case PreObj =>
        if c == '{' then phase = ObjStart
      case ObjStart =>
        c match
          case ' ' | '\t' | '\r' | '\n' => ()
          case '}'                      => phase = PostObj
          case '"'                      =>
            keyBuilder.clear()
            stringEscape = false
            phase = InKey
          case _ => () // tolerate unexpected characters
      case InKey =>
        if stringEscape then
          keyBuilder.append(unescapeChar(c))
          stringEscape = false
        else c match
          case '\\' => stringEscape = true
          case '"'  =>
            currentKey = keyBuilder.toString
            phase = AfterKey
          case _    => keyBuilder.append(c)
      case AfterKey =>
        c match
          case ' ' | '\t' | '\r' | '\n' => ()
          case ':'                      => phase = BeforeValue
          case _                        => ()
      case BeforeValue =>
        c match
          case ' ' | '\t' | '\r' | '\n' => ()
          case '"' =>
            contentBuffer.clear()
            stringEscape = false
            unicodeRemaining = 0
            unicodeBuf.clear()
            currentIsString = true
            phase = InStringValue
          case '{' | '[' =>
            contentBuffer.clear()
            contentBuffer.append(c)
            otherDepth = 1
            otherInString = false
            otherStringEscape = false
            currentIsString = false
            phase = InOtherValue
          case _ =>
            contentBuffer.clear()
            contentBuffer.append(c)
            otherDepth = 0
            otherInString = false
            otherStringEscape = false
            currentIsString = false
            phase = InOtherValue
      case InStringValue  => handleStringValueChar(c, out)
      case InOtherValue   => handleOtherValueChar(c, out)
      case AfterValue =>
        c match
          case ' ' | '\t' | '\r' | '\n' => ()
          case ','                      => phase = ObjStart
          case '}'                      => phase = PostObj
          case _                        => ()
      case PostObj => ()

  private def handleStringValueChar(c: Char, out: mutable.ArrayBuffer[FieldChunk]): Unit =
    if unicodeRemaining > 0 then
      unicodeBuf.append(c)
      unicodeRemaining -= 1
      if unicodeRemaining == 0 then
        if isCurrentTracked then
          val cp = Integer.parseInt(unicodeBuf.toString, 16)
          contentBuffer.append(cp.toChar)
        unicodeBuf.clear()
    else if stringEscape then
      stringEscape = false
      if c == 'u' then
        unicodeBuf.clear()
        unicodeRemaining = 4
      else if isCurrentTracked then
        contentBuffer.append(unescapeChar(c))
    else c match
      case '\\' => stringEscape = true
      case '"'  =>
        emitFinal(out)
        phase = AfterValue
      case _ =>
        if isCurrentTracked then contentBuffer.append(c)

  private def handleOtherValueChar(c: Char, out: mutable.ArrayBuffer[FieldChunk]): Unit =
    if otherStringEscape then
      otherStringEscape = false
      if isCurrentTracked then contentBuffer.append(c)
    else if otherInString then
      c match
        case '\\' =>
          otherStringEscape = true
          if isCurrentTracked then contentBuffer.append(c)
        case '"' =>
          otherInString = false
          if isCurrentTracked then contentBuffer.append(c)
        case _ =>
          if isCurrentTracked then contentBuffer.append(c)
    else c match
      case '"' =>
        otherInString = true
        if isCurrentTracked then contentBuffer.append(c)
      case '{' | '[' =>
        otherDepth += 1
        if isCurrentTracked then contentBuffer.append(c)
      case ']' if otherDepth > 0 =>
        otherDepth -= 1
        if isCurrentTracked then contentBuffer.append(c)
      case '}' if otherDepth > 0 =>
        otherDepth -= 1
        if isCurrentTracked then contentBuffer.append(c)
      case '}' =>
        // depth is 0 → this closes the outer object and ends this value.
        emitFinal(out)
        phase = PostObj
      case ',' if otherDepth == 0 =>
        emitFinal(out)
        phase = ObjStart
      case _ =>
        if isCurrentTracked then contentBuffer.append(c)

  private def unescapeChar(c: Char): Char = c match
    case 'n'  => '\n'
    case 't'  => '\t'
    case 'r'  => '\r'
    case 'b'  => '\b'
    case 'f'  => '\f'
    case '"'  => '"'
    case '\\' => '\\'
    case '/'  => '/'
    case other => other
