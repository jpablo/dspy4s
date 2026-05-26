package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterErrors
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.ValidationError
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

import scala.util.Try
import scala.xml.Elem
import scala.xml.XML

final case class XMLAdapter(
    name: String = "xml",
    allowTextFallbackForSingleOutput: Boolean = true
) extends Adapter:
  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    val fieldTags = invocation.layout.outputFields.map(field => s"<${field.name}>...</${field.name}>").mkString("\n")
    val xmlInstruction =
      s"Return XML only using this shape:\n<outputs>\n$fieldTags\n</outputs>"
    val systemText = invocation.layout.instructions match
      case Some(instructions) => s"$instructions\n\n$xmlInstruction"
      case None               => xmlInstruction

    val demoMessages = invocation.demos.flatMap { demo =>
      val userText = renderFields(invocation.layout.inputFields, demo.values)
      val assistantXml = buildOutputXml(invocation.layout, demo.values)
      Vector(
        Message(role = MessageRole.User, text = Some(userText)),
        Message(role = MessageRole.Assistant, text = Some(assistantXml))
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
        )
      )
    )

  override def streamingState(layout: SignatureLayout): Option[AdapterStreamingState] =
    Some(new XmlStreamingState(layout.outputFields))

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
      else Left(ParseError("adapter", "XML parse failed and no fallback was applied"))
    }

  private def parseStructured(layout: SignatureLayout, output: LmOutput): Either[DspyError, ParsedOutput] =
    for
      xmlText <- extractXml(output.text)
      document <- parseXml(xmlText)
      entries <- layout.outputFields.foldLeft[Either[DspyError, Vector[(String, DynamicValue)]]](Right(Vector.empty)) { (acc, field) =>
        for
          soFar <- acc
          raw <- extractFieldText(document, field.name).toRight(AdapterErrors.missingField(field.name))
          coerced <- coerce(field.typeRef, raw)
        yield soFar :+ (field.name -> coerced)
      }
    yield ParsedOutput(
      values   = DynamicValue.Record(Chunk.from(entries)),
      rawText  = Some(output.text),
      metadata = Map("adapter" -> name)
    )

  private def buildOutputXml(layout: SignatureLayout, values: DynamicValue.Record): String =
    val body = layout.outputFields.flatMap { field =>
      DynamicValues.recordGet(values, field.name).map { value =>
        s"<${field.name}>${escapeXml(DynamicValues.renderText(value))}</${field.name}>"
      }
    }.mkString
    s"<outputs>$body</outputs>"

  private def renderFields(fields: Vector[dspy4s.core.contracts.FieldSpec], values: DynamicValue.Record): String =
    fields.flatMap { field =>
      val value = DynamicValues.recordGet(values, field.name)
        .map(DynamicValues.renderText)
        .orElse(field.defaultValue.map(_.toString))
      value.map { v =>
        val prefix = field.prefix.getOrElse(s"${field.name}:")
        s"$prefix $v"
      }
    }.mkString("\n")

  private def extractXml(text: String): Either[DspyError, String] =
    val trimmed = text.trim
    if trimmed.startsWith("<") then Right(trimmed)
    else
      val fenced = "(?s)```xml\\s*(<.*?>.*?</.*?>)\\s*```".r
      fenced.findFirstMatchIn(text).map(_.group(1)) match
        case Some(xml) => Right(xml)
        case None =>
          val first = text.indexOf('<')
          val last = text.lastIndexOf('>')
          if first >= 0 && last > first then Right(text.substring(first, last + 1))
          else Left(ParseError("adapter", "Could not find XML document in model output"))

  private def parseXml(raw: String): Either[DspyError, Elem] =
    Try(XML.loadString(raw)).toEither.left.map(error => ParseError("adapter", error.getMessage))

  private def extractFieldText(xml: Elem, fieldName: String): Option[String] =
    (xml \\ fieldName).headOption.map(_.text.trim).filter(_.nonEmpty)

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
      case _ =>
        Right(DynamicValue.Primitive(PrimitiveValue.String(raw)))

  private def escapeXml(value: String): String =
    value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
