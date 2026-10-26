package dspy4s.adapters

import dspy4s.adapters.contracts.FieldChunk
import dspy4s.adapters.internal.{JsonTextScan, SingleUseAdapterStreamingState}
import dspy4s.core.contracts.FieldSpec

import scala.collection.mutable
import scala.util.control.NonFatal

/** Streaming state machine for [[JSONAdapter]] output.
  *
  * Parses a streamed top-level JSON object character by character and emits per-field value fragments as they arrive.
  * Behaviour:
  *
  *   - Any preamble before the first `{` is skipped, so fenced ```json blocks are tolerated.
  *   - String values are emitted with their escape sequences decoded (`\n`, `\t`, `\"`, `\\`, `\/`, `\uXXXX`, …). The
  *     surrounding quotes are stripped.
  *   - Non-string scalars (numbers, booleans, `null`) emit their literal textual form, trimmed of surrounding
  *     whitespace at value boundaries.
  *   - Nested object / array values emit the raw sub-document, tracking brace / bracket nesting and string state so
  *     internal `{` or `,` characters do not terminate the value early.
  *   - Keys not present in the signature's output fields are parsed but produce no [[FieldChunk]]s. The state buffers
  *     each value, and only the emission checks the key.
  *   - `finish()` flushes any in-progress field with `isLast = true` — useful when the model stops mid-value or omits
  *     the closing brace.
  *
  * Designed for incremental input: every receive boundary is safe — partial `\uXXXX` escapes, half-buffered keys, and
  * mid-value pauses all resume cleanly on the next call.
  */
final class JsonStreamingState(outputFields: Vector[FieldSpec]) extends SingleUseAdapterStreamingState:
  private val fieldNames: Set[String] = outputFields.map(_.name).toSet

  private enum Phase derives CanEqual:
    case PreObj, ObjStart, InKey, AfterKey, BeforeValue, InStringValue, InOtherValue, AfterValue, PostObj

  import Phase.*

  private var phase: Phase             = PreObj
  private val keyBuilder               = new StringBuilder
  private var currentKey: String       = ""
  private val contentBuffer            = new StringBuilder
  private var currentIsString: Boolean = false

  // String-value sub-state.
  private var stringEscape: Boolean = false
  private var unicodeRemaining: Int = 0
  private val unicodeBuf            = new StringBuilder

  // Other-value sub-state: the nesting depth, and the strings in the value.
  private var otherDepth: Int = 0
  private val otherScan       = new JsonTextScan

  override protected def receiveOpen(delta: String): Vector[FieldChunk] =
    val out = mutable.ArrayBuffer.empty[FieldChunk]
    var i   = 0
    while i < delta.length do
      processChar(delta.charAt(i), out)
      i += 1
    // Flush a tracked string value's decoded content accumulated so far as a partial chunk, so long string
    // values stream token by token instead of arriving as one final chunk at the closing quote. Pending
    // escape state (`stringEscape` / `unicodeRemaining`) lives outside the buffer, so this is boundary-safe.
    if phase == InStringValue && isCurrentTracked && contentBuffer.nonEmpty then
      out += FieldChunk(currentKey, contentBuffer.toString, isLast = false)
      contentBuffer.clear()
    out.toVector

  override protected def finishOpen(): Vector[FieldChunk] =
    val out = mutable.ArrayBuffer.empty[FieldChunk]
    // A tracked value still open at stream end needs its isLast terminator even when the buffer is empty —
    // string content may have already been flushed incrementally by receive().
    if phase == InStringValue || phase == InOtherValue then emitFinal(out)
    out.toVector

  private def isCurrentTracked: Boolean = fieldNames.contains(currentKey)

  private def emitFinal(out: mutable.ArrayBuffer[FieldChunk]): Unit =
    if isCurrentTracked then
      out += FieldChunk(currentKey, finalContent(), isLast = true)
    contentBuffer.clear()

  private def finalContent(): String =
    if currentIsString then contentBuffer.toString else contentBuffer.toString.strip

  private def processChar(c: Char, out: mutable.ArrayBuffer[FieldChunk]): Unit =
    phase match
      case PreObj   => if c == '{' then phase = ObjStart
      case ObjStart => c match
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
        else
          c match
            case '\\' => stringEscape = true
            case '"'  =>
              currentKey = keyBuilder.toString
              phase = AfterKey
            case _ => keyBuilder.append(c)
      case AfterKey => c match
          case ' ' | '\t' | '\r' | '\n' => ()
          case ':'                      => phase = BeforeValue
          case _                        => ()
      case BeforeValue => c match
          case ' ' | '\t' | '\r' | '\n' => ()
          case '"'                      =>
            contentBuffer.clear()
            stringEscape = false
            unicodeRemaining = 0
            unicodeBuf.clear()
            currentIsString = true
            phase = InStringValue
          case _ =>
            // A value that is not a string. An object or a list starts at depth one.
            contentBuffer.clear()
            contentBuffer.append(c)
            otherDepth = if c == '{' || c == '[' then 1 else 0
            otherScan.reset()
            currentIsString = false
            phase = InOtherValue
      case InStringValue => handleStringValueChar(c, out)
      case InOtherValue  => handleOtherValueChar(c, out)
      case AfterValue    => c match
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
        // A malformed `\u` escape (non-hex digits) must degrade to the literal text, not throw out of the
        // stream — mirroring XmlStreamingState.decodeEntity's NonFatal guard.
        try
          val _ = contentBuffer.append(Integer.parseInt(unicodeBuf.toString, 16).toChar)
        catch
          case NonFatal(_) => val _ = contentBuffer.append("\\u").append(unicodeBuf)
        unicodeBuf.clear()
    else if stringEscape then
      stringEscape = false
      if c == 'u' then
        unicodeBuf.clear()
        unicodeRemaining = 4
      else contentBuffer.append(unescapeChar(c))
    else
      c match
        case '\\' => stringEscape = true
        case '"'  =>
          emitFinal(out)
          phase = AfterValue
        case _ => contentBuffer.append(c)

  private def handleOtherValueChar(c: Char, out: mutable.ArrayBuffer[FieldChunk]): Unit =
    val structural = otherScan.structural(c)
    if structural && otherDepth == 0 && (c == '}' || c == ',') then
      // At depth 0, `}` closes the outer object and `,` comes before the next key: each ends this value.
      emitFinal(out)
      phase = if c == '}' then PostObj else ObjStart
    else
      if structural then
        c match
          case '{' | '['                   => otherDepth += 1
          case '}' | ']' if otherDepth > 0 => otherDepth -= 1
          case _                           => ()
      contentBuffer.append(c)

  private def unescapeChar(c: Char): Char =
    c match
      case 'n'   => '\n'
      case 't'   => '\t'
      case 'r'   => '\r'
      case 'b'   => '\b'
      case 'f'   => '\f'
      case '"'   => '"'
      case '\\'  => '\\'
      case '/'   => '/'
      case other => other
