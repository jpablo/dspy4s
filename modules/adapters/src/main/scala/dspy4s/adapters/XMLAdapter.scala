package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.NativeTools
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.adapters.internal.AdapterTextSupport
import dspy4s.adapters.internal.ConversationHistory
import dspy4s.adapters.internal.FramedTurns
import dspy4s.adapters.internal.PromptText
import dspy4s.adapters.internal.PromptText.OutputSchema
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.lm.contracts.LmOutput
import zio.blocks.schema.DynamicValue

import scala.util.Try
import scala.xml.NodeSeq
import scala.xml.XML

/** The XML adapter (upstream `XMLAdapter` in `dspy/adapters/xml_adapter.py`). Each field is a tag:
  * `<name>\nvalue\n</name>`. The system message has the form of the chat adapter.
  *
  * Decision A6: the prompt asks for the value of a list, dict, or model output as JSON text inside its tag, and the
  * parser reads that text as JSON. Upstream asks for nested XML elements. The structure and the output reminder thus
  * have the form that upstream uses for each other output type.
  */
final case class XMLAdapter(allowTextFallbackForSingleOutput: Boolean = true) extends Adapter:

  override def name: String = "xml"

  // This adapter has no native function calling: a turn of the history with tool calls stays text, and the request
  // has no tool options (upstream `Adapter._call_preprocess`).
  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    Right(ConversationHistory.prompt(invocation, NativeTools.Off, Turns))

  override def streamingState(layout: SignatureLayout)(using RuntimeContext): Option[AdapterStreamingState] =
    Some(new XmlStreamingState(layout.outputFields))

  /** The tag form of the input fields, with no output reminder (upstream `XMLAdapter.format_user_message_content`). */
  override def formatUserMessageContent(layout: SignatureLayout, values: DynamicValue.Record): String =
    Turns.user(layout, values, mainRequest = false)

  override def parse(invocation: AdapterInvocation, output: LmOutput)(using
      RuntimeContext
  ): Either[DspyError, ParsedOutput] =
    val layout = invocation.layout
    // The plain-text fallback only applies when the reply is not an XML document at all (the model ignored the
    // format). Falling back on ANY structured failure would mask real errors: a present-but-unfillable field
    // used to return the ENTIRE raw XML document as the field's "value".
    extractXml(output.text).flatMap(parseXml(_, layout)) match
      case Right(document) => parseFields(layout, document, output)
      case Left(_)         =>
        if allowTextFallbackForSingleOutput && layout.outputFields.size == 1 then
          AdapterTextSupport.singleOutputTextFallback(layout, output)
        else Left(ParseError("adapter", "XML parse failed and no fallback was applied", raw = Some(output.text)))

  /** The values of the output fields of `document`. A text `tool_calls` field takes the JSON list of calls in its text,
    * as in the chat and JSON formats (upstream `parse_value` with `ToolCalls`).
    */
  private def parseFields(
      layout  : SignatureLayout,
      document: NodeSeq,
      output  : LmOutput
  ): Either[DspyError, ParsedOutput] =
    AdapterTextSupport.decodeOutputFields(layout, output.text) { field =>
      extractFieldText(document, field.name) match
        case Some(raw) => AdapterTextSupport.outputTextValue(field, raw).map(Some(_))
        case None      => Right(None)
    }.map(entries => ParsedOutput(DynamicValues.recordFromEntries(entries)))

  /** The text of a turn: the tag form of the input fields for a user turn, with the output reminder in a current
    * request, and the tag form of the output fields for an assistant turn (upstream
    * `XMLAdapter.format_user_message_content` and `format_assistant_message_content`).
    */
  private object Turns extends FramedTurns:
    override protected def frame(name: String, text: String): String = s"<$name>\n$text\n</$name>"

    override def assistant(layout: SignatureLayout, values: DynamicValue.Record): String =
      framed(layout.outputFields.map { field =>
        val text = outputText(field, values)
        field -> (if PromptText.isStr(field) then escapeXml(text) else text)
      })

    /** The structure of the system message (upstream `XMLAdapter.format_field_structure`). */
    override def structure(layout: SignatureLayout, schema: OutputSchema): String =
      Vector(
        PromptText.StructureHeader,
        placeholders(layout.inputFields, output = false, schema),
        placeholders(layout.outputFields, output = true, schema)
      ).mkString("\n\n")

    /** The output reminder of the user turn (upstream `XMLAdapter.user_message_output_requirements`, with no nested XML
      * structure by decision A6).
      */
    override protected def outputRequirements(layout: SignatureLayout): String =
      "Respond with the corresponding output fields wrapped in XML tags " +
        layout.outputFields.map(field => s"`<${field.name}>`").mkString(", then ") + "."

  private def extractXml(text: String): Either[DspyError, String] =
    val trimmed = text.trim
    if trimmed.startsWith("<") then Right(trimmed)
    else
      XMLAdapter.fencedXml(text) match
        case Some(xml) => Right(xml)
        case None      =>
          val first = text.indexOf('<')
          val last  = text.lastIndexOf('>')
          if first >= 0 && last > first then Right(text.substring(first, last + 1))
          else Left(ParseError("adapter", "Could not find XML document in model output"))

  /** The top nodes of the document of `raw`. A reply with more than one top-level tag, as the prompt asks, is the
    * content of one root element (upstream `XMLAdapter.parse` reads `<dspy_root>completion</dspy_root>`). The parse
    * tries that form first, and the nodes are the content of the root. A reply that this form does not take, for
    * example a reply with a leading `<?xml ?>` declaration, is one document, and the node is its root element.
    *
    * The root element of the wrapped form can match a field ([[XMLAdapter.RootMatches]]). With such a field, the plain
    * form comes first, and the node of the wrapped form is its root element.
    */
  private def parseXml(raw: String, layout: SignatureLayout): Either[DspyError, NodeSeq] =
    def wrapped  = Try(XML.loadString(s"<${XMLAdapter.RootTag}>$raw</${XMLAdapter.RootTag}>"))
    def plain    = Try(XML.loadString(raw))
    val document =
      if layout.outputFields.exists(field => XMLAdapter.RootMatches.contains(field.name)) then plain.orElse(wrapped)
      else wrapped.map(root => NodeSeq.fromSeq(root.child)).orElse(plain)
    document.toEither.left.map(error => ParseError("adapter", DspyError.messageOf(error)))

  /** A present-but-empty tag is a PRESENT field with an empty value (`Some("")`), not a missing field — treating it as
    * missing used to route single-output signatures into the text fallback, which returned the whole raw XML document
    * as the field's value.
    */
  private def extractFieldText(
      document : NodeSeq,
      fieldName: String
  ): Option[String] = (document \\ fieldName).headOption.map(_.text.trim)

  /** The text of a `str` output value in a tag: `&` and `<` escaped, as upstream `format_field_with_value`. */
  private def escapeXml(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;")

object XMLAdapter:
  /** The root element that holds a reply with more than one top-level tag. */
  private val RootTag: String = "dspy_root"

  /** The field names that match the root element of the wrapped form in the search of a field (`\\` of `NodeSeq`): the
    * name of the root, and `_` (each element).
    */
  private val RootMatches: Set[String] = Set(RootTag, "_")

  /** The start of a fenced XML block, and the end of a fenced block. */
  private val XmlFence: String = "```xml"
  private val Fence: String    = "```"

  /** The XML document of the first ```xml block of `text` that has one. The document starts with `<` after the fence
    * and its whitespace. It ends at the first `>` after the first `>` and the first `</` of the document that only
    * whitespace (`\s`) and the end fence come after. This is the match of the pattern
    * `(?s)FENCE\s*(<.*?>.*?</.*?>)\s*END`, but the search reads the text after a fence one time, so a fence with no end
    * gives no backtracking.
    */
  private def fencedXml(text: String): Option[String] =
    Iterator
      .iterate(text.indexOf(XmlFence))(at => text.indexOf(XmlFence, at + 1))
      .takeWhile(_ >= 0)
      .flatMap { fence =>
        val start  = skipSpace(text, fence + XmlFence.length)
        val close  = if text.startsWith("<", start) then text.indexOf('>', start + 1) else -1
        val endTag = if close < 0 then -1 else text.indexOf("</", close + 1)
        if endTag < 0 then None
        else
          Iterator
            .iterate(text.indexOf('>', endTag + 2))(at => text.indexOf('>', at + 1))
            .takeWhile(_ >= 0)
            .find(end => text.startsWith(Fence, skipSpace(text, end + 1)))
            .map(end => text.substring(start, end + 1))
      }
      .nextOption()

  /** The first index at or after `from` that is not a whitespace character of a Java pattern (`\s`). */
  private def skipSpace(text: String, from: Int): Int =
    var index = from
    while index < text.length && JavaPatternSpace.indexOf(text.charAt(index)) >= 0 do index += 1
    index

  /** The characters of `\s` in a Java pattern. */
  private val JavaPatternSpace: String = " \t\n\u000B\f\r"
