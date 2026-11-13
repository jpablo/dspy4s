package dspy4s.harness.inspect

import dspy4s.core.contracts.{ClosableIterator, DspyError, LmTransportError, LmUsage, RuntimeContext}
import dspy4s.lm.contracts.{LmChunk, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.lm.providers.{HttpResponse, HttpStreamResponse, HttpTransport, OpenAiClient, OpenAiLanguageModel}
import dspy4s.lm.runtime.StreamAccumulator
import zio.blocks.schema.json.Json

import Exchanges.{field, text}
import Html.escape

/** What the model reads from the reply of an exchange (docs/code-harness/OBSERVABILITY.md, 5.4): the output, the usage,
  * the finish reason, or the error.
  */
final case class Reading(
    output: Option[LmOutput],
    usage : Option[LmUsage],
    finish: Option[String],
    error : Option[String]
)

/** The chat view of an exchange (5.4): the request as messages, and the reply as the model reads it. */
object Chat:

  /** The text of a request for the diff: for each message, a line with its role, then the lines of its content and of
    * its tool calls. A body with no messages gives its JSON with indents, and a body that is not JSON gives its lines.
    */
  def requestLines(body: String): Vector[String] =
    Json.parse(body) match
      case Right(json) => field(json, "messages") match
          case Some(messages: Json.Array) => messages.elements.toVector.flatMap(messageLines)
          case _                          => JsonText.pretty(json).linesIterator.toVector
      case Left(_) => body.linesIterator.toVector

  /** The reply of `x` as the model reads it. The reply goes again through `OpenAiLanguageModel` on a transport that
    * gives the recorded status, headers and body or lines. So the view and the model read a reply on one path: the same
    * parser of the stream, the same assembly of the chunks and of the tool calls, and the same usage.
    */
  def read(x: Exchange): Reading =
    if x.open then Reading(None, None, None, Some("The exchange has no reply yet."))
    else if x.status.isEmpty then Reading(None, None, None, Some(x.error.getOrElse("The exchange has no status.")))
    else
      given RuntimeContext = RuntimeContext()
      val mode             = modeOf(x.url)
      val client           = OpenAiClient(apiKey = "", baseUrl = "http://replay.invalid", transport = Replay(x))
      val model            = OpenAiLanguageModel(model = "inspector", mode = mode, client = client)
      val request          = LmRequest(model = "inspector")
      if x.stream then streamed(mode, model.stream(request))
      else
        model.call(request) match
          case Right(response) => Reading(response.outputs.headOption, response.usage, finishOf(x), None)
          case Left(error)     => Reading(None, None, None, Some(words(error)))

  /** The chat view: the options, the tools and the messages of the request, then the reply as the model reads it. */
  def html(x: Exchange): String =
    val request  = Json.parse(x.body).toOption
    val messages = request.flatMap(field(_, "messages")).toVector.flatMap(_.elements.toVector)
    val tools    = request.flatMap(field(_, "tools")).toVector.flatMap(_.elements.toVector)
    val options  = request.toVector.flatMap(_.fields.toVector).filterNot((k, _) => k == "messages" || k == "tools")
    val head     =
      if options.isEmpty then ""
      else
        options.map((k, v) => s"<dt>${escape(k)}</dt><dd><code>${escape(compact(v))}</code></dd>")
          .mkString("<dl>", "", "</dl>")
    val toolList =
      if tools.isEmpty then ""
      else
        s"<details><summary>${tools.size} tools</summary><ul>" + tools.map { tool =>
          val function = field(tool, "function").getOrElse(tool)
          val name     = text(function, "name").getOrElse("?")
          val about    = text(function, "description").fold("")(d => s" · ${escape(d)}")
          s"<li><code>${escape(name)}</code>$about</li>"
        }.mkString + "</ul></details>"
    val shown =
      if request.isEmpty then s"<pre>${escape(x.body)}</pre>"
      else if messages.isEmpty then s"<pre>${escape(requestLines(x.body).mkString("\n"))}</pre>"
      else messages.map(message).mkString
    "<h2>Request</h2>" + head + toolList + shown + "<h2>Reply</h2>" + replyHtml(read(x))

  private def replyHtml(reading: Reading): String =
    val error  = reading.error.fold("")(e => s"""<p class="error">${escape(e)}</p>""")
    val output = reading.output.fold("") { o =>
      val reasoning =
        if o.reasoning.trim.isEmpty then ""
        else s"<details><summary>Reasoning</summary><pre>${escape(o.reasoning)}</pre></details>"
      val calls =
        if o.toolCalls.isEmpty then ""
        else
          o.toolCalls.map(c =>
            s"<li><code>${escape(c.name)}</code> <code>${escape(ScalaText.line(c.args))}</code></li>"
          )
            .mkString("<p>Tool calls:</p><ul>", "", "</ul>")
      reasoning + s"""<div class="msg role-assistant"><div class="text">${escape(o.text)}</div></div>""" + calls
    }
    val facts = Vector(
      reading.finish.map(f => "finish" -> f),
      reading.usage.map(u => "tokens" -> s"${u.promptTokens} → ${u.completionTokens} (${u.totalTokens} in all)")
    ).flatten
    val dl =
      if facts.isEmpty then ""
      else facts.map((k, v) => s"<dt>$k</dt><dd>${escape(v)}</dd>").mkString("<dl>", "", "</dl>")
    error + output + dl

  /** One message of a request: its role, and its content and tool calls. A system message is folded, because the
    * instructions of the policy are long.
    */
  private def message(json: Json): String =
    val role  = text(json, "role").getOrElse("?")
    val lines = contentOf(json) ++ toolCallsOf(json).map(c => s"→ $c")
    val all   = lines.mkString("\n")
    val body  = escape(all)
    if role == "system" then
      s"""<details class="msg role-system"><summary>system · ${all.linesIterator.size} lines</summary>""" +
        s"""<div class="text">$body</div></details>"""
    else
      s"""<div class="msg role-${escape(role)}"><div class="role">${escape(role)}</div>""" +
        s"""<div class="text">$body</div></div>"""

  private def messageLines(json: Json): Vector[String] =
    val role = text(json, "role").getOrElse("?")
    Vector(s"── $role ──") ++ contentOf(json).flatMap(_.linesIterator) ++ toolCallsOf(json).map(c => s"→ $c")

  private def contentOf(json: Json): Vector[String] =
    field(json, "content") match
      case Some(Json.String(value)) => Vector(value)
      case Some(parts: Json.Array)  =>
        parts.elements.toVector.map(part => text(part, "text").getOrElse(s"[${text(part, "type").getOrElse("part")}]"))
      case _ => Vector.empty

  private def toolCallsOf(json: Json): Vector[String] =
    field(json, "tool_calls").toVector.flatMap(_.elements.toVector).map { call =>
      val function = field(call, "function").getOrElse(call)
      s"${text(function, "name").getOrElse("?")}(${text(function, "arguments").getOrElse("")})"
    }

  private def compact(json: Json): String =
    json match
      case Json.String(value) => value
      case other              => other.print

  private def modeOf(url: String): LmMode =
    if url.endsWith("/responses") then LmMode.Responses
    else if url.endsWith("/completions") && !url.endsWith("/chat/completions") then LmMode.Text
    else LmMode.Chat

  /** The finish reason of the first choice of a plain reply. */
  private def finishOf(x: Exchange): Option[String] =
    x.replied.headOption.flatMap(Json.parse(_).toOption).flatMap(field(_, "choices"))
      .flatMap(_.elements.headOption).flatMap(text(_, "finish_reason"))

  /** The chunks of a stream, assembled as the predictor assembles them. */
  private def streamed(mode: LmMode, chunks: Iterator[LmChunk]): Reading =
    val parts                      = StreamAccumulator(mode)
    var usage: Option[LmUsage]     = None
    var finish: Option[String]     = None
    var error: Option[String]      = None
    var cached: Option[LmResponse] = None
    chunks.foreach { chunk =>
      chunk.cachedResponse match
        case Some(response) => cached = Some(response)
        case None           => chunk.reportedFailure match
            case Some(failure) => error = Some(words(failure))
            case None          =>
              parts.push(chunk)
              chunk.usage.foreach(found => usage = Some(found))
              chunk.finishReason.foreach(reason => finish = Some(reason))
    }
    cached.fold(Reading(Some(parts.output), usage, finish, error))(r =>
      Reading(r.outputs.headOption, r.usage, finish, error)
    )

  private def words(error: DspyError): String = s"${error.code}: ${error.message}"

  /** A transport that gives the recorded reply of `x` again. */
  private final class Replay(x: Exchange) extends HttpTransport:
    private def status: Int = x.status.fold(0)(_.toInt)

    override def sendJson(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpResponse] =
      Right(HttpResponse(status, x.replyHeaders, x.replied.mkString("\n")))

    override def streamSse(url: String, headers: Map[String, String], body: String)
        : Either[DspyError, HttpStreamResponse] =
      val failure = () =>
        Option.when(x.end.contains("failed"))(LmTransportError(x.error.getOrElse("The stream failed.")))
      Right(HttpStreamResponse(status, x.replyHeaders, Lines(x.replied), failure))

  private final class Lines(items: Vector[String]) extends ClosableIterator[String]:
    private var at                = 0
    override def hasNext: Boolean = at < items.size
    override def next(): String   =
      val item = items(at)
      at += 1
      item
    override def close(): Unit = at = items.size
