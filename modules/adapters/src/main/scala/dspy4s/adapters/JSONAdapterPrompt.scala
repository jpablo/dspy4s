package dspy4s.adapters

import dspy4s.adapters.contracts.{
  AdapterConstraints,
  AdapterInvocation,
  FormattedPrompt,
  NativeFunctionCalling,
  ToolChoice
}
import dspy4s.adapters.internal.{AdapterTextSupport, JsonDynamic}
import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeContext, updated}
import dspy4s.lm.contracts.LanguageModel
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** Builds JSONAdapter's JSON instructions, demonstrations, and provider response-schema options. */
private[adapters] object JSONAdapterPrompt:
  def format(
      invocation              : AdapterInvocation,
      useNativeFunctionCalling: Boolean,
      parallelToolCalls       : Option[Boolean],
      toolChoice              : Option[ToolChoice]
  )(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    val textOutputFields = invocation.layout.outputFields.filterNot(NativeFunctionCalling.isToolCallsField)
    val fieldList = textOutputFields.map(_.name).mkString(", ")
    val jsonInstruction =
      invocation.outputJsonSchema match
        case Some(schema) =>
          s"""Return a valid JSON object that conforms to the following JSON Schema. Do not include markdown fences.
           |
           |$schema""".stripMargin
        case None =>
          s"Return a valid JSON object with exactly these keys: $fieldList. Do not include markdown fences."
    val baseSystemText =
      invocation.layout.instructions match
        case Some(instructions) =>
          s"$instructions\n\n$jsonInstruction"
        case None =>
          jsonInstruction
    val systemText = AdapterConstraints.appendTo(baseSystemText, invocation.layout.outputFields)

    val messages =
      AdapterTextSupport.fewShotMessages(invocation, systemText) { demoValues =>
        val assistantJson = ujson
          .Obj
          .from(
            textOutputFields.flatMap(field =>
              DynamicValues.recordGet(demoValues, field.name).map(value => field.name -> JsonDynamic.toUjson(value))
            )
          )
        ujson.write(assistantJson)
      }

    Right(
      FormattedPrompt(
        messages = messages,
        requestOptions = FormattedPrompt.mergeOptions(
          responseFormatOptions(invocation),
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

  /** Emit an OpenAI-compatible response schema when the resolved model supports it. */
  private def responseFormatOptions(invocation: AdapterInvocation)(using ctx: RuntimeContext): DynamicValue.Record =
    val capable =
      ctx.lm match
        case Some(lm: LanguageModel) =>
          lm.supportsResponseSchema
        case _ =>
          false
    if !capable then
      DynamicValue.Record.empty
    else
      invocation.outputJsonSchema match
        case Some(schemaString) =>
          JsonDynamic.parse(schemaString) match
            case Right(schema: DynamicValue.Record) =>
              val jsonSchema = DynamicValue.Record(
                Chunk(
                  "name"   -> DynamicValue.Primitive(PrimitiveValue.String(sanitizeSchemaName(invocation.layout.name))),
                  "schema" -> embedConstraints(schema, invocation.layout.outputFields),
                  "strict" -> DynamicValue.Primitive(PrimitiveValue.Boolean(false))
                )
              )
              DynamicValue.Record(
                Chunk.single(
                  "response_format" ->
                    DynamicValue.Record(
                      Chunk(
                        "type"        -> DynamicValue.Primitive(PrimitiveValue.String("json_schema")),
                        "json_schema" -> jsonSchema
                      )
                    )
                )
              )
            case _ =>
              DynamicValue.Record.empty
        case None =>
          DynamicValue.Record.empty

  /** Add each output field's constraints to the corresponding JSON Schema property. */
  private def embedConstraints(
      schema      : DynamicValue.Record,
      outputFields: Vector[dspy4s.core.contracts.FieldSpec]
  ): DynamicValue.Record =
    val constrained = outputFields.filter(_.constraints.nonEmpty)
    if constrained.isEmpty then
      schema
    else
      DynamicValues.recordGet(schema, "properties") match
        case Some(properties: DynamicValue.Record) =>
          val byName = constrained.map(field => field.name -> field.constraints).toMap
          val updatedProperties = properties
            .fields
            .map {
              case (propertyName, property: DynamicValue.Record) if byName.contains(propertyName) =>
                propertyName ->
                  byName(propertyName).foldLeft(property)((current, constraint) =>
                    current.updated(constraint.schemaKeyword, constraint.schemaValue)
                  )
              case other =>
                other
            }
          schema.updated("properties", DynamicValue.Record(Chunk.from(updatedProperties)))
        case _ =>
          schema

  private def sanitizeSchemaName(name: String): String =
    val cleaned = name.replaceAll("[^a-zA-Z0-9_-]", "_")
    if cleaned.isEmpty then
      "response_schema"
    else
      cleaned
