package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterConstraints
import dspy4s.adapters.contracts.AdapterErrors
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.NativeFunctionCalling
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.adapters.contracts.ToolChoice
import dspy4s.adapters.internal.JsonDynamic
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.ValidationError
import dspy4s.core.contracts.updated
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import ujson.Value
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

import scala.util.Try

final case class JSONAdapter(
    name: String = "json",
    allowTextFallbackForSingleOutput: Boolean = true,
    /** See [[ChatAdapter.useNativeFunctionCalling]] — same adapter-level native function-calling gate, shared via
      * [[NativeFunctionCalling]]. Off by default. */
    useNativeFunctionCalling: Boolean = false,
    parallelToolCalls: Option[Boolean] = None,
    /** See [[ChatAdapter.toolChoice]]. */
    toolChoice: Option[ToolChoice] = None
) extends Adapter:
  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    // G-9: this adapter emits no prose field-description block (only a key list or a JSON Schema), so field
    // constraints are surfaced as a consolidated block appended to the system instruction (shared with XMLAdapter).
    // (Embedding them structurally into the emitted JSON Schema -- `exclusiveMinimum`/`maxLength` -- is a richer
    // follow-up that needs schema-AST manipulation; the prose block is the v1.)
    // A `tool_calls`-typed output is filled from structured tool_calls, never requested as a JSON key.
    val textOutputFields = invocation.layout.outputFields.filterNot(NativeFunctionCalling.isToolCallsField)
    val fieldList = textOutputFields.map(_.name).mkString(", ")
    // When the typed Predict path supplies a JSON Schema (rendered from the output `Schema[O]` via
    // `Shape.jsonSchemaString`), inline it so the LM has the precise output contract -- field names, types,
    // enum constraints, and required fields. Falls back to the natural-language instruction when no schema is
    // available (DynamicPredict path, or shapes without a backing Schema like `MapShape`).
    val jsonInstruction = invocation.outputJsonSchema match
      case Some(schema) =>
        s"""Return a valid JSON object that conforms to the following JSON Schema. Do not include markdown fences.
           |
           |$schema""".stripMargin
      case None =>
        s"Return a valid JSON object with exactly these keys: $fieldList. Do not include markdown fences."
    val baseSystemText = invocation.layout.instructions match
      case Some(instructions) => s"$instructions\n\n$jsonInstruction"
      case None               => jsonInstruction
    val systemText = AdapterConstraints.appendTo(baseSystemText, invocation.layout.outputFields)

    val demoMessages = invocation.demos.flatMap { demo =>
      val userText = renderFields(invocation.layout.inputFields, demo.values)
      val assistantJson = textOutputFields
        .flatMap(field =>
          DynamicValues.recordGet(demo.values, field.name)
            .map(dv => field.name -> DynamicValues.renderText(dv))
        )
        .toMap
      Vector(
        Message(role = MessageRole.User, text = Some(userText)),
        Message(role = MessageRole.Assistant, text = Some(ujson.write(assistantJson)))
      )
    }

    val inputMessage = Message(
      role = MessageRole.User,
      text = Some(renderFields(invocation.layout.inputFields, invocation.inputs.values))
    )

    Right(
      FormattedPrompt(
        messages = Vector(Message(role = MessageRole.System, text = Some(systemText))) ++ demoMessages ++ Vector(
          inputMessage
        ),
        // Merge native-tool options (disjoint keys: `tools`/`parallel_tool_calls`) with the structured-output
        // `response_format` option; both ride the same requestOptions seam.
        requestOptions = FormattedPrompt.mergeOptions(
          responseFormatOptions(invocation),
          NativeFunctionCalling.toolOptions(invocation.layout, invocation.tools, useNativeFunctionCalling, parallelToolCalls, toolChoice)
        )
      )
    )

  /** G-7 v1 (native structured outputs): when the ambient LM declares `supportsResponseSchema` and a JSON Schema
    * is available, emit OpenAI's `response_format: {type:"json_schema", json_schema:{name, schema, strict}}` into
    * `FormattedPrompt.requestOptions`, so the provider ENFORCES the output schema (not only the prose hint, which
    * stays in the system message as a belt-and-suspenders fallback). The engine merges this under the per-call /
    * module options.
    *
    * `strict: false` — dspy4s-rendered schemas may not satisfy OpenAI strict-mode requirements (e.g. every
    * property must be `required`, `additionalProperties: false`). If the schema string fails to parse this returns
    * an empty record (prose-only); `format` never fails over this.
    *
    * Native FUNCTION CALLING reuses this same `requestOptions` seam and is now implemented (G-7b): see
    * [[NativeFunctionCalling.toolOptions]], merged with this `response_format` in `format`. */
  private def responseFormatOptions(invocation: AdapterInvocation)(using ctx: RuntimeContext): DynamicValue.Record =
    val capable = ctx.lm match
      case Some(lm: LanguageModel) => lm.supportsResponseSchema
      case _                       => false
    if !capable then DynamicValue.Record.empty
    else
      invocation.outputJsonSchema match
        case Some(schemaString) =>
          JsonDynamic.parse(schemaString) match
            case Right(schema: DynamicValue.Record) =>
              val jsonSchema = DynamicValue.Record(Chunk(
                "name"   -> DynamicValue.Primitive(PrimitiveValue.String(sanitizeSchemaName(invocation.layout.name))),
                "schema" -> embedConstraints(schema, invocation.layout.outputFields),
                "strict" -> DynamicValue.Primitive(PrimitiveValue.Boolean(false))
              ))
              DynamicValue.Record(Chunk.single(
                "response_format" -> DynamicValue.Record(Chunk(
                  "type"        -> DynamicValue.Primitive(PrimitiveValue.String("json_schema")),
                  "json_schema" -> jsonSchema
                ))
              ))
            // A non-object schema (or parse failure) → prose-only fallback, never fail.
            case _ => DynamicValue.Record.empty
        case None => DynamicValue.Record.empty

  /** G-9: structurally embed each constrained output field's constraints into the emitted JSON Schema, adding the
    * JSON-Schema keyword (`exclusiveMinimum`, `maxLength`, ...) to the matching property record. No-op when no
    * output field is constrained or the schema has no `properties` object. (The schema is emitted `strict:false`,
    * so the provider treats these as advisory hints — they add structured signal, matching ChatAdapter's prose.) */
  private def embedConstraints(
      schema: DynamicValue.Record,
      outputFields: Vector[dspy4s.core.contracts.FieldSpec]
  ): DynamicValue.Record =
    val constrained = outputFields.filter(_.constraints.nonEmpty)
    if constrained.isEmpty then schema
    else
      DynamicValues.recordGet(schema, "properties") match
        case Some(props: DynamicValue.Record) =>
          val byName = constrained.map(f => f.name -> f.constraints).toMap
          val updatedProps = props.fields.map {
            case (propName, prop: DynamicValue.Record) if byName.contains(propName) =>
              propName -> byName(propName).foldLeft(prop)((acc, c) => acc.updated(c.schemaKeyword, c.schemaValue))
            case other => other
          }
          schema.updated("properties", DynamicValue.Record(Chunk.from(updatedProps)))
        case _ => schema

  /** OpenAI requires the `json_schema.name` to match `^[a-zA-Z0-9_-]+$`. Replace any other character with `_`;
    * fall back to a constant when the result would be empty. */
  private def sanitizeSchemaName(name: String): String =
    val cleaned = name.replaceAll("[^a-zA-Z0-9_-]", "_")
    if cleaned.isEmpty then "response_schema" else cleaned

  override def streamingState(layout: SignatureLayout): Option[AdapterStreamingState] =
    Some(new JsonStreamingState(layout.outputFields))

  override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
    if layout.outputFields.exists(NativeFunctionCalling.isToolCallsField) && output.toolCalls.nonEmpty then
      Right(parseNativeToolTurn(layout, output))
    else
      parseStructured(layout, output).orElse {
        if allowTextFallbackForSingleOutput && layout.outputFields.size == 1 then
          val field = layout.outputFields.head
          val trimmed = output.text.trim
          if trimmed.nonEmpty then
            Right(
              ParsedOutput(
                values = DynamicValue.Record(Chunk.single(
                  field.name -> DynamicValue.Primitive(PrimitiveValue.String(trimmed))
                )),
                rawText = Some(output.text),
                metadata = Map("adapter" -> name, "fallback" -> "text")
              )
            )
          else Left(ParseError("adapter", "Cannot fallback from empty model output"))
        else Left(ParseError("adapter", "JSON parse failed and no fallback was applied"))
      }

  /** Native tool turn: the model returned `tool_calls` (typically with an empty or non-JSON body). Fill the
    * `tool_calls` field from the structured calls; parse any JSON body that IS present for the remaining text
    * fields, and default the rest to Null (mirrors dspy's lenient native parse — `setdefault(field, None)`). */
  private def parseNativeToolTurn(layout: SignatureLayout, output: LmOutput): ParsedOutput =
    val jsonRoot: Option[Value] = extractJson(output.text).flatMap(parseJsonObject).toOption
    val entries = layout.outputFields.map { field =>
      if NativeFunctionCalling.isToolCallsField(field) then
        field.name -> NativeFunctionCalling.encodeToolCalls(output.toolCalls)
      else
        jsonRoot
          .flatMap(_.objOpt.flatMap(_.get(field.name)))
          .flatMap(raw => coerce(field.typeRef, raw).toOption)
          .map(coerced => field.name -> coerced)
          .getOrElse(field.name -> DynamicValue.Null)
    }
    ParsedOutput(
      values   = DynamicValue.Record(Chunk.from(entries)),
      rawText  = Some(output.text),
      metadata = Map("adapter" -> name)
    )

  private def parseStructured(layout: SignatureLayout, output: LmOutput): Either[DspyError, ParsedOutput] =
    for
      jsonText <- extractJson(output.text)
      root <- parseJsonObject(jsonText)
      entries <- layout.outputFields.foldLeft[Either[DspyError, Vector[(String, DynamicValue)]]](Right(Vector.empty)) { (acc, field) =>
        for
          soFar <- acc
          value <- root.obj.get(field.name) match
            case Some(raw) => coerce(field.typeRef, raw)
            case None      => Left(AdapterErrors.missingField(field.name))
        yield soFar :+ (field.name -> value)
      }
    yield ParsedOutput(
      values   = DynamicValue.Record(Chunk.from(entries)),
      rawText  = Some(output.text),
      metadata = Map("adapter" -> name)
    )

  private def extractJson(text: String): Either[DspyError, String] =
    val trimmed = text.trim
    if trimmed.startsWith("{") && trimmed.endsWith("}") then Right(trimmed)
    else
      val fencedPattern = "(?s)```json\\s*(\\{.*?\\})\\s*```".r
      fencedPattern.findFirstMatchIn(text).map(_.group(1)) match
        case Some(json) => Right(json)
        case None =>
          extractFirstJsonObject(text).toRight(ParseError("adapter", "Could not find JSON object in model output"))

  private def extractFirstJsonObject(text: String): Option[String] =
    val start = text.indexOf('{')
    if start < 0 then None
    else
      var depth = 0
      var end = -1
      var i = start
      var inString = false
      var escaped = false
      while i < text.length && end < 0 do
        val c = text.charAt(i)
        if inString then
          // Inside a JSON string literal: braces are data, not structure.
          if escaped then escaped = false
          else if c == '\\' then escaped = true
          else if c == '"' then inString = false
        else
          c match
            case '"' => inString = true
            case '{' => depth += 1
            case '}' =>
              depth -= 1
              if depth == 0 then end = i
            case _ => ()
        i += 1
      if end >= start then Some(text.substring(start, end + 1))
      else None

  private def parseJsonObject(raw: String): Either[DspyError, Value] =
    Try(ujson.read(raw)).toEither.left.map(error => ParseError("adapter", error.getMessage)).flatMap { value =>
      if value.objOpt.isDefined then Right(value)
      else Left(ParseError("adapter", "Parsed JSON output is not an object"))
    }

  private def coerce(typeRef: TypeRef, value: Value): Either[DspyError, DynamicValue] =
    typeRef match
      case TypeRef.int =>
        value.numOpt.map(_.toInt).toRight(ValidationError(s"Expected integer value, found: $value"))
          .map(i => DynamicValue.Primitive(PrimitiveValue.Int(i)))
      case TypeRef.double =>
        value.numOpt.toRight(ValidationError(s"Expected numeric value, found: $value"))
          .map(d => DynamicValue.Primitive(PrimitiveValue.Double(d)))
      case TypeRef.bool =>
        value.boolOpt.toRight(ValidationError(s"Expected boolean value, found: $value"))
          .map(b => DynamicValue.Primitive(PrimitiveValue.Boolean(b)))
      case TypeRef.json | TypeRef.list =>
        Right(JsonDynamic.fromUjson(value))
      case _ =>
        Right(DynamicValue.Primitive(PrimitiveValue.String(value.strOpt.getOrElse(renderJson(value)))))

  private def renderJson(value: Value): String =
    value match
      case ujson.Str(v) => v
      case other        => other.render()

  private def renderFields(fields: Vector[dspy4s.core.contracts.FieldSpec], values: DynamicValue.Record): String =
    fields.flatMap { field =>
      val rendered = DynamicValues.recordGet(values, field.name)
        .map(DynamicValues.renderText)
        .orElse(field.defaultValue.map(_.toString))
      rendered.map { value =>
        val prefix = field.prefix.getOrElse(s"${field.name}:")
        s"$prefix $value"
      }
    }.mkString("\n")
