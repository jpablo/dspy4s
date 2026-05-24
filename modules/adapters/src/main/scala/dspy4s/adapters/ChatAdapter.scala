package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterErrors
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.Signature
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.ValidationError
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole

import scala.util.matching.Regex

/** Chat-style adapter that frames each signature field with
  * `[[ ## field_name ## ]]` markers and terminates output with
  * `[[ ## completed ## ]]`. Mirrors Python DSPy's `ChatAdapter`.
  *
  * The marker framing is required for:
  *   - multi-line field values to round-trip cleanly,
  *   - unambiguous parsing when a field value contains a colon-prefixed line,
  *   - reliable streaming detection of field boundaries.
  */
final case class ChatAdapter(name: String = "chat") extends Adapter:

  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    val signature = invocation.signature

    val systemMessage = Message(
      role = MessageRole.System,
      text = Some(buildSystemPrompt(signature))
    )

    val demoMessages = invocation.demos.flatMap { demo =>
      val userText = renderInputs(signature.inputFields, demo.values)
      val assistantText = renderOutputs(signature.outputFields, demo.values) + "\n\n" + ChatAdapter.CompletedMarker + "\n"
      Vector(
        Message(role = MessageRole.User, text = Some(userText)),
        Message(role = MessageRole.Assistant, text = Some(assistantText))
      )
    }

    val inputMessage = Message(
      role = MessageRole.User,
      text = Some(
        renderInputs(signature.inputFields, invocation.inputs.values) + "\n\n" + outputRequirements(signature)
      )
    )

    Right(FormattedPrompt(messages = Vector(systemMessage) ++ demoMessages ++ Vector(inputMessage)))

  override def streamingState(signature: Signature): Option[AdapterStreamingState] =
    Some(new ChatStreamingState(signature.outputFields))

  override def parse(signature: Signature, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
    val outputNames = signature.outputFields.map(_.name).toSet
    val sections = extractSections(output.text, outputNames)

    val values = sections.view.filterKeys(outputNames.contains).toMap

    // Single-output fallback: when the model produced no markers and the
    // signature has exactly one output, treat the whole text as that field.
    val resolved =
      if values.isEmpty && signature.outputFields.size == 1 && output.text.trim.nonEmpty then
        Map(signature.outputFields.head.name -> output.text.trim)
      else values

    signature.outputFields.foldLeft[Either[DspyError, Map[String, Any]]](Right(Map.empty)) { (acc, field) =>
      for
        soFar <- acc
        raw <- resolved.get(field.name) match
          case Some(v) => Right(v)
          case None    => Left(AdapterErrors.missingField(field.name))
        coerced <- coerce(field.typeRef, raw)
      yield soFar.updated(field.name, coerced)
    }.map { values =>
      ParsedOutput(values = values, rawText = Some(output.text), metadata = Map("adapter" -> name))
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

  private def buildSystemPrompt(signature: Signature): String =
    val inputs = signature.inputFields.map(_.name).mkString(", ")
    val outputs = signature.outputFields.map(_.name).mkString(", ")
    val structureExample = exampleStructure(signature)
    val instructions =
      signature.instructions.getOrElse(s"Given the fields ${inputs}, produce the fields ${outputs}.")
    s"""Your input fields are: $inputs.
       |Your output fields are: $outputs.
       |
       |All interactions will be structured in the following way, with the appropriate values filled in.
       |
       |$structureExample
       |
       |In adhering to this structure, your objective is: $instructions""".stripMargin

  /** Renders an example showing the full marker framing — input markers,
    * output markers, and the closing `[[ ## completed ## ]]`. The model
    * sees this in the system prompt so it knows exactly what shape to
    * produce. */
  private def exampleStructure(signature: Signature): String =
    val inputBlock = signature.inputFields.map { field =>
      s"[[ ## ${field.name} ## ]]\n{${field.name}}"
    }.mkString("\n\n")
    val outputBlock = signature.outputFields.map { field =>
      s"[[ ## ${field.name} ## ]]\n{${field.name}}"
    }.mkString("\n\n")
    Vector(inputBlock, outputBlock, ChatAdapter.CompletedMarker).filter(_.nonEmpty).mkString("\n\n")

  private def outputRequirements(signature: Signature): String =
    val outputs = signature.outputFields.map(f => s"`[[ ## ${f.name} ## ]]`").mkString(", then ")
    s"Respond with the corresponding output fields, starting with the field $outputs, and then ending with the marker for `${ChatAdapter.CompletedMarker}`."

  private def renderInputs(fields: Vector[FieldSpec], values: Map[String, Any]): String =
    fields.flatMap { field =>
      values.get(field.name).orElse(field.defaultValue).map { value =>
        s"[[ ## ${field.name} ## ]]\n${value.toString}"
      }
    }.mkString("\n\n")

  private def renderOutputs(fields: Vector[FieldSpec], values: Map[String, Any]): String =
    fields.flatMap { field =>
      values.get(field.name).orElse(field.defaultValue).map { value =>
        s"[[ ## ${field.name} ## ]]\n${value.toString}"
      }
    }.mkString("\n\n")

  private def coerce(typeRef: TypeRef, raw: String): Either[DspyError, Any] =
    typeRef match
      case TypeRef.int =>
        raw.toIntOption.toRight(ValidationError(s"Cannot parse integer output from '$raw'"))
      case TypeRef.double =>
        raw.toDoubleOption.toRight(ValidationError(s"Cannot parse double output from '$raw'"))
      case TypeRef.bool =>
        raw.trim.toLowerCase match
          case "true"  => Right(true)
          case "false" => Right(false)
          case other   => Left(ValidationError(s"Cannot parse boolean output from '$other'"))
      case _ =>
        Right(raw)

object ChatAdapter:
  /** Pattern matching `[[ ## field_name ## ]]`. Capture group 1 is the
    * field name. The pattern is intentionally anchored to start-of-string
    * by callers (`findPrefixMatchOf`), so leading whitespace is the
    * caller's responsibility to strip. */
  val MarkerPattern: Regex = """\[\[ ## (\w+) ## \]\]""".r

  /** Reserved field name that closes the structured output. */
  val CompletedFieldName: String = "completed"
  val CompletedMarker: String = s"[[ ## $CompletedFieldName ## ]]"
