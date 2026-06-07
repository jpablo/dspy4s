package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterErrors
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.adapters.internal.JsonDynamic
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.ValidationError
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
    allowTextFallbackForSingleOutput: Boolean = true
) extends Adapter:
  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    // NOTE (G-9): this adapter emits no prose field-description block (only a key list or a JSON Schema), so
    // there is no `get_field_description_string` analog to append `FieldSpec.constraints` to. TODO: embed
    // constraints into the emitted JSON Schema (e.g. `exclusiveMinimum`, `maxLength`) as a follow-up.
    val fieldList = invocation.layout.outputFields.map(_.name).mkString(", ")
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
    val systemText = invocation.layout.instructions match
      case Some(instructions) => s"$instructions\n\n$jsonInstruction"
      case None               => jsonInstruction

    val demoMessages = invocation.demos.flatMap { demo =>
      val userText = renderFields(invocation.layout.inputFields, demo.values)
      val assistantJson = invocation.layout.outputFields
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
        requestOptions = responseFormatOptions(invocation)
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
    * NOTE (follow-up): native FUNCTION CALLING (injecting `tools` / `tool_choice` from `ToolSchemaBridge` when
    * `supportsFunctionCalling`, plus parsing native `tool_calls` from the response and ReAct rewiring) reuses this
    * same `requestOptions` seam but is out of scope for v1. */
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
                "schema" -> schema,
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

  /** OpenAI requires the `json_schema.name` to match `^[a-zA-Z0-9_-]+$`. Replace any other character with `_`;
    * fall back to a constant when the result would be empty. */
  private def sanitizeSchemaName(name: String): String =
    val cleaned = name.replaceAll("[^a-zA-Z0-9_-]", "_")
    if cleaned.isEmpty then "response_schema" else cleaned

  override def streamingState(layout: SignatureLayout): Option[AdapterStreamingState] =
    Some(new JsonStreamingState(layout.outputFields))

  override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
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
