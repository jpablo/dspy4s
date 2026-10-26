package dspy4s.adapters.internal

import dspy4s.adapters.contracts.AdapterErrors
import dspy4s.adapters.contracts.NativeFunctionCalling
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.LenientValues
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.PythonText
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ToolCall
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.ValidationError
import dspy4s.core.contracts.updated
import dspy4s.lm.contracts.LmOutput
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** Prompt-text helpers shared across the adapters. One implementation guarantees the adapters render demo / input
  * fields, coerce string field values, and apply the single-output text fallback identically — fixing a defect in one
  * adapter can no longer silently miss the others.
  */
private[adapters] object AdapterTextSupport:

  /** `values` with the [[FieldSpec.defaultValue]] of each field of `inputFields` that `values` does not have. Only the
    * inputs of a live call take the defaults. A demo and a turn of a history do not.
    */
  def withInputDefaults(inputFields: Vector[FieldSpec], values: DynamicValue.Record): DynamicValue.Record =
    inputFields.foldLeft(values) { (filled, field) =>
      (DynamicValues.recordGet(filled, field.name), field.defaultValue) match
        case (None, Some(default)) => filled.updated(field.name, default)
        case _                     => filled
    }

  /** Decode every output field in layout order, attaching the field name exactly once at this shared boundary.
    *
    * `decode` gives `None` when the response does not have the field. The field then takes the value of
    * [[missingOutput]]. An error of `decode` becomes a [[ParseError]] that names the field and has the raw response
    * `raw`.
    */
  def decodeOutputFields(
      layout: SignatureLayout,
      raw   : String
  )(
      decode: FieldSpec => Either[DspyError, Option[DynamicValue]]
  ): Either[DspyError, Vector[(String, DynamicValue)]] =
    layout.outputFields.foldLeft[Either[DspyError, Vector[(String, DynamicValue)]]](Right(Vector.empty)) {
      (acc, field) =>
        for
          soFar <- acc
          found <- decode(field).left.map(fieldError(field, raw, _))
          value <- found.fold(missingOutput(field, raw))(Right(_))
        yield soFar :+ (field.name -> value)
    }

  /** The value of an output field that the response does not have (upstream `apply_output_field_defaults`): the
    * [[FieldSpec.defaultValue]] of the field, else a null when the field is optional. Else it is a parse error that has
    * the raw response `raw`.
    */
  def missingOutput(field: FieldSpec, raw: String): Either[DspyError, DynamicValue] =
    field.defaultValue match
      case Some(default)          => Right(default)
      case None if field.optional => Right(DynamicValue.Null)
      case None                   => Left(AdapterErrors.missingField(field.name, Some(raw)))

  /** The parse error of a value of the output field `field` that does not fit the type of the field. */
  private def fieldError(field: FieldSpec, raw: String, error: DspyError): DspyError =
    ParseError("adapter", s"Failed to parse field ${field.name}: ${error.message}", raw = Some(raw))

  /** Build the common adapter parse result while preserving the model's exact raw text. */
  def parsedOutput(
      adapterName: String,
      output     : LmOutput,
      entries    : Vector[(String, DynamicValue)],
      metadata   : Map[String, Any] = Map.empty
  ): ParsedOutput =
    ParsedOutput(
      values = DynamicValue.Record(Chunk.from(entries)),
      rawText = Some(output.text),
      metadata = Map("adapter" -> adapterName) ++ metadata
    )

  /** The value of the output field `field` from its raw text (upstream `parse_value` with a string). A `tool_calls`
    * field takes the JSON list of calls in the text ([[textToolCalls]]). Each other field goes to [[coerceOutputText]].
    * The Chat, JSON, and XML adapters use this function, so a text value has the same rules in each format.
    */
  def outputTextValue(field: FieldSpec, raw: String): Either[DspyError, DynamicValue] =
    if isNullText(field, raw) then Right(DynamicValue.Null)
    else if NativeFunctionCalling.isToolCallsField(field) then textToolCalls(raw)
    else coerceOutputText(field, raw)

  /** Coerce the raw text of the output field `field` (upstream `parse_value`). The text `null` or `None` of an optional
    * field is a null, when the type of the field is not a string. A field with allowed values takes the text of
    * [[allowedValue]]. Each other text goes to [[coerceText]].
    *
    * An optional field also takes the lenient forms of [[optionalFallback]].
    */
  def coerceOutputText(field: FieldSpec, raw: String): Either[DspyError, DynamicValue] =
    if isNullText(field, raw) then Right(DynamicValue.Null)
    else
      val value = if hasAllowedValues(field) then allowedValue(field, raw) else coerceText(field.typeRef, raw)
      optionalFallback(field, raw, value)

  /** `true` when the output field `field` takes only its allowed values: a string field with [[FieldSpec.enumValues]],
    * as a `Literal[...]` of a string signature or a Scala enum of a typed signature gives it (decision D7). The enum
    * values of a decision type (`TypeRef.noul`, `score`, and `choice`) are its levels or its options, so these fields
    * do not take this rule.
    */
  def hasAllowedValues(field: FieldSpec): Boolean =
    field.typeRef == TypeRef.string && field.enumValues.nonEmpty

  /** The allowed value of the output field `field` that the text `raw` gives (upstream `parse_value` with a `Literal`
    * annotation, decision D7). The comparison is case-sensitive. When `raw` is not an allowed value, these steps of
    * upstream come before one more comparison:
    *   1. Remove the whitespace at the two ends (Python `str.strip`).
    *   2. Remove a `Literal[...]` or a `str[...]` wrapper.
    *   3. Remove one pair of quotes (`'` or `"`).
    *
    * Then upstream reads the text with `json_repair.loads`, and else with `ast.literal_eval`, so the last comparison
    * takes the text of [[literalText]]. A text that gives no allowed value is an error, thus a parse error of the
    * parser, and the chat adapter then tries its JSON fallback.
    *
    * A Scala enum output has the same rule. Upstream `find_enum_member` compares the text of an `Enum` with no step.
    */
  def allowedValue(field: FieldSpec, raw: String): Either[DspyError, DynamicValue] =
    val allowed   = field.enumValues
    val stripped  = PythonText.strip(raw)
    val unwrapped = withoutQuotes(
      if AllowedValueWrappers.exists(stripped.startsWith) && stripped.endsWith("]") then
        stripped.substring(stripped.indexOf('[') + 1, stripped.length - 1)
      else stripped
    )
    (Iterator(raw, unwrapped) ++ literalText(unwrapped).iterator)
      .find(allowed.contains)
      .map(text => DynamicValue.Primitive(PrimitiveValue.String(text)))
      .toRight(notAllowedValue(field, raw))

  /** The string that upstream `parse_value` reads from `text` in its last step, after the JSON whitespace at the two
    * ends (space, tab, and line breaks) goes. `json_repair.loads` comes first: a JSON string in double quotes gives its
    * text with the JSON escapes decoded ([[PythonText.jsonString]]). Else `ast.literal_eval` reads a Python string
    * literal, also with a `u` or an `r` prefix, with the Python escapes decoded ([[PythonText.stringLiteral]]). `None`
    * when `text` is neither.
    */
  private def literalText(text: String): Option[String] =
    val trimmed = text.dropWhile(JsonWhitespace.contains).reverse.dropWhile(JsonWhitespace.contains).reverse
    PythonText.jsonString(trimmed).orElse(PythonText.stringLiteral(trimmed))

  /** The whitespace that `json_repair` skips around a value. */
  private val JsonWhitespace: Set[Char] = Set(' ', '\t', '\n', '\r')

  /** The allowed value of the output field `field` that the JSON value `value` gives (upstream `parse_value` with a
    * `Literal` annotation, decision D7). The JSON adapter uses this rule for a value of its JSON object, and `RLM` uses
    * it for a value of `SUBMIT`. Use it only for a field for which [[hasAllowedValues]] is `true`:
    *   - A null of an optional field is a null.
    *   - A string takes the steps of [[allowedValue]], and the result is the allowed value itself.
    *   - Each other value is an error, because each allowed value is a string. The error shows the JSON text of the
    *     value ([[PythonText.json]]).
    */
  def allowedJsonValue(field: FieldSpec, value: DynamicValue): Either[DspyError, DynamicValue] =
    value match
      case _: DynamicValue.Null.type if field.optional         => Right(DynamicValue.Null)
      case DynamicValue.Primitive(PrimitiveValue.String(text)) => outputTextValue(field, text)
      case other                                               => Left(notAllowedValue(field, PythonText.json(other)))

  /** The error of the text or the JSON text `text` that is not an allowed value of the output field `field`. */
  private def notAllowedValue(field: FieldSpec, text: String): DspyError =
    ValidationError(s"Value '$text' is not one of the allowed values: ${field.enumValues.mkString("; ")}")

  /** The prefixes of a wrapper that upstream `parse_value` removes from a `Literal` value. */
  private val AllowedValueWrappers: Vector[String] = Vector("Literal[", "str[")

  /** `true` when `text` has two or more characters, and the same quote (`'` or `"`) at the two ends. */
  private def isQuoted(text: String): Boolean =
    text.length > 1 && text.head == text.last && (text.head == '\'' || text.head == '"')

  /** `text` with no quotes at the two ends when [[isQuoted]] is `true`, else `text`. */
  private def withoutQuotes(text: String): String =
    if isQuoted(text) then text.substring(1, text.length - 1) else text

  /** `result`, else, for an optional field, the value of the lenient forms of the typed decode
    * ([[dspy4s.core.contracts.LenientValues]]): the texts `""`, `null`, `none`, and `n/a` in any case are a null, and a
    * number can have a currency symbol, a `+`, and thousands commas (`"$2,399.00"`). An integer can be out of the `Int`
    * range.
    *
    * An `Option` field of a typed signature has the type of its element. Before, it was a text field, and the typed
    * decode read these forms. This rule keeps them for an optional field. A text that does not fit the type, also in a
    * lenient form, keeps the error of `result`.
    *
    * @param raw
    *   the text of the value: the text of the response, or the JSON text of a JSON value
    */
  def optionalFallback(
      field : FieldSpec,
      raw   : String,
      result: Either[DspyError, DynamicValue]
  ): Either[DspyError, DynamicValue] =
    result match
      case Left(error) if field.optional => lenientOptional(field.typeRef, raw.trim).toRight(error)
      case other                         => other

  private def lenientOptional(typeRef: TypeRef, text: String): Option[DynamicValue] =
    if LenientValues.isNullText(text) then Some(DynamicValue.Null)
    else
      val value = DynamicValue.Primitive(PrimitiveValue.String(text))
      typeRef match
        case TypeRef.int => LenientValues.toLong(value).map { whole =>
            if whole.isValidInt then DynamicValue.Primitive(PrimitiveValue.Int(whole.toInt))
            else DynamicValue.Primitive(PrimitiveValue.Long(whole))
          }
        case TypeRef.double =>
          LenientValues.toDouble(value).map(number => DynamicValue.Primitive(PrimitiveValue.Double(number)))
        case _ => None

  /** `true` when `raw` is a null text of the optional field `field` whose type is not a string. */
  private def isNullText(field: FieldSpec, raw: String): Boolean =
    field.optional && field.typeRef != TypeRef.string && NullTexts.contains(raw.trim)

  /** The texts that are a null for an optional field: JSON `null` and Python `None`. */
  private val NullTexts: Set[String] = Set("null", "None")

  /** The value of a `tool_calls` output field that the model wrote as text (native function calling is not active). The
    * text is a JSON value ([[PythonText.parseJson]]) in a form of [[toolCallsValue]]. Each other text is an error.
    */
  def textToolCalls(raw: String): Either[DspyError, DynamicValue] =
    PythonText.parseJson(raw).flatMap(toolCallsValue)

  /** The value of a `tool_calls` output field from a JSON value, in a form that upstream `ToolCalls.validate_input`
    * accepts ([[ToolCall.fromDynamic]]): a list of `{"name", "args"}` objects, an object with that list under the key
    * `tool_calls`, or one call object with `args` or `arguments`. The value has the form of
    * [[NativeFunctionCalling.encodeToolCalls]].
    */
  def toolCallsValue(value: DynamicValue): Either[DspyError, DynamicValue] =
    ToolCall
      .fromDynamic(value)
      .map(NativeFunctionCalling.encodeToolCalls)
      .toRight(ValidationError(
        s"Expected a JSON list of tool calls, each with a name and args, found: ${DynamicValues.renderText(value)}"
      ))

  /** Coerce a raw string field value into the `DynamicValue` its declared `TypeRef` demands. Shared by the Chat and XML
    * adapters so a `json` / `list` field decodes identically regardless of which adapter parsed it. A `json` or a
    * `list` field reads the text as JSON with the rules of Python `json.loads` ([[PythonText.parseJson]]): an integer
    * keeps its exact value, and a float stays a float.
    */
  def coerceText(typeRef: TypeRef, raw: String): Either[DspyError, DynamicValue] =
    typeRef match
      // Upstream `parse_value` reads the text as JSON, then pydantic lax mode accepts a whole float for an int.
      case TypeRef.int => wholeNumber(raw.trim).toRight(ValidationError(s"Cannot parse integer output from '$raw'"))
          .map(i => DynamicValue.Primitive(PrimitiveValue.Int(i)))
      case TypeRef.double => raw.trim.toDoubleOption.toRight(ValidationError(s"Cannot parse double output from '$raw'"))
          .map(d => DynamicValue.Primitive(PrimitiveValue.Double(d)))
      case TypeRef.bool => raw.trim.toLowerCase match
          case "true"  => Right(DynamicValue.Primitive(PrimitiveValue.Boolean(true)))
          case "false" => Right(DynamicValue.Primitive(PrimitiveValue.Boolean(false)))
          case other   => Left(ValidationError(s"Cannot parse boolean output from '$other'"))
      case TypeRef.json | TypeRef.list => PythonText.parseJson(raw).left.map(_ =>
          ValidationError(s"Field could not be parsed as JSON from '$raw'")
        )
      case other if isListOf(other) => listOfValue(other, PythonText.parseJson(raw).toOption, raw)
      case _                        => Right(DynamicValue.Primitive(PrimitiveValue.String(raw)))

  /** `true` for a list type with an item type, for example the type `list[str]` of a string signature. A `tuple[...]`,
    * a `set[...]`, and a `frozenset[...]` are list types too: upstream `parse_value` gives a tuple or a set, and the
    * port has no tuple or set value.
    */
  def isListOf(typeRef: TypeRef): Boolean = ListTypePrefixes.exists(typeRef.repr.startsWith)

  /** The type tokens of [[isListOf]], in lower case, as [[TypeRef.fromToken]] gives them. */
  private val ListTypePrefixes: Vector[String] = Vector("list[", "tuple[", "set[", "frozenset[")

  /** The type tokens of a set type of [[isListOf]]. */
  private val SetTypePrefixes: Vector[String] = Vector("set[", "frozenset[")

  /** The value of a field of the list type `typeRef` ([[isListOf]]) from its JSON value `value`: the value when it is a
    * list, else an error (upstream `parse_value` validates a list). The items stay as they are. The Chat, JSON, and XML
    * adapters use this function, so a list value has the same rule in each format.
    *
    * A set type keeps the first copy of each equal value, in the order of the list: `["b", "a", "b"]` gives
    * `["b", "a"]`. A Python set has no order (the order of a set of texts changes with the hash seed of the process),
    * so only its rule for equal values applies: a set keeps the copy that it got first. Two values are equal when their
    * `DynamicValue`s are equal.
    *
    * @param raw
    *   the text of the value, for the error
    */
  def listOfValue(typeRef: TypeRef, value: Option[DynamicValue], raw: String): Either[DspyError, DynamicValue] =
    value
      .collect { case list: DynamicValue.Sequence =>
        if SetTypePrefixes.exists(typeRef.repr.startsWith) then DynamicValue.Sequence(list.elements.distinct) else list
      }
      .toRight(ValidationError(s"Expected a JSON list for ${typeRef.repr} from '$raw'"))

  /** The `Int` of an integer text, or of a float text with no fraction (`3.0`). */
  private def wholeNumber(text: String): Option[Int] =
    text.toIntOption.orElse(
      text.toDoubleOption.filter(d => d.isWhole && d >= Int.MinValue.toDouble && d <= Int.MaxValue.toDouble).map(
        _.toInt
      )
    )

  /** Single-output plain-text fallback shared by the JSON and XML adapters: when structured parsing failed at the
    * DOCUMENT level and the signature has exactly one output field, treat the whole (trimmed, non-empty) reply as that
    * field's value. Callers gate on their `allowTextFallbackForSingleOutput` flag and field count before invoking. A
    * field with allowed values takes the value of [[allowedValue]], so a text outside the allowed values is a parse
    * error, as in the other parse paths (decision D7).
    */
  def singleOutputTextFallback(
      adapterName: String,
      layout     : SignatureLayout,
      output     : LmOutput
  ): Either[DspyError, ParsedOutput] =
    val field   = layout.outputFields.head
    val trimmed = output.text.trim
    if trimmed.nonEmpty then
      val value =
        if hasAllowedValues(field) then allowedValue(field, trimmed).left.map(fieldError(field, output.text, _))
        else Right(DynamicValue.Primitive(PrimitiveValue.String(trimmed)))
      value.map(text =>
        parsedOutput(adapterName, output, Vector(field.name -> text), metadata = Map("fallback" -> "text"))
      )
    else Left(ParseError("adapter", "Cannot fallback from empty model output", raw = Some(output.text)))
