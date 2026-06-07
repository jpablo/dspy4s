package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterErrors
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.NativeFunctionCalling
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.adapters.internal.JsonDynamic
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.ValidationError
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

import scala.util.matching.Regex

/** Chat-style adapter that frames each layout field with
  * `[[ ## field_name ## ]]` markers and terminates output with
  * `[[ ## completed ## ]]`. Mirrors Python DSPy's `ChatAdapter`.
  *
  * The marker framing is required for:
  *   - multi-line field values to round-trip cleanly,
  *   - unambiguous parsing when a field value contains a colon-prefixed line,
  *   - reliable streaming detection of field boundaries.
  */
final case class ChatAdapter(
    name: String = "chat",
    /** Enable provider-native function-calling. When on AND the signature declares a `tool_calls` output field AND
      * tool specs are supplied AND the resolved LM advertises `supportsFunctionCalling`, the `tools` are injected
      * into the request option bag and the tool-calls field is filled from the provider's structured `tool_calls`
      * instead of being requested as text. Off by default (mirrors dspy's `use_native_function_calling`). */
    useNativeFunctionCalling: Boolean = false,
    /** When native function-calling is active, request provider-side parallel tool-call generation. `None` leaves
      * the knob unset (provider default). */
    parallelToolCalls: Option[Boolean] = None,
    /** When native function-calling is active, set the provider `tool_choice` (e.g. `"auto"`, `"required"`,
      * `"none"`). `None` leaves it unset (provider default). */
    toolChoice: Option[String] = None
) extends Adapter:

  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    val layout = invocation.layout
    // A `tool_calls`-typed output field is filled from the provider's structured tool_calls, never asked for as
    // text, so it is excluded from the rendered prompt. No-op for ordinary signatures (byte-identical output).
    val renderLayout = layout.withFields(layout.fields.filterNot(NativeFunctionCalling.isToolCallsField))

    val systemMessage = Message(
      role = MessageRole.System,
      text = Some(buildSystemPrompt(renderLayout, invocation.outputJsonSchema))
    )

    val demoMessages = invocation.demos.flatMap { demo =>
      val userText = renderInputs(renderLayout.inputFields, demo.values)
      val assistantText = renderOutputs(renderLayout.outputFields, demo.values) + "\n\n" + ChatAdapter.CompletedMarker + "\n"
      Vector(
        Message(role = MessageRole.User, text = Some(userText)),
        Message(role = MessageRole.Assistant, text = Some(assistantText))
      )
    }

    val inputMessage = Message(
      role = MessageRole.User,
      text = Some(
        renderInputs(renderLayout.inputFields, invocation.inputs.values) + "\n\n" + outputRequirements(renderLayout)
      )
    )

    Right(FormattedPrompt(
      messages       = Vector(systemMessage) ++ demoMessages ++ Vector(inputMessage),
      requestOptions = NativeFunctionCalling.toolOptions(layout, invocation.tools, useNativeFunctionCalling, parallelToolCalls, toolChoice)
    ))

  override def streamingState(layout: SignatureLayout): Option[AdapterStreamingState] =
    Some(new ChatStreamingState(layout.outputFields))

  override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
    // A `tool_calls`-typed field is filled from the structured `output.toolCalls`, not from the text, so it is
    // excluded from marker extraction and the single-output fallback.
    val textFields  = layout.outputFields.filterNot(NativeFunctionCalling.isToolCallsField)
    val outputNames = textFields.map(_.name).toSet
    val sections    = extractSections(output.text, outputNames)

    val values = sections.view.filterKeys(outputNames.contains).toMap

    // Single-output fallback: when the model produced no markers and there is exactly one TEXT output, treat the
    // whole text as that field.
    val resolved =
      if values.isEmpty && textFields.size == 1 && output.text.trim.nonEmpty then
        Map(textFields.head.name -> output.text.trim)
      else values

    layout.outputFields.foldLeft[Either[DspyError, Vector[(String, DynamicValue)]]](Right(Vector.empty)) { (acc, field) =>
      for
        soFar <- acc
        entry <-
          if NativeFunctionCalling.isToolCallsField(field) then
            Right(field.name -> NativeFunctionCalling.encodeToolCalls(output.toolCalls))
          else
            resolved.get(field.name) match
              case Some(v) => coerce(field.typeRef, v).map(coerced => field.name -> coerced)
              case None =>
                // On a tool-call turn (tool_calls present) the text output fields can legitimately be absent; default
                // them to Null rather than erroring (mirrors dspy's `setdefault(field, None)`). Otherwise it's a miss.
                if output.toolCalls.nonEmpty then Right(field.name -> DynamicValue.Null)
                else Left(AdapterErrors.missingField(field.name))
      yield soFar :+ entry
    }.map { entries =>
      ParsedOutput(
        values   = DynamicValue.Record(Chunk.from(entries)),
        rawText  = Some(output.text),
        metadata = Map("adapter" -> name)
      )
    }

  /** Walks the LM completion line by line, opening a new section every time a
    * line (after stripping) matches the `[[ ## name ## ]]` marker. Trailing
    * content after the marker on the same line is treated as the first line
    * of that section. */
  private def extractSections(text: String, outputNames: Set[String]): Map[String, String] =
    val out = scala.collection.mutable.LinkedHashMap.empty[String, StringBuilder]
    var currentField: Option[String] = None
    text.split('\n').foreach { rawLine =>
      val stripped = rawLine.trim
      ChatAdapter.MarkerPattern.findPrefixMatchOf(stripped) match
        case Some(m) =>
          val fieldName = m.group(1)
          val trailing = stripped.substring(m.end).trim
          if fieldName == ChatAdapter.CompletedFieldName then
            currentField = None
          else if outputNames.contains(fieldName) then
            val sb = out.getOrElseUpdate(fieldName, new StringBuilder)
            if trailing.nonEmpty then
              if sb.nonEmpty then sb.append('\n')
              sb.append(trailing)
            currentField = Some(fieldName)
          else
            // Unknown marker — likely an input echo or a hallucinated section.
            // Stop accumulating to any prior field; ignore until the next
            // recognised marker.
            currentField = None
        case None =>
          currentField.foreach { name =>
            val sb = out(name)
            if sb.nonEmpty then sb.append('\n')
            sb.append(rawLine)
          }
    }
    out.iterator.map { (k, v) => k -> v.toString.stripTrailing }.toMap

  private def buildSystemPrompt(layout: SignatureLayout, outputJsonSchema: Option[String]): String =
    val inputBlock = fieldDescriptionBlock(layout.inputFields, role = "input")
    val outputBlock = fieldDescriptionBlock(layout.outputFields, role = "output")
    // When the typed Predict path supplies the output Shape's JSON schema, surface it so the LM knows the
    // nested structure of record/list output fields (which the flat field list cannot convey). Absent (e.g.
    // DynamicPredict), the prompt is byte-for-byte unchanged.
    val schemaBlock = outputJsonSchema match
      case Some(schema) =>
        s"\n\nYour output fields must conform to this JSON schema:\n$schema"
      case None => ""
    val structureExample = exampleStructure(layout)
    val instructions =
      layout.instructions.getOrElse(defaultInstructions(layout))
    s"""$inputBlock
       |
       |$outputBlock$schemaBlock
       |
       |All interactions will be structured in the following way, with the appropriate values filled in.
       |
       |$structureExample
       |
       |In adhering to this structure, your objective is: $instructions""".stripMargin

  /** Numbered field list mirroring Python's `get_field_description_string`.
    * Each line:
    *   `  N. `field_name` (type): description`
    * with the description omitted when `FieldSpec.description` is the
    * default `${field_name}` placeholder that layout normalisation
    * inserts (see `FieldSpec.normalize`). */
  private def fieldDescriptionBlock(fields: Vector[FieldSpec], role: String): String =
    if fields.isEmpty then s"Your $role fields are: (none)."
    else
      val header = s"Your $role fields are:"
      val lines = fields.zipWithIndex.map { case (field, idx) =>
        val typeName = ChatAdapter.displayTypeName(field.typeRef)
        val descPart = field.description match
          case Some(desc) if desc != s"$${${field.name}}" && desc.nonEmpty =>
            s": $desc"
          case _ => ""
        // Mirror Python's `get_field_description_string`: when the field carries constraints, append a
        // "Constraints: <joined>" suffix after the description. Joined with ", " (single-line field list).
        val constraintsPart =
          if field.constraints.nonEmpty then s" Constraints: ${field.constraints.mkString(", ")}"
          else ""
        val enumPart =
          if field.enumValues.nonEmpty then s" (must be one of: ${field.enumValues.mkString(", ")})"
          else ""
        s"${idx + 1}. `${field.name}` ($typeName)$descPart$constraintsPart$enumPart"
      }
      (header +: lines).mkString("\n")

  private def defaultInstructions(layout: SignatureLayout): String =
    val inputs = layout.inputFields.map(_.name).mkString(", ")
    val outputs = layout.outputFields.map(_.name).mkString(", ")
    s"Given the fields $inputs, produce the fields $outputs."

  /** Renders an example showing the full marker framing — input markers,
    * output markers, and the closing `[[ ## completed ## ]]`. Output-field
    * placeholders carry a `# note:` typing constraint when the field has
    * a non-string type, reinforcing the type contract structurally. */
  private def exampleStructure(layout: SignatureLayout): String =
    val inputBlock = layout.inputFields.map { field =>
      s"[[ ## ${field.name} ## ]]\n{${field.name}}"
    }.mkString("\n\n")
    val outputBlock = layout.outputFields.map { field =>
      val note = ChatAdapter.structureHint(field.typeRef).fold("") { hint =>
        s"        # note: the value you produce $hint"
      }
      s"[[ ## ${field.name} ## ]]\n{${field.name}}$note"
    }.mkString("\n\n")
    Vector(inputBlock, outputBlock, ChatAdapter.CompletedMarker).filter(_.nonEmpty).mkString("\n\n")

  private def outputRequirements(layout: SignatureLayout): String =
    val outputs = layout.outputFields.map { f =>
      val hint = ChatAdapter.reminderHint(f.typeRef).fold("") { h => s" ($h)" }
      s"`[[ ## ${f.name} ## ]]`$hint"
    }.mkString(", then ")
    s"Respond with the corresponding output fields, starting with the field $outputs, and then ending with the marker for `${ChatAdapter.CompletedMarker}`."

  private def renderInputs(fields: Vector[FieldSpec], values: DynamicValue.Record): String =
    renderFieldBlock(fields, values)

  private def renderOutputs(fields: Vector[FieldSpec], values: DynamicValue.Record): String =
    renderFieldBlock(fields, values)

  private def renderFieldBlock(fields: Vector[FieldSpec], values: DynamicValue.Record): String =
    fields.flatMap { field =>
      val resolved = DynamicValues.recordGet(values, field.name)
        .map(DynamicValues.renderText)
        .orElse(field.defaultValue.map(_.toString))
      resolved.map(rendered => s"[[ ## ${field.name} ## ]]\n$rendered")
    }.mkString("\n\n")

  private def coerce(typeRef: TypeRef, raw: String): Either[DspyError, DynamicValue] =
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

object ChatAdapter:
  /** Pattern matching `[[ ## field_name ## ]]`. Capture group 1 is the
    * field name. The pattern is intentionally anchored to start-of-string
    * by callers (`findPrefixMatchOf`), so leading whitespace is the
    * caller's responsibility to strip. */
  val MarkerPattern: Regex = """\[\[ ## (\w+) ## \]\]""".r

  /** Reserved field name that closes the structured output. */
  val CompletedFieldName: String = "completed"
  val CompletedMarker: String = s"[[ ## $CompletedFieldName ## ]]"

  /** Canonical type name to surface in the system prompt's field
    * description block. Maps dspy4s's internal `TypeRef.repr` to the
    * names users will recognise (and that match Python DSPy). */
  def displayTypeName(t: TypeRef): String = t match
    case TypeRef.string => "str"
    case TypeRef.int    => "int"
    case TypeRef.double => "float"
    case TypeRef.bool   => "bool"
    case TypeRef.json   => "dict"
    case TypeRef.list   => "list"
    case other          => other.repr

  /** Hint phrasing for the final-user-message reminder
    * ("Respond with `[[ ## answer ## ]]` (must be …)"). Returns `None`
    * for strings (no hint needed) and a "(must be formatted as a valid …)"
    * string otherwise. */
  def reminderHint(t: TypeRef): Option[String] = t match
    case TypeRef.string => None
    case TypeRef.list   => Some("must be a valid JSON array")
    case _              => Some(s"must be formatted as a valid ${displayTypeName(t)}")

  /** Hint phrasing for the structure-example `# note: ...` comments.
    * More specific than the reminder hint: enumerates booleans and gives
    * a brief "single X value" form for scalars. */
  def structureHint(t: TypeRef): Option[String] = t match
    case TypeRef.string => None
    case TypeRef.int    => Some("must be a single int value")
    case TypeRef.double => Some("must be a single float value")
    case TypeRef.bool   => Some("must be true or false")
    case TypeRef.json   => Some("must be a valid JSON object")
    case TypeRef.list   => Some("must be a valid JSON array")
    case other          => Some(s"must be a valid ${displayTypeName(other)}")
