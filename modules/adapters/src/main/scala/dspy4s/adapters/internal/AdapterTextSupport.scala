package dspy4s.adapters.internal

import dspy4s.adapters.contracts.AdapterErrors
import dspy4s.adapters.contracts.NativeFunctionCalling
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.ParseError
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
    * field is a null, when the type of the field is not a string. Each other text goes to [[coerceText]].
    *
    * An optional field also takes the lenient forms of [[optionalFallback]].
    */
  def coerceOutputText(field: FieldSpec, raw: String): Either[DspyError, DynamicValue] =
    if isNullText(field, raw) then Right(DynamicValue.Null)
    else optionalFallback(field, raw, coerceText(field.typeRef, raw))

  /** `result`, else, for an optional field, the value of the lenient forms of the typed decode
    * (`ZioSchemaCodec.normalize`): the texts `""`, `null`, `none`, and `n/a` in any case are a null, and a number can
    * have a currency symbol, a `+`, and thousands commas (`"$2,399.00"`). An integer can be out of the `Int` range.
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
    if LenientNullTexts.exists(_.equalsIgnoreCase(text)) then Some(DynamicValue.Null)
    else
      val number = cleanNumeric(text)
      typeRef match
        case TypeRef.int => number.toLongOption.map { whole =>
            if whole.isValidInt then DynamicValue.Primitive(PrimitiveValue.Int(whole.toInt))
            else DynamicValue.Primitive(PrimitiveValue.Long(whole))
          }
        case TypeRef.double => number.toDoubleOption.map(value => DynamicValue.Primitive(PrimitiveValue.Double(value)))
        case _              => None

  /** The texts that are a null for the typed decode of an `Option` field (`ZioSchemaCodec.normalizeOption`). */
  private val LenientNullTexts: Vector[String] = Vector("", "null", "none", "n/a")

  /** A number text with no leading currency symbol (`$ € £ ¥`), no leading `+`, and no comma
    * (`ZioSchemaCodec.cleanNumeric`).
    */
  private def cleanNumeric(text: String): String =
    val noSymbol = if text.nonEmpty && "$€£¥".contains(text.head) then text.tail.trim else text
    val noSign   = if noSymbol.startsWith("+") then noSymbol.tail else noSymbol
    noSign.filter(_ != ',')

  /** `true` when `raw` is a null text of the optional field `field` whose type is not a string. */
  private def isNullText(field: FieldSpec, raw: String): Boolean =
    field.optional && field.typeRef != TypeRef.string && NullTexts.contains(raw.trim)

  /** The texts that are a null for an optional field: JSON `null` and Python `None`. */
  private val NullTexts: Set[String] = Set("null", "None")

  /** The value of a `tool_calls` output field that the model wrote as text (native function calling is not active). The
    * text is a JSON list of `{"name", "args"}` objects, or a JSON object with that list under the key `tool_calls`.
    * Each other text is an error.
    */
  def textToolCalls(raw: String): Either[DspyError, DynamicValue] =
    JsonDynamic.parse(raw).flatMap(toolCallsValue)

  /** The value of a `tool_calls` output field from a JSON value: a list of `{"name", "args"}` objects, or an object
    * with that list under the key `tool_calls`. The value has the form of [[NativeFunctionCalling.encodeToolCalls]].
    */
  def toolCallsValue(value: DynamicValue): Either[DspyError, DynamicValue] =
    val calls = value match
      case record: DynamicValue.Record => DynamicValues.recordGet(record, ToolCallsKey).flatMap(ToolCall.fromDynamic)
      case other                       => ToolCall.fromDynamic(other)
    calls
      .map(NativeFunctionCalling.encodeToolCalls)
      .toRight(ValidationError(
        s"Expected a JSON list of tool calls, each with a name and args, found: ${DynamicValues.renderText(value)}"
      ))

  /** The key of the list in the object form of a text `tool_calls` value (upstream `ToolCalls`). */
  private val ToolCallsKey: String = "tool_calls"

  /** Coerce a raw string field value into the `DynamicValue` its declared `TypeRef` demands. Shared by the Chat and XML
    * adapters so a `json` / `list` field decodes identically regardless of which adapter parsed it.
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
      case TypeRef.json | TypeRef.list => JsonDynamic.parse(raw).left.map(_ =>
          ValidationError(s"Field could not be parsed as JSON from '$raw'")
        )
      case other if other.repr.startsWith("list[") =>
        JsonDynamic.parse(raw) match
          case Right(list: DynamicValue.Sequence) => Right(list)
          case _                                  => Left(ValidationError(s"Expected a JSON list for ${other.repr} from '$raw'"))
      case _ => Right(DynamicValue.Primitive(PrimitiveValue.String(raw)))

  /** The `Int` of an integer text, or of a float text with no fraction (`3.0`). */
  private def wholeNumber(text: String): Option[Int] =
    text.toIntOption.orElse(
      text.toDoubleOption.filter(d => d.isWhole && d >= Int.MinValue.toDouble && d <= Int.MaxValue.toDouble).map(
        _.toInt
      )
    )

  /** Single-output plain-text fallback shared by the JSON and XML adapters: when structured parsing failed at the
    * DOCUMENT level and the signature has exactly one output field, treat the whole (trimmed, non-empty) reply as that
    * field's value. Callers gate on their `allowTextFallbackForSingleOutput` flag and field count before invoking.
    */
  def singleOutputTextFallback(
      adapterName: String,
      layout     : SignatureLayout,
      output     : LmOutput
  ): Either[DspyError, ParsedOutput] =
    val field   = layout.outputFields.head
    val trimmed = output.text.trim
    if trimmed.nonEmpty then
      Right(parsedOutput(
        adapterName,
        output,
        Vector(field.name -> DynamicValue.Primitive(PrimitiveValue.String(trimmed))),
        metadata = Map("fallback" -> "text")
      ))
    else Left(ParseError("adapter", "Cannot fallback from empty model output", raw = Some(output.text)))
