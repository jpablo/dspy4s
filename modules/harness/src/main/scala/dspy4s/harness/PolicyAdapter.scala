package dspy4s.harness

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, DelegatingAdapter, FormattedPrompt, ParsedOutput}
import dspy4s.core.contracts.{DspyError, DynamicValues, ParseError, RuntimeContext, SignatureLayout}
import dspy4s.lm.contracts.{LmOutput, MessageRole}
import dspy4s.programs.runtime.SettingsProgramRuntime
import zio.blocks.schema.DynamicValue

/** The adapter of a policy call (CODE_HARNESS_DESIGN.md, 9): the adapter `delegate`, with one more rule for a reply
  * that does not follow the field format. When the parse of `delegate` gives a `ParseError`, the rule reads the reply
  * as a step (`PolicyAdapter.values`). When the rule fits, its values are the result, so the call needs no fallback and
  * no second model call. Else the error of `delegate` stays. The fallback of `delegate` gets the same rule
  * (`DelegatingAdapter.wrap`). Each other member comes from `delegate`.
  *
  * The format of `delegate` gets one change: the fixed fields (`PolicyAdapter.Fixed`) go to the end of the system
  * message, so the endpoint can reuse the start of each request of a turn from its cache (`PolicyAdapter.moved`).
  *
  * The rule gives the fields of `PolicyStep`. After the parse, the call fills the reasoning field as for each reply:
  * with the native reasoning of the reply, else with the empty text. The adapters of DSPy have no such rule, so it is
  * in the harness, and only the policy call has it (`HarnessEffects.callPolicy`).
  */
private[harness] final case class PolicyAdapter(delegate: Adapter) extends DelegatingAdapter:

  override protected def wrap(next: Adapter): Adapter = PolicyAdapter(next)

  /** The prompt of `delegate`, with the fixed fields at the end of the system message (`PolicyAdapter.moved`). */
  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    delegate.format(invocation).map { prompt =>
      val values = PolicyAdapter.Fixed.flatMap(name => invocation.inputs.get(name).map(name -> _))
      val shown  = values.filterNot((_, value) => DynamicValues.asString(value).contains(""))
      PolicyAdapter.moved(prompt, marked(invocation, values), marked(invocation, shown))
    }

  /** The marker form of the input fields `fields` of `invocation`, or an empty text for no field. */
  private def marked(invocation: AdapterInvocation, fields: Vector[(String, DynamicValue)]): String =
    if fields.isEmpty then ""
    else delegate.formatUserMessageContent(invocation.layout, DynamicValues.recordFromEntries(fields))

  override def parse(invocation: AdapterInvocation, output: LmOutput)(using
      RuntimeContext
  ): Either[DspyError, ParsedOutput] =
    delegate.parse(invocation, output) match
      case Left(error: ParseError) =>
        PolicyAdapter.values(invocation.layout, output.text).map(ParsedOutput(_)).toRight(error)
      case result => result

private[harness] object PolicyAdapter:

  /** The input fields that stay the same at each step: `task` and `apiReference` in a session, and `memory` in a turn.
    * They come one time, at the end of the system message (`moved`).
    */
  val Fixed: Vector[String] = Vector("task", "apiReference", "memory")

  /** The text before the fixed fields in the system message. */
  val FixedHeader: String =
    "These fields come here one time, and not in each message: task and apiReference stay the same in this session, " +
      "and memory stays the same in this turn."

  /** `prompt` with the fixed fields at the end of its system message (CODE_HARNESS_DESIGN.md, 10.6). `prefix` is the
    * marker form of the fixed fields of the request, and `shown` the marker form of the fixed fields that are not
    * empty. When the last user message starts with `prefix` and a blank line, that start goes away, and `shown` goes at
    * the end of the system message after `FixedHeader`. So the system message and the steps of the turn are the same
    * start of each request of a turn, and the endpoint can reuse them from its cache. The history of the turn stays
    * between them and the last message. Each other prompt stays as it is.
    *
    * A measured request (2026-11-12) had the fixed fields in the last user message, after the history. So each later
    * step reused only 256 of about 3,200 tokens from the cache.
    */
  def moved(prompt: FormattedPrompt, prefix: String, shown: String): FormattedPrompt =
    val messages = prompt.messages
    val first    = messages.indexWhere(_.role == MessageRole.System)
    val last     = messages.lastIndexWhere(_.role == MessageRole.User)
    val changed  =
      for
        _      <- Option.when(prefix.nonEmpty && first >= 0 && first < last)(())
        text   <- messages(last).text.filter(_.startsWith(prefix + "\n\n"))
        system <- messages(first).text
      yield
        val head = if shown.isEmpty then system else s"$system\n\n$FixedHeader\n\n$shown"
        messages
          .updated(first, messages(first).copy(text = Some(head)))
          .updated(last, messages(last).copy(text = Some(text.drop(prefix.length + 2))))
    changed.fold(prompt)(updated => prompt.copy(messages = updated))

  /** `context` with its adapter in a `PolicyAdapter`. The adapter is the adapter of `context`, else a chat adapter, as
    * a predictor gets it (`SettingsProgramRuntime`).
    */
  def around(context: RuntimeContext): RuntimeContext =
    val adapter = PolicyAdapter(SettingsProgramRuntime.adapterOrChat(context))
    context.withServices(context.services.copy(adapter = Some(adapter)))

  /** The values of a reply that does not follow the field format, or none when no rule fits. The marker
    * `[[ ## completed ## ]]` ends the reply, as in the chat format. The rules:
    *
    *   - A reply with a field marker for `code` and none for `text`: the text is the reply before the first marker.
    *     Each other field of `layout` takes its section, as in the chat format.
    *   - A reply with no field markers and exactly one ```` ```scala ```` fence: the text is the reply with no such
    *     fence, and the code is the code in the fence. A fence has ``` and an optional word for the language on its
    *     first line, and ``` on its last line (`blocks`).
    *   - A reply with no field markers and no ```` ```scala ```` fence: the whole reply is the text, with no code. A
    *     fence of another language, or with no language, is a part of the text, for example an example of a shell
    *     command in a reply. An empty text fits no rule.
    *
    * Each other reply fits no rule: a reply with two ```` ```scala ```` fences, because the harness cannot know which
    * fence is the cell, and a reply with a fence that does not end. Each text has no white space at its two ends. The
    * live session of 2026-11-11 gave these rules: at the first step, 25 of 30 replies of a model with native reasoning
    * had no text field.
    */
  def values(layout: SignatureLayout, reply: String): Option[DynamicValue.Record] =
    val lines = reply.linesIterator.toVector.takeWhile(line => !marker(line).exists(_._1 == EndField))
    val first = lines.indexWhere(line => marker(line).isDefined)
    if first >= 0 then
      val found = sections(lines.drop(first))
      Option.when(found.contains(CodeField) && !found.contains(TextField))(
        record(layout, joined(lines.take(first)), found)
      )
    else
      blocks(lines).flatMap(_.filter(_.language == CellLanguage) match
        case Vector() =>
          val text = joined(lines)
          Option.when(text.nonEmpty)(record(layout, text, Map.empty))
        case Vector(cell) =>
          val code = lines.slice(cell.open + 1, cell.close).mkString("\n")
          val text = Vector(joined(lines.take(cell.open)), joined(lines.drop(cell.close + 1))).filter(_.nonEmpty)
          Some(record(layout, text.mkString("\n\n"), Map(CodeField -> code)))
        case _ => None)

  /** The text of the step of a reply so far (CLI_SCREEN.md, 13), with the rules of `values` and of the chat format
    * (`Draft.text`). The reply ends at the marker of the end, and a last line with no line end that can still become a
    * marker or a fence waits (`settled`). With a marker, the text is the section of `text`, else the reply before the
    * first marker. With no marker, the text is the reply with no `scala` fence (`outside`).
    */
  def textSoFar(reply: String): String =
    val lines = settled(reply)
    val first = lines.indexWhere(line => marker(line).isDefined)
    if first >= 0 then sections(lines.drop(first)).getOrElse(TextField, joined(lines.take(first)))
    else outside(lines)

  /** The section of `reasoning` of a reply so far, or the empty text when the reply has no such section. */
  def reasoningSoFar(reply: String): String =
    val lines = settled(reply)
    val first = lines.indexWhere(line => marker(line).isDefined)
    if first < 0 then "" else sections(lines.drop(first)).getOrElse(ReasoningField, "")

  /** The lines of a reply so far, up to the marker of the end. A last line with no line end waits when its first
    * character that is not white space is `[` or a backquote, because it can still become a marker or a fence.
    */
  private def settled(reply: String): Vector[String] =
    val lines = reply.linesIterator.toVector
    val open  = !(reply.endsWith("\n") || reply.endsWith("\r"))
    val ready =
      if open && lines.lastOption.exists(_.strip.headOption.exists(c => c == '[' || c == '`')) then lines.init
      else lines
    ready.takeWhile(line => !marker(line).exists(_._1 == EndField))

  /** The text of `lines` with no marker: each part outside a `scala` fence, with an empty line between two parts. A
    * fence of another language is a part of the text. A `scala` fence that does not end hides the rest. A fence starts
    * and ends as in `blocks`. For a reply with one `scala` fence, this is the text of `values`.
    */
  private def outside(lines: Vector[String]): String =
    val (parts, _) = lines.foldLeft((Vector(Vector.empty[String]), Option.empty[String])) {
      case ((parts, None), line) if line.strip.startsWith(Fence) =>
        val language = line.strip.drop(Fence.length).strip.takeWhile(!_.isWhitespace)
        if language == CellLanguage then (parts :+ Vector.empty, Some(language))
        else (parts.init :+ (parts.last :+ line), Some(language))
      case ((parts, None), line)           => (parts.init :+ (parts.last :+ line), None)
      case ((parts, Some(language)), line) =>
        val kept = if language == CellLanguage then parts else parts.init :+ (parts.last :+ line)
        (kept, Option.when(line.strip != Fence)(language))
    }
    parts.map(joined).filter(_.nonEmpty).mkString("\n\n")

  private val TextField      = "text"
  private val CodeField      = "code"
  private val ReasoningField = "reasoning"

  /** The name of the marker that ends a reply in the chat format. */
  private val EndField = "completed"

  /** A marker of the chat format at the start of a line: `[[ ## name ## ]]`. The group is the name. */
  private val Marker = """\[\[ ## (\w+) ## \]\]""".r

  private val Fence = "```"

  /** The language of the one fence that is a cell. */
  private val CellLanguage = "scala"

  /** A fence of a reply: the index of its first line and of its last line, and the word for its language, or the empty
    * text.
    */
  private final case class Block(open: Int, close: Int, language: String)

  /** The fences of `lines`, in their order, or none when a fence does not end. Outside a fence, a line that starts with
    * ``` starts a fence, and the first word after the ``` is its language. In a fence, only a line of ``` ends it, so a
    * line with ``` and a word is a part of the fence, as in Markdown.
    */
  private def blocks(lines: Vector[String]): Option[Vector[Block]] =
    val (done, open) = lines.indices.foldLeft((Vector.empty[Block], Option.empty[(Int, String)])) {
      case ((done, None), i) if lines(i).strip.startsWith(Fence) =>
        (done, Some(i -> lines(i).strip.drop(Fence.length).strip.takeWhile(!_.isWhitespace)))
      case ((done, Some((start, language))), i) if lines(i).strip == Fence => (done :+ Block(start, i, language), None)
      case (state, _)                                                      => state
    }
    Option.when(open.isEmpty)(done)

  /** The name of the marker at the start of `line`, and the rest of the line, or none. */
  private def marker(line: String): Option[(String, String)] =
    val stripped = line.strip
    Marker.findPrefixMatchOf(stripped).map(m => (m.group(1), stripped.substring(m.end).strip))

  /** The text of each section of `lines`, by the name of its marker. The first line of `lines` is a marker. A section
    * has the rest of the line of its marker and the lines up to the next marker. The first section of a name wins, as
    * in the chat format.
    */
  private def sections(lines: Vector[String]): Map[String, String] =
    val parts = lines.foldLeft(Vector.empty[(String, Vector[String])]) { (done, line) =>
      marker(line) match
        case Some((name, rest)) => done :+ (name -> Vector(rest))
        case None               => done.lastOption.fold(done)((name, body) => done.init :+ (name -> (body :+ line)))
    }
    parts.distinctBy(_._1).map((name, body) => name -> joined(body)).toMap

  private def joined(lines: Vector[String]): String = lines.mkString("\n").strip

  /** The values of the output fields of `layout`: `text`, and the text of `found` for each other field. An optional
    * field with no text is null, as in the chat format. A field with no text that is not optional has no value, so the
    * call fills a reasoning field.
    */
  private def record(layout: SignatureLayout, text: String, found: Map[String, String]): DynamicValue.Record =
    DynamicValues.recordFromEntries(layout.outputFields.flatMap { field =>
      if field.name == TextField then Some(field.name -> DynamicValues.string(text))
      else
        found.get(field.name) match
          case Some(value)            => Some(field.name -> DynamicValues.string(value))
          case None if field.optional => Some(field.name -> DynamicValue.Null)
          case None                   => None
    })
