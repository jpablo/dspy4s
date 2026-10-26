package dspy4s.adapters

import dspy4s.adapters.contracts.{NativeFunctionCalling, ParsedOutput}
import dspy4s.adapters.internal.AdapterTextSupport
import dspy4s.core.contracts.{DspyError, DynamicValues, FieldSpec, PythonText, SignatureLayout}
import dspy4s.lm.contracts.LmOutput
import zio.blocks.schema.DynamicValue

/** Decodes ChatAdapter's marker-framed completion and provider-native tool calls. */
private[adapters] object ChatAdapterParser:

  /** @param nativeCalls
    *   `true` when native function calling is active for the call. The provider tool calls then fill the tool calls
    *   field. They also fill it when the response has provider tool calls (upstream `_call_postprocess`). Else the
    *   field is a text field.
    */
  def parse(layout: SignatureLayout, output: LmOutput, nativeCalls: Boolean): Either[DspyError, ParsedOutput] =
    val providerCalls                           = nativeCalls || output.toolCalls.nonEmpty
    def fromProvider(field: FieldSpec): Boolean = providerCalls && NativeFunctionCalling.isToolCallsField(field)

    val fields      = textFields(layout, providerCalls)
    val outputNames = fields.map(_.name).toSet
    val values      = extractSections(output.text, outputNames)

    // The single-output fallback is a port rule: upstream `ChatAdapter.parse` has none. The value has the line ends of
    // a section value (the lines of Python `str.splitlines`, joined with `\n`), and the parser strips it with Python
    // `str.strip`, as for the other values. Thus the joined stream chunks of the field are this value
    // (`ChatStreamingState`).
    val resolved =
      if values.isEmpty && fields.size == 1 && PythonText.strip(output.text).nonEmpty then
        Map(fields.head.name -> fallbackText(output.text))
      else values

    AdapterTextSupport
      .decodeOutputFields(layout, output.text) { field =>
        if fromProvider(field) then
          Right(Some(NativeFunctionCalling.encodeToolCalls(output.toolCalls)))
        else
          resolved.get(field.name) match
            case Some(value) => AdapterTextSupport.outputTextValue(field, value).map(Some(_))
            // A tool turn has no final answer yet: a missing field takes its default, else a null.
            case None if output.toolCalls.nonEmpty => Right(Some(field.defaultValue.getOrElse(DynamicValue.Null)))
            case None                              => Right(None)
      }
      .map(entries => ParsedOutput(DynamicValues.recordFromEntries(entries)))

  /** The output fields of `layout` that the text of the reply gives: each output field, less the tool calls field when
    * `providerCalls` is `true`. The provider tool calls then fill that field. [[ChatStreamingState]] gets the same
    * fields, so the stream follows the single-output fallback of the parser.
    */
  def textFields(layout: SignatureLayout, providerCalls: Boolean): Vector[FieldSpec] =
    layout.outputFields.filterNot(field => providerCalls && NativeFunctionCalling.isToolCallsField(field))

  /** The value of the single-output fallback for the whole text `text` of a reply with no header: the lines of `text`
    * (Python `str.splitlines`), joined with `\n`, and stripped with Python `str.strip`. A section value has the same
    * line ends ([[extractSections]]), so a CRLF reply gives the value of an LF reply.
    */
  def fallbackText(text: String): String =
    PythonText.strip(PythonText.splitLines(text).mkString("\n"))

  /** The text of each output field. The first header of a field wins (upstream `ChatAdapter.parse`): the lines of a
    * later header of the same field are dropped. The lines come from Python `str.splitlines`, as upstream, so a CRLF
    * reply gives the values of an LF reply. Each text is stripped at both ends with Python `str.strip`, as upstream
    * (`"\n".join(v).strip()`).
    *
    * A header starts a line after Python whitespace only (upstream `field_header_pattern.match(line.strip())`). The
    * rest of the line after the header is the first text of the field. Upstream cuts that rest from the line before the
    * strip (`line[match.end():]`), so after leading whitespace it keeps a part of the header. The port cuts it from the
    * stripped line.
    */
  private def extractSections(text: String, outputNames: Set[String]): Map[String, String] =
    val out                          = scala.collection.mutable.LinkedHashMap.empty[String, StringBuilder]
    var currentField: Option[String] = None
    PythonText
      .splitLines(text)
      .foreach { rawLine =>
        val stripped = PythonText.strip(rawLine)
        ChatAdapter.MarkerPattern.findPrefixMatchOf(stripped) match
          case Some(marker) =>
            val fieldName = marker.group(1)
            val trailing  = PythonText.strip(stripped.substring(marker.end))
            if fieldName == ChatAdapter.CompletedFieldName then
              currentField = None
            else if outputNames.contains(fieldName) && !out.contains(fieldName) then
              out.update(fieldName, new StringBuilder(trailing))
              currentField = Some(fieldName)
            else
              currentField = None
          case None => currentField.foreach { name =>
              val builder = out(name)
              if builder.nonEmpty then
                builder.append('\n')
              builder.append(rawLine)
            }
      }
    out
      .iterator
      .map { (name, value) =>
        name -> PythonText.strip(value.toString)
      }
      .toMap
