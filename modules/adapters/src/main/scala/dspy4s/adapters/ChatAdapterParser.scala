package dspy4s.adapters

import dspy4s.adapters.contracts.{AdapterErrors, NativeFunctionCalling, ParsedOutput}
import dspy4s.adapters.internal.AdapterTextSupport
import dspy4s.core.contracts.{DspyError, SignatureLayout, TypeRef}
import dspy4s.lm.contracts.LmOutput
import zio.blocks.schema.DynamicValue

/** Decodes ChatAdapter's marker-framed completion and provider-native tool calls. */
private[adapters] object ChatAdapterParser:
  def parse(adapterName: String, layout: SignatureLayout, output: LmOutput): Either[DspyError, ParsedOutput] =
    val textFields  = layout.outputFields.filterNot(NativeFunctionCalling.isToolCallsField)
    val outputNames = textFields.map(_.name).toSet
    val sections    = extractSections(output.text, outputNames)
    val values      = sections.view.filterKeys(outputNames.contains).toMap

    val resolved =
      if values.isEmpty && textFields.size == 1 && output.text.trim.nonEmpty then
        Map(textFields.head.name -> output.text.trim)
      else
        values

    AdapterTextSupport
      .decodeOutputFields(layout) { field =>
        if NativeFunctionCalling.isToolCallsField(field) then
          Right(NativeFunctionCalling.encodeToolCalls(output.toolCalls))
        else
          resolved.get(field.name) match
            case Some(value) => coerce(field.typeRef, value)
            case None        =>
              if output.toolCalls.nonEmpty then
                Right(DynamicValue.Null)
              else
                Left(AdapterErrors.missingField(field.name, Some(output.text)))
      }
      .map(entries => AdapterTextSupport.parsedOutput(adapterName, output, entries))

  private def extractSections(text: String, outputNames: Set[String]): Map[String, String] =
    val out                          = scala.collection.mutable.LinkedHashMap.empty[String, StringBuilder]
    var currentField: Option[String] = None
    text
      .split('\n')
      .foreach { rawLine =>
        val stripped = rawLine.trim
        ChatAdapter.MarkerPattern.findPrefixMatchOf(stripped) match
          case Some(marker) =>
            val fieldName = marker.group(1)
            val trailing  = stripped.substring(marker.end).trim
            if fieldName == ChatAdapter.CompletedFieldName then
              currentField = None
            else if outputNames.contains(fieldName) then
              val builder = out.getOrElseUpdate(fieldName, new StringBuilder)
              if trailing.nonEmpty then
                if builder.nonEmpty then
                  builder.append('\n')
                builder.append(trailing)
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
        name -> value.toString.stripTrailing
      }
      .toMap

  private def coerce(typeRef: TypeRef, raw: String): Either[DspyError, DynamicValue] =
    AdapterTextSupport.coerceText(
      typeRef,
      raw
    )
