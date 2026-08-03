package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterConstraints
import dspy4s.adapters.contracts.AdapterErrors
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.adapters.internal.AdapterTextSupport
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.lm.contracts.LmOutput
import zio.blocks.schema.DynamicValue

import scala.util.Try
import scala.util.matching.Regex
import scala.xml.Elem
import scala.xml.XML

final case class XMLAdapter(
    name: String = "xml",
    allowTextFallbackForSingleOutput: Boolean = true
) extends Adapter:
  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    // G-9: this adapter renders no prose field-description block (only `<field>` tags), so field constraints are
    // surfaced as a consolidated block appended to the system instruction (shared with JSONAdapter).
    val fieldTags = invocation.layout.outputFields.map(field => s"<${field.name}>...</${field.name}>").mkString("\n")
    val xmlInstruction =
      s"Return XML only using this shape:\n<outputs>\n$fieldTags\n</outputs>"
    val baseSystemText = invocation.layout.instructions match
      case Some(instructions) => s"$instructions\n\n$xmlInstruction"
      case None               => xmlInstruction
    val systemText = AdapterConstraints.appendTo(baseSystemText, invocation.layout.outputFields)

    Right(
      FormattedPrompt(
        messages = AdapterTextSupport.fewShotMessages(invocation, systemText)(values =>
          buildOutputXml(invocation.layout, values)
        )
      )
    )

  override def streamingState(layout: SignatureLayout): Option[AdapterStreamingState] =
    Some(new XmlStreamingState(layout.outputFields))

  override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
    // The plain-text fallback only applies when the reply is not an XML document at all (the model ignored the
    // format). Falling back on ANY structured failure would mask real errors: a present-but-unfillable field
    // used to return the ENTIRE raw XML document as the field's "value".
    extractXml(output.text).flatMap(parseXml) match
      case Right(document) => parseFields(layout, document, output)
      case Left(_) =>
        if allowTextFallbackForSingleOutput && layout.outputFields.size == 1 then
          AdapterTextSupport.singleOutputTextFallback(name, layout, output)
        else Left(ParseError("adapter", "XML parse failed and no fallback was applied", raw = Some(output.text)))

  private def parseFields(layout: SignatureLayout, document: Elem, output: LmOutput): Either[DspyError, ParsedOutput] =
    AdapterTextSupport.decodeOutputFields(layout) { field =>
      for
        raw <- extractFieldText(document, field.name).toRight(AdapterErrors.missingField(field.name, Some(output.text)))
        coerced <- AdapterTextSupport.coerceText(field.typeRef, raw)
      yield coerced
    }.map(entries => AdapterTextSupport.parsedOutput(name, output, entries))

  private def buildOutputXml(layout: SignatureLayout, values: DynamicValue.Record): String =
    val body = layout.outputFields.flatMap { field =>
      DynamicValues.recordGet(values, field.name).map { value =>
        s"<${field.name}>${escapeXml(DynamicValues.renderText(value))}</${field.name}>"
      }
    }.mkString
    s"<outputs>$body</outputs>"

  private def extractXml(text: String): Either[DspyError, String] =
    val trimmed = text.trim
    if trimmed.startsWith("<") then Right(trimmed)
    else
      XMLAdapter.FencedXml.findFirstMatchIn(text).map(_.group(1)) match
        case Some(xml) => Right(xml)
        case None =>
          val first = text.indexOf('<')
          val last = text.lastIndexOf('>')
          if first >= 0 && last > first then Right(text.substring(first, last + 1))
          else Left(ParseError("adapter", "Could not find XML document in model output"))

  private def parseXml(raw: String): Either[DspyError, Elem] =
    Try(XML.loadString(raw)).toEither.left.map(error => ParseError("adapter", error.getMessage))

  /** A present-but-empty tag is a PRESENT field with an empty value (`Some("")`), not a missing field —
    * treating it as missing used to route single-output signatures into the text fallback, which returned the
    * whole raw XML document as the field's value. */
  private def extractFieldText(xml: Elem, fieldName: String): Option[String] =
    (xml \\ fieldName).headOption.map(_.text.trim)

  private def escapeXml(value: String): String =
    value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")

object XMLAdapter:
  /** Fenced ```xml block extractor, compiled once (parse runs per LM completion). */
  private val FencedXml: Regex = "(?s)```xml\\s*(<.*?>.*?</.*?>)\\s*```".r
