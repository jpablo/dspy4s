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
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.PythonText
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.lm.contracts.LmOutput
import zio.blocks.schema.DynamicValue

import scala.util.Try
import scala.xml.Elem
import scala.xml.Node
import scala.xml.XML

/** The XML adapter (upstream `XMLAdapter` in `dspy/adapters/xml_adapter.py`). Each field is a tag:
  * `<name>\nvalue\n</name>`. The system message has the form of the chat adapter.
  *
  * Decision A6: the prompt asks for the value of a list, dict, or model output as JSON text inside its tag, and the
  * parser reads that text as JSON. Upstream asks for nested XML elements. The structure and the output reminder thus
  * have the form that upstream uses for each other output type.
  */
final case class XMLAdapter(
    allowTextFallbackForSingleOutput: Boolean = true,
    /** Try a [[JSONAdapter]] after a parse error, as [[ChatAdapter.useJsonAdapterFallback]]. On by default: upstream
      * `XMLAdapter` extends `ChatAdapter`, so a parse error of the XML gives the JSON fallback. See
      * [[Adapter.fallback]].
      */
    useJsonAdapterFallback: Boolean = true
) extends Adapter:

  override def name: String = "xml"

  /** A [[JSONAdapter]] when [[useJsonAdapterFallback]] is on. This adapter has no native function calling, so the
    * fallback adapter has none too.
    */
  override def fallback: Option[Adapter] =
    Option.when(useJsonAdapterFallback)(JSONAdapter())

  // This adapter has no native function calling: a turn of the history with tool calls stays text, and the request
  // has no tool options (upstream `Adapter._call_preprocess`).
  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    Right(ConversationHistory.prompt(invocation, NativeTools.Off, Turns))

  override def streamingState(layout: SignatureLayout)(using RuntimeContext): Option[AdapterStreamingState] =
    Some(new XmlStreamingState(layout.outputFields))

  /** The tag form of the input fields, with no output reminder (upstream `XMLAdapter.format_user_message_content`). */
  override def formatUserMessageContent(layout: SignatureLayout, values: DynamicValue.Record): String =
    Turns.user(layout, values, mainRequest = false)

  /** The values of the output fields, with the tool calls rule of [[AdapterTextSupport.parseOutputs]]. This adapter has
    * no native function calling, but a reply with provider tool calls is a tool turn, as in the other formats.
    */
  override def parse(invocation: AdapterInvocation, output: LmOutput)(using
      RuntimeContext
  ): Either[DspyError, ParsedOutput] =
    AdapterTextSupport.parseOutputs(invocation.layout, output, nativeCalls = false)(parseText(_, output))

  /** The values of the text output fields `fields` from the XML document of the text of `output`.
    *
    * The single-output text fallback ([[AdapterTextSupport.singleOutputTextFallback]]) applies only when the reply has
    * no XML document at all ([[extractXml]]): the model did not use the format. A reply with an XML document that does
    * not parse, for example `<answer>AT&T</answer>`, is a parse error (upstream `ET.fromstring` raises), and the
    * predictor then tries the fallback adapter ([[fallback]]).
    */
  private def parseText(fields: Vector[FieldSpec], output: LmOutput): Either[DspyError, DynamicValue.Record] =
    extractXml(output.text) match
      case Some(xml) =>
        parseXml(xml).left.map(error => ParseError("adapter", s"Failed to parse XML: $error", raw = Some(output.text)))
          .flatMap(document => parseFields(fields, document, output))
      case None =>
        if allowTextFallbackForSingleOutput && fields.size == 1 then
          AdapterTextSupport.singleOutputTextFallback(fields.head, output)
        else Left(ParseError("adapter", "Could not find XML document in model output", raw = Some(output.text)))

  /** The values of the output fields `fields` of `document` ([[fieldTexts]]). A text `tool_calls` field takes the JSON
    * list of calls in its text, as in the chat and JSON formats (upstream `parse_value` with `ToolCalls`).
    */
  private def parseFields(
      fields  : Vector[FieldSpec],
      document: Seq[Node],
      output  : LmOutput
  ): Either[DspyError, DynamicValue.Record] =
    val texts = XMLAdapter.fieldTexts(document, fields.map(_.name).toSet)
    AdapterTextSupport.decodeOutputFields(fields, output.text) { field =>
      texts.get(field.name) match
        case Some(raw) => AdapterTextSupport.outputTextValue(field, raw).map(Some(_))
        case None      => Right(None)
    }

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

  /** The XML document of `text`, or `None` when the text has no XML document at all: the whole text when it starts with
    * `<` after Python whitespace, else the first ```xml block ([[XMLAdapter.fencedXml]]), else the text from the first
    * `<` to the last `>`.
    */
  private def extractXml(text: String): Option[String] =
    val stripped = PythonText.strip(text)
    if stripped.startsWith("<") then Some(stripped)
    else
      XMLAdapter.fencedXml(text).orElse {
        val first = text.indexOf('<')
        val last  = text.lastIndexOf('>')
        Option.when(first >= 0 && last > first)(text.substring(first, last + 1))
      }

  /** The top nodes of the document of `raw`, or the message of the XML syntax error. A reply with more than one
    * top-level tag, as the prompt asks, is the content of one root element (upstream `XMLAdapter.parse` reads
    * `<dspy_root>completion</dspy_root>`). The parse tries that form first, and the nodes are the content of the root.
    * A reply that this form does not take, for example a reply with a leading `<?xml ?>` declaration, is one document,
    * and the node is its root element.
    */
  private def parseXml(raw: String): Either[String, Seq[Node]] =
    def wrapped = Try(XML.loadString(s"<${XMLAdapter.RootTag}>$raw</${XMLAdapter.RootTag}>")).map(_.child.toSeq)
    def plain   = Try(XML.loadString(raw)).map(Seq(_))
    wrapped.orElse(plain).toEither.left.map(DspyError.messageOf)

  /** The text of a `str` output value in a tag: `&` and `<` escaped, as upstream `format_field_with_value`. */
  private def escapeXml(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;")

object XMLAdapter:
  /** The root element that holds a reply with more than one top-level tag. */
  private val RootTag: String = "dspy_root"

  /** The text of each output field of `names` in the nodes `nodes`, stripped with Python `str.strip` (upstream
    * `.strip()`). A field is an element of a field name that is not inside another field element: the parser does not
    * read the content of a field element for more fields. An element of another name, for example an `<outputs>`
    * wrapper, holds no value, and the parser reads its content. The first element of a field wins. A present but empty
    * tag is a present field with an empty value, not a missing field.
    *
    * Upstream reads the direct children of the root only (`_group_children`). This rule is the rule of
    * [[XmlStreamingState]], which also reads the fields inside a wrapper, so the parser and the stream agree.
    */
  private def fieldTexts(
      nodes: Seq[Node],
      names: Set[String],
      found: Map[String, String] = Map.empty
  ): Map[String, String] =
    nodes.foldLeft(found) { (texts, node) =>
      node match
        case element: Elem if names.contains(element.label) =>
          if texts.contains(element.label) then texts
          else texts.updated(element.label, PythonText.strip(element.text))
        case element: Elem => fieldTexts(element.child.toSeq, names, texts)
        case _             => texts
    }

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
