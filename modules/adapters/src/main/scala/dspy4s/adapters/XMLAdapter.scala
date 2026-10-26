package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.NativeFunctionCalling
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.adapters.internal.AdapterTextSupport
import dspy4s.adapters.internal.ConversationHistory
import dspy4s.adapters.internal.PromptText
import dspy4s.adapters.internal.PromptText.OutputSchema
import dspy4s.adapters.internal.TurnText
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.PythonText
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.lm.contracts.LmOutput
import zio.blocks.schema.DynamicValue

import scala.util.Try
import scala.util.matching.Regex
import scala.xml.Elem
import scala.xml.XML

/** The XML adapter (upstream `XMLAdapter` in `dspy/adapters/xml_adapter.py`). Each field is a tag:
  * `<name>\nvalue\n</name>`. The system message has the form of the chat adapter.
  *
  * Decision A6: the prompt asks for the value of a list, dict, or model output as JSON text inside its tag, and the
  * parser reads that text as JSON. Upstream asks for nested XML elements. The structure and the output reminder thus
  * have the form that upstream uses for each other output type.
  */
final case class XMLAdapter(
    name                            : String  = "xml",
    allowTextFallbackForSingleOutput: Boolean = true
) extends Adapter:
  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    val layout     = invocation.layout
    val schema     = OutputSchema(invocation.outputJsonSchema)
    val systemText = PromptText.systemMessage(layout, layout, fieldStructure(layout, schema))

    // This adapter has no native function calling: a turn of the history with tool calls stays text, and the request
    // has no tool options (upstream `Adapter._call_preprocess`).
    Right(
      FormattedPrompt(
        messages = ConversationHistory.messages(
          invocation,
          renderLayout = layout,
          systemText,
          useNativeFunctionCalling = false,
          Turns
        ),
        removedOptions = NativeFunctionCalling.ToolOptionKeys
      )
    )

  override def streamingState(layout: SignatureLayout)(using RuntimeContext): Option[AdapterStreamingState] =
    Some(new XmlStreamingState(layout.outputFields))

  /** The tag form of the input fields, with no output reminder (upstream `XMLAdapter.format_user_message_content`). */
  override def formatUserMessageContent(layout: SignatureLayout, values: DynamicValue.Record): String =
    Turns.user(layout, values, mainRequest = false)

  override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
    // The plain-text fallback only applies when the reply is not an XML document at all (the model ignored the
    // format). Falling back on ANY structured failure would mask real errors: a present-but-unfillable field
    // used to return the ENTIRE raw XML document as the field's "value".
    extractXml(output.text).flatMap(parseXml) match
      case Right(document) => parseFields(layout, document, output)
      case Left(_)         =>
        if allowTextFallbackForSingleOutput && layout.outputFields.size == 1 then
          AdapterTextSupport.singleOutputTextFallback(name, layout, output)
        else Left(ParseError("adapter", "XML parse failed and no fallback was applied", raw = Some(output.text)))

  /** The values of the output fields of `document`. A text `tool_calls` field takes the JSON list of calls in its text,
    * as in the chat and JSON formats (upstream `parse_value` with `ToolCalls`).
    */
  private def parseFields(layout: SignatureLayout, document: Elem, output: LmOutput): Either[DspyError, ParsedOutput] =
    AdapterTextSupport.decodeOutputFields(layout, output.text) { field =>
      extractFieldText(document, field.name) match
        case Some(raw) => AdapterTextSupport.outputTextValue(field, raw).map(Some(_))
        case None      => Right(None)
    }.map(entries => AdapterTextSupport.parsedOutput(name, output, entries))

  /** The text of a turn: the tag form of the input fields for a user turn, with the output reminder in a current
    * request, and the tag form of the output fields for an assistant turn (upstream
    * `XMLAdapter.format_user_message_content` and `format_assistant_message_content`).
    */
  private object Turns extends TurnText:
    override def user(layout: SignatureLayout, values: DynamicValue.Record, mainRequest: Boolean): String =
      val inputs = layout.inputFields.flatMap { field =>
        DynamicValues.recordGet(values, field.name).map(value => tag(field, PromptText.fieldValue(field, value)))
      }
      val reminder = Option.when(mainRequest)(outputRequirements(layout))
      PythonText.strip((Vector(
        "",
        PythonText.strip(inputs.mkString("\n\n"))
      ) ++ reminder.toVector :+ "").mkString("\n\n"))

    override def assistant(layout: SignatureLayout, values: DynamicValue.Record): String =
      PythonText.strip(layout.outputFields.map { field =>
        val text =
          PromptText.fieldValue(field, DynamicValues.recordGet(values, field.name).getOrElse(DynamicValue.Null))
        tag(field, if PromptText.isStr(field) then escapeXml(text) else text)
      }.mkString("\n\n"))

  /** The structure of the system message (upstream `XMLAdapter.format_field_structure`). */
  private def fieldStructure(layout: SignatureLayout, schema: OutputSchema): String =
    def fields(group: Vector[FieldSpec], output: Boolean): String =
      group.map(field => tag(field, PromptText.placeholder(field, output, schema))).mkString("\n\n")
    Vector(
      PromptText.StructureHeader,
      fields(layout.inputFields, output = false),
      fields(layout.outputFields, output = true)
    )
      .mkString("\n\n")

  /** The output reminder of the user turn (upstream `XMLAdapter.user_message_output_requirements`, with no nested XML
    * structure by decision A6).
    */
  private def outputRequirements(layout: SignatureLayout): String =
    "Respond with the corresponding output fields wrapped in XML tags " +
      layout.outputFields.map(field => s"`<${field.name}>`").mkString(", then ") + "."

  private def tag(field: FieldSpec, text: String): String =
    s"<${field.name}>\n$text\n</${field.name}>"

  private def extractXml(text: String): Either[DspyError, String] =
    val trimmed = text.trim
    if trimmed.startsWith("<") then Right(trimmed)
    else
      XMLAdapter.FencedXml.findFirstMatchIn(text).map(_.group(1)) match
        case Some(xml) => Right(xml)
        case None      =>
          val first = text.indexOf('<')
          val last  = text.lastIndexOf('>')
          if first >= 0 && last > first then Right(text.substring(first, last + 1))
          else Left(ParseError("adapter", "Could not find XML document in model output"))

  /** The document of `raw`. A reply with more than one top-level tag, as the prompt asks, is the content of one root
    * element (upstream `XMLAdapter.parse` reads `<dspy_root>completion</dspy_root>`).
    */
  private def parseXml(raw: String): Either[DspyError, Elem] =
    Try(XML.loadString(raw))
      .orElse(Try(XML.loadString(s"<${XMLAdapter.RootTag}>$raw</${XMLAdapter.RootTag}>")))
      .toEither.left.map(error => ParseError("adapter", DspyError.messageOf(error)))

  /** A present-but-empty tag is a PRESENT field with an empty value (`Some("")`), not a missing field — treating it as
    * missing used to route single-output signatures into the text fallback, which returned the whole raw XML document
    * as the field's value.
    */
  private def extractFieldText(
      xml      : Elem,
      fieldName: String
  ): Option[String] = (xml \\ fieldName).headOption.map(_.text.trim)

  /** The text of a `str` output value in a tag: `&` and `<` escaped, as upstream `format_field_with_value`. */
  private def escapeXml(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;")

object XMLAdapter:
  /** The root element that holds a reply with more than one top-level tag. */
  private val RootTag: String = "dspy_root"

  /** Fenced ```xml block extractor, compiled once (parse runs per LM completion). */
  private val FencedXml: Regex = "(?s)```xml\\s*(<.*?>.*?</.*?>)\\s*```".r
