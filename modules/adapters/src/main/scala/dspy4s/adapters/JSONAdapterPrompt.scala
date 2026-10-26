package dspy4s.adapters

import dspy4s.adapters.contracts.{AdapterInvocation, FormattedPrompt, NativeFunctionCalling, NativeTools}
import dspy4s.adapters.internal.{ConversationHistory, PromptText, StrictJsonSchema, TurnText}
import dspy4s.adapters.internal.PromptText.OutputSchema
import dspy4s.core.contracts.{
  :=, DspyError, DynamicValues, FieldSpec, PythonText, RuntimeContext, SignatureLayout, updated
}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import zio.blocks.schema.DynamicValue

/** Builds JSONAdapter's system message, demonstrations, conversation history, and provider response-schema options
  * (upstream `JSONAdapter` in `dspy/adapters/json_adapter.py`). The system message and the user turns have the form of
  * the chat adapter, with a JSON object for the outputs.
  */
private[adapters] object JSONAdapterPrompt:
  def format(invocation: AdapterInvocation, native: NativeTools)(using
      RuntimeContext
  ): Either[DspyError, FormattedPrompt] =
    Right(ConversationHistory.prompt(invocation, native, Turns, responseFormatOptions(invocation, native.isActive)))

  /** The text of a turn: the marker form of the chat adapter for a user turn, with the JSON reminder in a current
    * request, and one JSON object for an assistant turn (upstream `JSONAdapter.format_assistant_message_content`).
    */
  private object Turns extends TurnText:
    override def user(layout: SignatureLayout, values: DynamicValue.Record, mainRequest: Boolean): String =
      ChatAdapterPrompt.userText(layout, values, Option.when(mainRequest)(outputRequirements(layout)))

    override def assistant(layout: SignatureLayout, values: DynamicValue.Record): String =
      outputObject(layout.outputFields.map { field =>
        field -> PromptText.jsonable(field, DynamicValues.recordGet(values, field.name).getOrElse(DynamicValue.Null))
      })

    /** The structure of the system message (upstream `JSONAdapter.format_field_structure`). */
    override def structure(layout: SignatureLayout, schema: OutputSchema): String =
      val outputs = layout.outputFields.map { field =>
        field -> DynamicValues.string(PromptText.placeholder(field, output = true, schema))
      }
      PythonText.strip(
        Vector(
          PromptText.StructureHeader,
          "Inputs will have the following structure:",
          ChatAdapterPrompt.inputStructure(layout, schema),
          "Outputs will be a JSON object with the following fields.",
          outputObject(outputs)
        ).mkString("\n\n")
      )

  /** The output fields as one JSON object, in the form of `json.dumps(..., indent=2)`. */
  private def outputObject(fields: Vector[(FieldSpec, DynamicValue)]): String =
    PythonText.json(DynamicValues.recordFromEntries(fields.map((field, value) => field.name -> value)), Some(2))

  /** The output reminder of the user turn (upstream `JSONAdapter.user_message_output_requirements`). */
  private def outputRequirements(layout: SignatureLayout): String =
    "Respond with a JSON object in the following order of fields: " +
      layout.outputFields.map(field => s"`${field.name}`${PromptText.reminderHint(field)}").mkString(", then ") + "."

  /** The `response_format` of the request (upstream `JSONAdapter._prepare_response_format`). Empty when the model of
    * the context does not support a response schema.
    *
    * The schema is the typed output schema of the invocation, else the schema that
    * [[dspy4s.adapters.internal.StrictJsonSchema.fromFields]] builds from the output fields. With native function
    * calling active (`nativeActive`), the schema has no tool calls field. The constraints of the fields are added, then
    * [[dspy4s.adapters.internal.StrictJsonSchema.shape]] puts the schema in strict shape. The result is
    * `{"type": "json_schema", "json_schema": {"name": "DSPyProgramOutputs", "schema": ..., "strict": true}}`.
    *
    * JSON mode, `{"type": "json_object"}`, replaces the schema when an output is a tool calls field and native function
    * calling is not active, or when no strict schema results: a field type has no schema, or the schema has an open
    * map.
    *
    * When the schema build fails and the adapter goes back to JSON mode, the adapter logs the warning of upstream
    * ([[SchemaBuildWarning]]). A tool calls field and an open map give JSON mode with no warning, as upstream. The open
    * map of a field is an open map below the root of the schema
    * ([[dspy4s.adapters.internal.StrictJsonSchema.hasFieldOpenMap]]), for example the schema of a field of the type
    * `json` (upstream `dict`). An open root is a failed build: `fromFields` writes it when a field has no schema, and
    * upstream then logs the warning.
    */
  private def responseFormatOptions(invocation: AdapterInvocation, nativeActive: Boolean)(using
      ctx: RuntimeContext
  ): DynamicValue.Record =
    val capable = ctx.lm match
      case Some(lm: LanguageModel) => lm.supportsResponseSchema
      case _                       => false
    if !capable then DynamicValue.Record.empty
    else DynamicValues.record("response_format" -> responseFormat(invocation, nativeActive))

  /** The strict `json_schema` format of `invocation`, else JSON mode. See [[responseFormatOptions]]. */
  private def responseFormat(invocation: AdapterInvocation, nativeActive: Boolean): DynamicValue.Record =
    val outputFields = invocation.layout.outputFields
    if !nativeActive && outputFields.exists(NativeFunctionCalling.isToolCallsField) then
      JsonMode
    else
      // In this branch, a tool calls field is an output only when native function calling is active. The schema does
      // not show that field.
      val (toolCalls, fields) = outputFields.partition(NativeFunctionCalling.isToolCallsField)
      val schema              = invocation.outputJsonSchema.fold(StrictJsonSchema.fromFields(fields))(
        withoutProperties(_, toolCalls.map(_.name).toSet)
      )
      if StrictJsonSchema.hasFieldOpenMap(schema) then JsonMode
      else
        StrictJsonSchema.shape(embedConstraints(schema, fields), fields.filter(_.optional).map(_.name).toSet) match
          case Right(strict) => DynamicValues.record(
              "type"        := "json_schema",
              "json_schema" ->
                DynamicValues.record("name" := StrictJsonSchema.Name, "schema" -> strict, "strict" := true)
            )
          case Left(_) =>
            RuntimeEnvironment.warn("JSONAdapter", SchemaBuildWarning)
            JsonMode

  /** The JSON mode format: the response is a JSON object, with no schema. */
  private val JsonMode: DynamicValue.Record = DynamicValues.record("type" := "json_object")

  /** The warning when the schema build fails (upstream `JSONAdapter._prepare_response_format`). */
  private val SchemaBuildWarning: String = "Failed to build structured output schema; using JSON mode before execution."

  /** `schema` with no property in `names`. [[StrictJsonSchema.shape]] writes `required` again from the properties. */
  private def withoutProperties(schema: DynamicValue.Record, names: Set[String]): DynamicValue.Record =
    DynamicValues.recordGet(schema, "properties") match
      case Some(properties: DynamicValue.Record) if names.nonEmpty =>
        schema.updated("properties", DynamicValues.recordFilterKeys(properties, name => !names.contains(name)))
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
          schema.updated("properties", DynamicValue.Record(updatedProperties))
        case _ => schema
