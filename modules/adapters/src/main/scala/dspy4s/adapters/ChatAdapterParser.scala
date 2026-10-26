package dspy4s.adapters

import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.adapters.internal.AdapterTextSupport
import dspy4s.core.contracts.{DspyError, FieldSpec, PythonText, SignatureLayout}
import dspy4s.lm.contracts.LmOutput
import zio.blocks.schema.DynamicValue

/** Decodes ChatAdapter's marker-framed completion and provider-native tool calls. */
private[adapters] object ChatAdapterParser:

  /** The values of the output fields of `layout`, with the tool calls rule of [[AdapterTextSupport.parseOutputs]].
    *
    * @param nativeCalls
    *   `true` when native function calling is active for the call. The provider tool calls then fill the tool calls
    *   field. They also fill it when the response has provider tool calls (upstream `_call_postprocess`). Else the
    *   field is a text field.
    */
  def parse(layout: SignatureLayout, output: LmOutput, nativeCalls: Boolean): Either[DspyError, ParsedOutput] =
    AdapterTextSupport.parseOutputs(layout, output, nativeCalls)(parseText(_, output))

  /** The values of the text output fields `fields` from the sections of the text of `output`.
    *
    * The single-output fallback is a port rule: upstream `ChatAdapter.parse` has none. With one text field and no
    * header of that field, the whole text is the text of the value ([[AdapterTextSupport.singleOutputTextFallback]]).
    * The value has the line ends of a section value, and the parser strips it with Python `str.strip`, as for the other
    * values. Thus the joined stream chunks of the field are this value (`ChatStreamingState`).
    */
  private def parseText(fields: Vector[FieldSpec], output: LmOutput): Either[DspyError, DynamicValue.Record] =
    val values = extractSections(output.text, fields.map(_.name).toSet)
    if values.isEmpty && fields.size == 1 && PythonText.strip(output.text).nonEmpty then
      AdapterTextSupport.singleOutputTextFallback(fields.head, output)
    else
      AdapterTextSupport.decodeOutputFields(fields, output.text) { field =>
        values.get(field.name) match
          case Some(value) => AdapterTextSupport.outputTextValue(field, value).map(Some(_))
          case None        => Right(None)
      }

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
