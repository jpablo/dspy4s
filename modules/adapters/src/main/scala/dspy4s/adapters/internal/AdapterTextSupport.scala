package dspy4s.adapters.internal

import dspy4s.adapters.contracts.AdapterErrors
import dspy4s.adapters.contracts.AdapterInvocation
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
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** Prompt-text helpers shared across the adapters. One implementation guarantees the adapters render demo / input
  * fields, coerce string field values, and apply the single-output text fallback identically — fixing a defect in one
  * adapter can no longer silently miss the others.
  */
private[adapters] object AdapterTextSupport:

  /** Standard system → demonstration pairs → live input message sequence. `TwoStepAdapter` uses it. The Chat, JSON, and
    * XML adapters use [[ConversationHistory.messages]], which also replays a conversation history. Both use
    * [[DemoMessages]] for the demos.
    */
  def fewShotMessages(
      invocation: AdapterInvocation,
      systemText: String
  )(
      renderDemoOutput: DynamicValue.Record => String
  ): Vector[Message] =
    val inputFields = invocation.layout.inputFields
    val demos       = DemoMessages.messages(invocation.layout, invocation.demos)(
      values => renderFields(inputFields, values),
      renderDemoOutput
    )
    (Vector(Message(role = MessageRole.System, text = Some(systemText))) ++ demos) :+
      Message(
        role = MessageRole.User,
        text = Some(renderFields(inputFields, withInputDefaults(inputFields, invocation.inputs.values)))
      )

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

  /** Render fields as `prefix value` lines (prefix falls back to `name:`). A field that `values` does not have is not
    * rendered. Used for demo / input user messages by the JSON, XML, and TwoStep adapters.
    */
  def renderFields(fields: Vector[FieldSpec], values: DynamicValue.Record): String =
    fields.flatMap { field =>
      val rendered = DynamicValues.recordGet(values, field.name)
        .map(DynamicValues.renderText)
      rendered.map { value =>
        val prefix = field.prefix.getOrElse(s"${field.name}:")
        s"$prefix $value"
      }
    }.mkString("\n")

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
    */
  def coerceOutputText(field: FieldSpec, raw: String): Either[DspyError, DynamicValue] =
    if isNullText(field, raw) then Right(DynamicValue.Null)
    else coerceText(field.typeRef, raw)

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
