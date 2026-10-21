package dspy4s.adapters

import dspy4s.adapters.contracts.{NativeFunctionCalling, ParsedOutput}
import dspy4s.adapters.internal.{AdapterTextSupport, PythonText}
import dspy4s.core.contracts.{DspyError, FieldSpec, SignatureLayout}
import dspy4s.lm.contracts.LmOutput
import zio.blocks.schema.DynamicValue

/** Decodes ChatAdapter's marker-framed completion and provider-native tool calls. */
private[adapters] object ChatAdapterParser:

  /** @param nativeCalls
    *   `true` when native function calling is active for the call. The provider tool calls then fill the tool calls
    *   field. They also fill it when the response has provider tool calls (upstream `_call_postprocess`). Else the
    *   field is a text field.
    */
  def parse(
      adapterName: String,
      layout     : SignatureLayout,
      output     : LmOutput,
      nativeCalls: Boolean
  ): Either[DspyError, ParsedOutput] =
    val providerCalls                           = nativeCalls || output.toolCalls.nonEmpty
    def fromProvider(field: FieldSpec): Boolean = providerCalls && NativeFunctionCalling.isToolCallsField(field)

    val textFields  = layout.outputFields.filterNot(fromProvider)
    val outputNames = textFields.map(_.name).toSet
    val values      = extractSections(output.text, outputNames)

    // The single-output fallback is a port rule: upstream `ChatAdapter.parse` has none. It strips the text with
    // `String.strip`, as for the other values. Thus the joined stream chunks of the field are this value
    // (`ChatStreamingState`).
    val resolved =
      if values.isEmpty && textFields.size == 1 && output.text.strip.nonEmpty then
        Map(textFields.head.name -> output.text.strip)
      else
        values

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
      .map(entries => AdapterTextSupport.parsedOutput(adapterName, output, entries))

  /** The text of each output field. The first header of a field wins (upstream `ChatAdapter.parse`): the lines of a
    * later header of the same field are dropped. The lines come from Python `str.splitlines`, as upstream, so a CRLF
    * reply gives the values of an LF reply. Each text is stripped at both ends, as upstream (`"\n".join(v).strip()`).
    */
  private def extractSections(text: String, outputNames: Set[String]): Map[String, String] =
    val out                          = scala.collection.mutable.LinkedHashMap.empty[String, StringBuilder]
    var currentField: Option[String] = None
    PythonText
      .splitLines(text)
      .foreach { rawLine =>
        val stripped = rawLine.trim
        ChatAdapter.MarkerPattern.findPrefixMatchOf(stripped) match
          case Some(marker) =>
            val fieldName = marker.group(1)
            val trailing  = stripped.substring(marker.end).trim
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
        name -> value.toString.strip
      }
      .toMap
