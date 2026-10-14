package dspy4s.adapters

import dspy4s.adapters.contracts.{
  AdapterConstraints,
  AdapterInvocation,
  FormattedPrompt,
  NativeFunctionCalling,
  ToolChoice
}
import dspy4s.adapters.internal.{AdapterTextSupport, ConversationHistory, JsonDynamic, StrictJsonSchema, TurnText}
import dspy4s.core.contracts.{DspyError, DynamicValues, FieldSpec, RuntimeContext, SignatureLayout, updated}
import dspy4s.lm.contracts.LanguageModel
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** Builds JSONAdapter's JSON instructions, demonstrations, conversation history, and provider response-schema options.
  */
private[adapters] object JSONAdapterPrompt:
  def format(
      invocation              : AdapterInvocation,
      useNativeFunctionCalling: Boolean,
      parallelToolCalls       : Option[Boolean],
      toolChoice              : Option[ToolChoice]
  )(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    val layout           = invocation.layout
    val renderLayout     = NativeFunctionCalling.promptLayout(layout, useNativeFunctionCalling)
    val textOutputFields = renderLayout.outputFields
    val fieldList        = textOutputFields.map(_.name).mkString(", ")
    val jsonInstruction  = invocation.outputJsonSchema match
      case Some(schema) =>
        s"""Return a valid JSON object that conforms to the following JSON Schema. Do not include markdown fences.
           |
           |$schema""".stripMargin
      case None => s"Return a valid JSON object with exactly these keys: $fieldList. Do not include markdown fences." +
          toolCallsNote(textOutputFields)
    val baseSystemText = layout.instructions match
      case Some(instructions) => s"$instructions\n\n$jsonInstruction"
      case None               => jsonInstruction
    val systemText = AdapterConstraints.appendTo(
      ConversationHistory.appendNoteTo(baseSystemText, layout),
      layout.outputFields
    )

    val messages = ConversationHistory.messages(
      invocation,
      renderLayout = renderLayout,
      systemText,
      useNativeFunctionCalling,
      Turns
    )

    Right(
      FormattedPrompt(
        messages = messages,
        requestOptions = FormattedPrompt.mergeOptions(
          responseFormatOptions(invocation, useNativeFunctionCalling),
          NativeFunctionCalling.toolOptions(
            invocation.layout,
            invocation.tools,
            useNativeFunctionCalling,
            parallelToolCalls,
            toolChoice
          )
        )
      )
    )

  /** The text of a turn: `prefix value` lines for a user turn, and one JSON object for an assistant turn. A user turn
    * has no output reminder.
    */
  private object Turns extends TurnText:
    override def user(layout: SignatureLayout, values: DynamicValue.Record, mainRequest: Boolean): String =
      AdapterTextSupport.renderFields(layout.inputFields, values)

    override def assistant(layout: SignatureLayout, values: DynamicValue.Record): String =
      val assistantJson = ujson
        .Obj
        .from(
          layout.outputFields.flatMap(field =>
            DynamicValues.recordGet(values, field.name).map(value => field.name -> JsonDynamic.toUjson(value))
          )
        )
      ujson.write(assistantJson)

    override val assistantWritesJson: Boolean = true

  /** The form of each text tool calls field, for the keys instruction. Empty when the layout has no such field. */
  private def toolCallsNote(outputFields: Vector[FieldSpec]): String =
    outputFields
      .filter(NativeFunctionCalling.isToolCallsField)
      .map(field => s" The value of `${field.name}` must be ${NativeFunctionCalling.TextFormat}.")
      .mkString

  /** The `response_format` of the request (upstream `JSONAdapter._prepare_response_format`). Empty when the model of
    * the context does not support a response schema.
    *
    * The schema is the typed output schema of the invocation, else the schema that
    * [[dspy4s.adapters.internal.StrictJsonSchema.fromFields]] builds from the output fields. With native function
    * calling active ([[NativeFunctionCalling.isActive]]), the schema has no tool calls field. The constraints of the
    * fields are added, then [[dspy4s.adapters.internal.StrictJsonSchema.shape]] puts the schema in strict shape. The
    * result is `{"type": "json_schema", "json_schema": {"name": "DSPyProgramOutputs", "schema": ..., "strict": true}}`.
    *
    * JSON mode, `{"type": "json_object"}`, replaces the schema when an output is a tool calls field and native function
    * calling is not active, or when no strict schema results: the typed schema does not parse, a field type has no
    * schema, or the schema has an open map.
    */
  private def responseFormatOptions(invocation: AdapterInvocation, useNativeFunctionCalling: Boolean)(using
      ctx: RuntimeContext
  ): DynamicValue.Record =
    val capable = ctx.lm match
      case Some(lm: LanguageModel) => lm.supportsResponseSchema
      case _                       => false
    if !capable then
      DynamicValue.Record.empty
    else
      val format = responseFormat(invocation, NativeFunctionCalling.isActive(useNativeFunctionCalling))
      DynamicValue.Record(Chunk.single("response_format" -> format))

  /** The strict `json_schema` format of `invocation`, else JSON mode. See [[responseFormatOptions]]. */
  private def responseFormat(invocation: AdapterInvocation, nativeActive: Boolean): DynamicValue.Record =
    val outputFields = invocation.layout.outputFields
    if !nativeActive && outputFields.exists(NativeFunctionCalling.isToolCallsField) then
      JsonMode
    else
      // In this branch, a tool calls field is an output only when native function calling is active. The schema does
      // not show that field.
      val fields         = outputFields.filterNot(NativeFunctionCalling.isToolCallsField)
      val toolCallsNames = outputFields.filter(NativeFunctionCalling.isToolCallsField).map(_.name).toSet
      val schema         = invocation.outputJsonSchema match
        case Some(schemaString) => JsonDynamic.parse(schemaString).toOption.collect {
            case typed: DynamicValue.Record => withoutProperties(typed, toolCallsNames)
          }
        case None => StrictJsonSchema.fromFields(fields)
      val optionalFields = fields.filter(_.optional).map(_.name).toSet
      schema
        .map(embedConstraints(_, fields))
        .flatMap(StrictJsonSchema.shape(_, optionalFields).toOption) match
        case Some(strict) => DynamicValue.Record(
            Chunk(
              "type"        -> DynamicValue.Primitive(PrimitiveValue.String("json_schema")),
              "json_schema" -> DynamicValue.Record(
                Chunk(
                  "name"   -> DynamicValue.Primitive(PrimitiveValue.String(StrictJsonSchema.Name)),
                  "schema" -> strict,
                  "strict" -> DynamicValue.Primitive(PrimitiveValue.Boolean(true))
                )
              )
            )
          )
        case None => JsonMode

  /** The JSON mode format: the response is a JSON object, with no schema. */
  private val JsonMode: DynamicValue.Record =
    DynamicValue.Record(Chunk.single("type" -> DynamicValue.Primitive(PrimitiveValue.String("json_object"))))

  /** `schema` with no property in `names`. [[StrictJsonSchema.shape]] writes `required` again from the properties. */
  private def withoutProperties(schema: DynamicValue.Record, names: Set[String]): DynamicValue.Record =
    DynamicValues.recordGet(schema, "properties") match
      case Some(properties: DynamicValue.Record) if names.nonEmpty =>
        val kept = properties.fields.toVector.filterNot((name, _) => names.contains(name))
        schema.updated("properties", DynamicValue.Record(Chunk.from(kept)))
      case _ => schema

  /** Add each output field's constraints to the corresponding JSON Schema property. */
  private def embedConstraints(
      schema      : DynamicValue.Record,
      outputFields: Vector[FieldSpec]
  ): DynamicValue.Record =
    val constrained = outputFields.filter(_.constraints.nonEmpty)
    if constrained.isEmpty then
      schema
    else
      DynamicValues.recordGet(schema, "properties") match
        case Some(properties: DynamicValue.Record) =>
          val byName            = constrained.map(field => field.name -> field.constraints).toMap
          val updatedProperties = properties
            .fields
            .map {
              case (propertyName, property: DynamicValue.Record) if byName.contains(propertyName) =>
                propertyName ->
                  byName(propertyName).foldLeft(property)((current, constraint) =>
                    current.updated(constraint.schemaKeyword, constraint.schemaValue)
                  )
              case other => other
            }
          schema.updated("properties", DynamicValue.Record(Chunk.from(updatedProperties)))
        case _ => schema
