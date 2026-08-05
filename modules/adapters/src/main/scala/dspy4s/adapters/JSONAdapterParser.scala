package dspy4s.adapters

import dspy4s.adapters.contracts.{AdapterErrors, NativeFunctionCalling, ParsedOutput}
import dspy4s.adapters.internal.{AdapterTextSupport, JsonDynamic}
import dspy4s.core.contracts.{DspyError, ParseError, SignatureLayout, TypeRef, ValidationError}
import dspy4s.lm.contracts.LmOutput
import ujson.Value
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

import scala.util.Try
import scala.util.matching.Regex

/** Decodes JSONAdapter's JSON body and provider-native tool calls into typed dynamic fields. */
private[adapters] object JSONAdapterParser:
  private val FencedJson: Regex = "(?s)```json\\s*(\\{.*?\\})\\s*```".r

  def parse(
      adapterName                     : String,
      allowTextFallbackForSingleOutput: Boolean,
      layout                          : SignatureLayout,
      output                          : LmOutput
  ): Either[DspyError, ParsedOutput] =
    if layout.outputFields.exists(NativeFunctionCalling.isToolCallsField) && output.toolCalls.nonEmpty then
      Right(parseNativeToolTurn(adapterName, layout, output))
    else
      extractJson(output.text).flatMap(parseJsonObject) match
        case Right(root) => parseFields(adapterName, layout, root, output)
        case Left(_)     =>
          if allowTextFallbackForSingleOutput && layout.outputFields.size == 1 then
            AdapterTextSupport.singleOutputTextFallback(adapterName, layout, output)
          else
            Left(ParseError("adapter", "JSON parse failed and no fallback was applied", raw = Some(output.text)))

  private def parseNativeToolTurn(adapterName: String, layout: SignatureLayout, output: LmOutput): ParsedOutput =
    val jsonRoot: Option[Value] = extractJson(output.text).flatMap(parseJsonObject).toOption
    val entries                 = layout
      .outputFields
      .map { field =>
        if NativeFunctionCalling.isToolCallsField(field) then
          field.name -> NativeFunctionCalling.encodeToolCalls(output.toolCalls)
        else
          jsonRoot
            .flatMap(_.objOpt.flatMap(_.get(field.name)))
            .flatMap(raw => coerce(field.typeRef, raw).toOption)
            .map(coerced => field.name -> coerced)
            .getOrElse(field.name -> DynamicValue.Null)
      }
    AdapterTextSupport.parsedOutput(adapterName, output, entries)

  private def parseFields(
      adapterName: String,
      layout     : SignatureLayout,
      root       : Value,
      output     : LmOutput
  ): Either[DspyError, ParsedOutput] =
    AdapterTextSupport
      .decodeOutputFields(layout) { field =>
        root.obj.get(field.name) match
          case Some(raw) => coerce(field.typeRef, raw)
          case None      => Left(AdapterErrors.missingField(field.name, Some(output.text)))
      }
      .map(entries => AdapterTextSupport.parsedOutput(adapterName, output, entries))

  private def extractJson(text: String): Either[DspyError, String] =
    val trimmed = text.trim
    if trimmed.startsWith("{") && trimmed.endsWith("}") then
      Right(trimmed)
    else
      FencedJson.findFirstMatchIn(text).map(_.group(1)) match
        case Some(json) => Right(json)
        case None       =>
          extractFirstJsonObject(text).toRight(ParseError("adapter", "Could not find JSON object in model output"))

  private def extractFirstJsonObject(text: String): Option[String] =
    val start = text.indexOf('{')
    if start < 0 then
      None
    else
      var depth    = 0
      var end      = -1
      var i        = start
      var inString = false
      var escaped  = false
      while i < text.length && end < 0 do
        val char = text.charAt(i)
        if inString then
          if escaped then
            escaped = false
          else if char == '\\' then
            escaped = true
          else if char == '"' then
            inString = false
        else
          char match
            case '"' => inString = true
            case '{' => depth += 1
            case '}' =>
              depth -= 1
              if depth == 0 then
                end = i
            case _ => ()
        i += 1
      if end >= start then
        Some(text.substring(start, end + 1))
      else
        None

  private def parseJsonObject(raw: String): Either[DspyError, Value] =
    Try(ujson.read(raw))
      .toEither
      .left
      .map(error => ParseError("adapter", error.getMessage))
      .flatMap { value =>
        if value.objOpt.isDefined then
          Right(value)
        else
          Left(ParseError("adapter", "Parsed JSON output is not an object"))
      }

  private def coerce(typeRef: TypeRef, value: Value): Either[DspyError, DynamicValue] =
    typeRef match
      case TypeRef.int => value
          .numOpt
          .filter(number => number.isWhole && number >= Int.MinValue.toDouble && number <= Int.MaxValue.toDouble)
          .map(number => DynamicValue.Primitive(PrimitiveValue.Int(number.toInt)))
          .toRight(ValidationError(s"Expected integer value, found: $value"))
      case TypeRef.double => value
          .numOpt
          .toRight(ValidationError(s"Expected numeric value, found: $value"))
          .map(number => DynamicValue.Primitive(PrimitiveValue.Double(number)))
      case TypeRef.bool => value
          .boolOpt
          .toRight(ValidationError(s"Expected boolean value, found: $value"))
          .map(boolean => DynamicValue.Primitive(PrimitiveValue.Boolean(boolean)))
      case TypeRef.json | TypeRef.list => Right(JsonDynamic.fromUjson(value))
      case _                           => Right(DynamicValue.Primitive(PrimitiveValue.String(value.strOpt.getOrElse(renderJson(value)))))

  private def renderJson(value: Value): String =
    value match
      case ujson.Str(text) => text
      case other           => other.render()
