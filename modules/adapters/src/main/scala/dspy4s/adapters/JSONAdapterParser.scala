package dspy4s.adapters

import dspy4s.adapters.contracts.{NativeFunctionCalling, ParsedOutput}
import dspy4s.adapters.internal.{AdapterTextSupport, JsonTextScan}
import dspy4s.core.contracts.{
  DspyError,
  DynamicValues,
  FieldSpec,
  ParseError,
  PythonText,
  SignatureLayout,
  TypeRef,
  ValidationError
}
import dspy4s.lm.contracts.LmOutput
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

import scala.util.matching.Regex

/** Decodes JSONAdapter's JSON body and provider-native tool calls into schema-backed `DynamicValue` fields. */
private[adapters] object JSONAdapterParser:
  private val FencedJson: Regex = "(?s)```json\\s*(\\{.*?\\})\\s*```".r

  /** @param nativeCalls
    *   `true` when native function calling is active for the call. The provider tool calls then fill the tool calls
    *   field, and an empty sequence when there are none. Else the field is an ordinary JSON key.
    */
  def parse(
      allowTextFallbackForSingleOutput: Boolean,
      layout                          : SignatureLayout,
      output                          : LmOutput,
      nativeCalls                     : Boolean
  ): Either[DspyError, ParsedOutput] =
    if layout.outputFields.exists(NativeFunctionCalling.isToolCallsField) && output.toolCalls.nonEmpty then
      Right(parseNativeToolTurn(layout, output))
    else
      extractJson(output.text).flatMap(parseJsonObject) match
        case Right(root) => parseFields(layout, root, output, nativeCalls)
        case Left(_)     =>
          if allowTextFallbackForSingleOutput && layout.outputFields.size == 1 then
            AdapterTextSupport.singleOutputTextFallback(layout, output)
          else
            Left(ParseError("adapter", "JSON parse failed and no fallback was applied", raw = Some(output.text)))

  /** A tool turn has no final answer yet: a field that is missing or does not fit its type takes its default, else a
    * null.
    */
  private def parseNativeToolTurn(layout: SignatureLayout, output: LmOutput): ParsedOutput =
    val jsonRoot = extractJson(output.text).flatMap(parseJsonObject).toOption
    val entries  = layout
      .outputFields
      .map { field =>
        if NativeFunctionCalling.isToolCallsField(field) then
          field.name -> NativeFunctionCalling.encodeToolCalls(output.toolCalls)
        else
          jsonRoot
            .flatMap(DynamicValues.recordGet(_, field.name))
            .flatMap(raw => decode(field, raw).toOption)
            .map(coerced => field.name -> coerced)
            .getOrElse(field.name -> field.defaultValue.getOrElse(DynamicValue.Null))
      }
    ParsedOutput(DynamicValues.recordFromEntries(entries))

  private def parseFields(
      layout     : SignatureLayout,
      root       : DynamicValue.Record,
      output     : LmOutput,
      nativeCalls: Boolean
  ): Either[DspyError, ParsedOutput] =
    AdapterTextSupport
      .decodeOutputFields(layout, output.text) { field =>
        if nativeCalls && NativeFunctionCalling.isToolCallsField(field) then
          Right(Some(NativeFunctionCalling.encodeToolCalls(output.toolCalls)))
        else
          DynamicValues.recordGet(root, field.name) match
            case Some(raw) => decode(field, raw).map(Some(_))
            case None      => Right(None)
      }
      .map(entries => ParsedOutput(DynamicValues.recordFromEntries(entries)))

  /** The value of the output field `field` (upstream `parse_value`). A JSON null of an optional field is a null. A tool
    * calls field takes a JSON list of calls, or the JSON text of such a list. A JSON string for a field whose type is
    * not a string goes through the text coercion of the chat format (`AdapterTextSupport.outputTextValue`), as upstream
    * `parse_value` reads a string with `json_repair`: `"3"` for an `int` field is `3`, and `"[1, 2]"` for a `list`
    * field is a list. A JSON value for a `list[T]` field of a string signature must be a list, as in the chat format.
    * An optional field also takes the lenient forms of `AdapterTextSupport.optionalFallback`.
    *
    * A field with allowed values takes the rule of `AdapterTextSupport.allowedJsonValue` (decision D7): a JSON string
    * only. Each other JSON value is an error: upstream `parse_value` validates a value that is not a string against the
    * `Literal`, whose members are strings.
    */
  private def decode(field: FieldSpec, value: DynamicValue): Either[DspyError, DynamicValue] =
    if AdapterTextSupport.hasAllowedValues(field) then AdapterTextSupport.allowedJsonValue(field, value)
    else
      value match
        case _: DynamicValue.Null.type if field.optional            => Right(DynamicValue.Null)
        case DynamicValue.Primitive(PrimitiveValue.String(text))    => AdapterTextSupport.outputTextValue(field, text)
        case other if NativeFunctionCalling.isToolCallsField(field) => AdapterTextSupport.toolCallsValue(other)
        case other                                                  => coerce(field, other)

  private def extractJson(text: String): Either[DspyError, String] =
    val trimmed = text.trim
    if trimmed.startsWith("{") && trimmed.endsWith("}") then
      Right(trimmed)
    else
      FencedJson.findFirstMatchIn(text).map(_.group(1)) match
        case Some(json) => Right(json)
        case None       =>
          extractFirstJsonObject(text).toRight(ParseError("adapter", "Could not find JSON object in model output"))

  /** The text of the first JSON object of `text`: from the first `{` to the `}` that closes it. A brace in a JSON
    * string does not count ([[JsonTextScan]]).
    */
  private def extractFirstJsonObject(text: String): Option[String] =
    val start = text.indexOf('{')
    if start < 0 then
      None
    else
      val scan  = new JsonTextScan
      var depth = 0
      var end   = -1
      var i     = start
      while i < text.length && end < 0 do
        val char = text.charAt(i)
        if scan.structural(char) then
          if char == '{' then depth += 1
          else if char == '}' then
            depth -= 1
            if depth == 0 then end = i
        i += 1
      Option.when(end >= start)(text.substring(start, end + 1))

  /** The JSON object of `raw`, read with the rules of Python `json.loads` ([[PythonText.parseJson]]). */
  private def parseJsonObject(raw: String): Either[DspyError, DynamicValue.Record] =
    PythonText.parseJson(raw).flatMap {
      case record: DynamicValue.Record => Right(record)
      case _                           => Left(ParseError("adapter", "Parsed JSON output is not an object"))
    }

  /** The value of the output field `field` from a JSON value `value` that is not a string or a null. An `int` field
    * takes the rule of `AdapterTextSupport.integerValue`: a whole number, also a whole float (upstream pydantic lax
    * mode), keeps its exact value as an `Int`, a `Long`, or a `BigInt`. A `json` field, a `list` field, and an evidence
    * field of the decision adapter ([[DecisionAdapter.isEvidenceType]]) keep the value as it is. A field of another
    * type takes the JSON text of the value as its text. An optional field also takes the lenient forms of
    * `AdapterTextSupport.optionalFallback`. The parser encodes the JSON text one time, only when a rule reads it.
    */
  private def coerce(field: FieldSpec, value: DynamicValue): Either[DspyError, DynamicValue] =
    lazy val text = jsonText(value)
    AdapterTextSupport.optionalFallback(field, text, coerceTo(field.typeRef, value, text))

  private def coerceTo(typeRef: TypeRef, value: DynamicValue, text: => String): Either[DspyError, DynamicValue] =
    typeRef match
      case TypeRef.int => AdapterTextSupport
          .integerValue(value)
          .toRight(ValidationError(s"Expected integer value, found: $text"))
      case TypeRef.double => DynamicValues
          .asDouble(value)
          .toRight(ValidationError(s"Expected numeric value, found: $text"))
          .map(number => DynamicValue.Primitive(PrimitiveValue.Double(number)))
      case TypeRef.bool => Some(value)
          .collect { case boolean @ DynamicValue.Primitive(PrimitiveValue.Boolean(_)) => boolean }
          .toRight(ValidationError(s"Expected boolean value, found: $text"))
      case TypeRef.json | TypeRef.list                          => Right(value)
      case evidence if DecisionAdapter.isEvidenceType(evidence) => Right(value)
      // A string signature type such as `list[str]`: the rule of the chat format (`AdapterTextSupport.listOfValue`).
      case other if AdapterTextSupport.isListOf(other) => AdapterTextSupport.listOfValue(other, Some(value), text)
      case _                                           => Right(DynamicValues.string(text))

  /** The text of a JSON value: a string is its text, and each other value is its JSON text ([[PythonText.json]]). */
  private def jsonText(value: DynamicValue): String =
    DynamicValues.asString(value).getOrElse(PythonText.json(value))
