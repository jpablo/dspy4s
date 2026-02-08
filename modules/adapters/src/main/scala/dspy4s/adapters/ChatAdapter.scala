package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterErrors
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.Signature
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.ValidationError
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole

import scala.util.matching.Regex

final case class ChatAdapter(name: String = "chat") extends Adapter:
  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    val systemMessage = Message(
      role = MessageRole.System,
      text = Some(invocation.signature.instructions.getOrElse(defaultInstructions(invocation.signature)))
    )

    val demoMessages = invocation.demos.flatMap { demo =>
      val userText = renderFields(invocation.signature.inputFields, demo.values)
      val assistantText = renderFields(invocation.signature.outputFields, demo.values)
      Vector(
        Message(role = MessageRole.User, text = Some(userText)),
        Message(role = MessageRole.Assistant, text = Some(assistantText))
      )
    }

    val inputMessage = Message(
      role = MessageRole.User,
      text = Some(renderFields(invocation.signature.inputFields, invocation.inputs.values))
    )

    Right(FormattedPrompt(messages = Vector(systemMessage) ++ demoMessages ++ Vector(inputMessage)))

  override def parse(signature: Signature, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
    signature.outputFields.foldLeft[Either[DspyError, Map[String, Any]]](Right(Map.empty)) { (acc, field) =>
      for
        soFar <- acc
        extracted <- extractField(field = field, text = output.text, isSingle = signature.outputFields.size == 1)
        coerced <- coerce(field.typeRef, extracted)
      yield soFar.updated(field.name, coerced)
    }.map { values =>
      ParsedOutput(values = values, rawText = Some(output.text), metadata = Map("adapter" -> name))
    }

  private def extractField(
      field: dspy4s.core.contracts.FieldSpec,
      text: String,
      isSingle: Boolean
  ): Either[DspyError, String] =
    val lineValue = extractByLabel(field, text)
    lineValue match
      case Some(value) => Right(value)
      case None if isSingle =>
        val trimmed = text.trim
        if trimmed.nonEmpty then Right(trimmed)
        else Left(AdapterErrors.missingField(field.name))
      case None =>
        Left(AdapterErrors.missingField(field.name))

  private def extractByLabel(field: dspy4s.core.contracts.FieldSpec, text: String): Option[String] =
    val label = field.prefix.getOrElse(s"${field.name}:").trim
    val labelPattern = ("(?im)^\\s*" + Regex.quote(label) + "\\s*(.*)$").r
    labelPattern.findFirstMatchIn(text).map(_.group(1).trim).filter(_.nonEmpty)

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

  private def defaultInstructions(signature: Signature): String =
    val outputs = signature.outputFields.map(_.name).mkString(", ")
    s"Return the requested outputs: $outputs"

  private def renderFields(fields: Vector[dspy4s.core.contracts.FieldSpec], values: Map[String, Any]): String =
    fields.flatMap { field =>
      val value = values.get(field.name).orElse(field.defaultValue).map(_.toString)
      value.map { v =>
        val prefix = field.prefix.getOrElse(s"${field.name}:")
        s"$prefix $v"
      }
    }.mkString("\n")
