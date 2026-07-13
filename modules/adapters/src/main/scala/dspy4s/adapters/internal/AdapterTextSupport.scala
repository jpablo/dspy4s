package dspy4s.adapters.internal

import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.ValidationError
import dspy4s.lm.contracts.LmOutput
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** Prompt-text helpers shared across the adapters. One implementation guarantees the adapters render demo /
  * input fields, coerce string field values, and apply the single-output text fallback identically — fixing a
  * defect in one adapter can no longer silently miss the others. */
private[adapters] object AdapterTextSupport:

  /** Render fields as `prefix value` lines (prefix falls back to `name:`, value falls back to the field's
    * `defaultValue`). Used for demo / input user messages by the JSON, XML, and TwoStep adapters. */
  def renderFields(fields: Vector[FieldSpec], values: DynamicValue.Record): String =
    fields.flatMap { field =>
      val rendered = DynamicValues.recordGet(values, field.name)
        .map(DynamicValues.renderText)
        .orElse(field.defaultValue.map(_.toString))
      rendered.map { value =>
        val prefix = field.prefix.getOrElse(s"${field.name}:")
        s"$prefix $value"
      }
    }.mkString("\n")

  /** Coerce a raw string field value into the `DynamicValue` its declared `TypeRef` demands. Shared by the
    * Chat and XML adapters so a `json` / `list` typed field decodes identically regardless of which adapter
    * parsed it. */
  def coerceText(typeRef: TypeRef, raw: String): Either[DspyError, DynamicValue] =
    typeRef match
      case TypeRef.int =>
        raw.toIntOption.toRight(ValidationError(s"Cannot parse integer output from '$raw'"))
          .map(i => DynamicValue.Primitive(PrimitiveValue.Int(i)))
      case TypeRef.double =>
        raw.toDoubleOption.toRight(ValidationError(s"Cannot parse double output from '$raw'"))
          .map(d => DynamicValue.Primitive(PrimitiveValue.Double(d)))
      case TypeRef.bool =>
        raw.trim.toLowerCase match
          case "true"  => Right(DynamicValue.Primitive(PrimitiveValue.Boolean(true)))
          case "false" => Right(DynamicValue.Primitive(PrimitiveValue.Boolean(false)))
          case other   => Left(ValidationError(s"Cannot parse boolean output from '$other'"))
      case TypeRef.json | TypeRef.list =>
        JsonDynamic.parse(raw).left.map(_ =>
          ValidationError(s"Field could not be parsed as JSON from '$raw'")
        )
      case _ =>
        Right(DynamicValue.Primitive(PrimitiveValue.String(raw)))

  /** Single-output plain-text fallback shared by the JSON and XML adapters: when structured parsing failed at
    * the DOCUMENT level and the signature has exactly one output field, treat the whole (trimmed, non-empty)
    * reply as that field's value. Callers gate on their `allowTextFallbackForSingleOutput` flag and field
    * count before invoking. */
  def singleOutputTextFallback(
      adapterName: String,
      layout: SignatureLayout,
      output: LmOutput
  ): Either[DspyError, ParsedOutput] =
    val field = layout.outputFields.head
    val trimmed = output.text.trim
    if trimmed.nonEmpty then
      Right(
        ParsedOutput(
          values = DynamicValue.Record(Chunk.single(
            field.name -> DynamicValue.Primitive(PrimitiveValue.String(trimmed))
          )),
          rawText = Some(output.text),
          metadata = Map("adapter" -> adapterName, "fallback" -> "text")
        )
      )
    else Left(ParseError("adapter", "Cannot fallback from empty model output", raw = Some(output.text)))
