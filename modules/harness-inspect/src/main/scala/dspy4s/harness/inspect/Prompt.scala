package dspy4s.harness.inspect

import dspy4s.harness.PolicyAdapter
import dspy4s.harness.host.SessionFile.Row
import zio.blocks.schema.json.Json

import scala.annotation.tailrec

/** A call of the API reference: its name, its signature with no body, and its comment on one line. */
final case class ApiCall(name: String, signature: String, doc: String):

  /** The first sentence of the comment: the text up to the first period with a space after it. */
  def about: String =
    val end = doc.indexOf(". ")
    if end < 0 then doc else doc.take(end + 1)

/** The prompt of a policy request (docs/code-harness/OBSERVABILITY.md, 5.8): the parts of the request that stay the
  * same in the steps of a turn. The memory is a fixed field of the policy too (`PolicyAdapter.Fixed`), but it changes
  * at each turn, so it is not a part of the prompt.
  *
  * @param instructions
  *   the system message up to its first fixed field: the fields of the signature, their format and the objective
  * @param fields
  *   the fixed fields of the session (`Prompt.SessionFields`) with their values, in the order of the request
  * @param options
  *   each member of the body but `messages`, for example `model` and `reasoning_effort`
  * @param url
  *   the URL of the request
  * @param inSystem
  *   true when the fixed fields are at the end of the system message. Else they are in the last user message, and the
  *   endpoint cannot reuse the system message and the steps of the turn from its cache.
  */
final case class Prompt(
    instructions: String,
    fields      : Vector[(String, String)],
    options     : Vector[(String, Json)],
    url         : String,
    inSystem    : Boolean
):

  def field(name: String): Option[String] = fields.collectFirst { case (n, value) if n == name => value }

  def option(name: String): Option[Json] = options.collectFirst { case (n, value) if n == name => value }

  /** The calls of the API reference. */
  def calls: Vector[ApiCall] = Prompt.calls(field("apiReference").getOrElse(""))

  /** The names of the parts of the prompt: `instructions`, each field, `url`, and each option. */
  def parts: Vector[String] = ("instructions" +: fields.map(_._1)) ++ ("url" +: options.map(_._1))

  /** The text of the part `name`, or none for a part that the prompt does not have. An option has its text from
    * `Prompt.words`.
    */
  def part(name: String): Option[String] =
    if name == "instructions" then Some(instructions)
    else if name == "url" then Some(url)
    else field(name).orElse(option(name).map(Prompt.words))

  /** The names of the parts whose text differs from the text in `before`, in the order of `parts`. A part that only one
    * of the two prompts has also differs.
    */
  def changes(before: Prompt): Vector[String] = (parts ++ before.parts).distinct.filter(name =>
    part(name) != before.part(name)
  )

/** A policy call with a new prompt (5.8): the first policy call of the page, or a call whose prompt differs from the
  * prompt of the policy call before it. `before` is that earlier call with its prompt. `activation` is the last
  * `Activated` of the journal at or before the call.
  */
final case class PromptMark(
    call      : Exchange,
    prompt    : Prompt,
    before    : Option[(Exchange, Prompt)],
    activation: Option[Row]
):

  /** The names of the parts that changed: none for the first prompt. */
  def changes: Vector[String] = before.fold(Vector.empty)((_, earlier) => prompt.changes(earlier))

object Prompt:

  /** The fixed fields of a session: the fixed fields of the policy but `memory`, which changes at each turn. */
  val SessionFields: Vector[String] = PolicyAdapter.Fixed.filterNot(_ == "memory")

  /** A marker line of the chat format, for example `[[ ## task ## ]]`. */
  private val Marker = """\[\[ ## (\w+) ## \]\]""".r

  /** The marker at the end of the format part of the system message. The objective comes after it. */
  private val Completed = "[[ ## completed ## ]]"

  /** The line of the object of the calls in an API reference, for example `@assumeSafe object api:`. */
  private val ApiObject = """\s*(?:@\w+\s+)*object\s+api\s*:\s*""".r

  /** The start of a call: `def` and its name, with an optional modifier before it. */
  private val Def = """(?:(?:inline|transparent|final|override)\s+)*def\s+([A-Za-z_][A-Za-z0-9_]*).*""".r

  /** True for a policy call of the session. A probe runs its own policy, so the calls of a probe do not count. */
  def policy(x: Exchange): Boolean = x.predictor.contains("policy") && x.probe.isEmpty

  /** True for a marker line of the chat format. */
  def isMarker(line: String): Boolean = markerOf(line).isDefined

  /** The prompt of the request of `x`, or none for a body that is not JSON or that has no messages.
    *
    * The format part of the system message names each field with a marker too, so only a marker after the marker
    * `completed` can start a fixed field. When the system message has no fixed field after `completed`, the fixed
    * fields come from the last user message, and the instructions are the whole system message.
    */
  def of(x: Exchange): Option[Prompt] =
    Json.parse(x.body).toOption.flatMap { body =>
      val messages = Exchanges.field(body, "messages").toVector.flatMap(_.elements.toVector)
      Option.when(messages.nonEmpty) {
        def content(role: String, all: Vector[Json]): String =
          all.find(m => Exchanges.text(m, "role").contains(role)).fold("")(Chat.contentOf(_).mkString("\n"))
        val system  = content("system", messages)
        val options = body.fields.toVector.filterNot((name, _) => name == "messages")
        val lines   = system.linesIterator.toVector
        val done    = lines.indexOf(Completed)
        val start   =
          if done < 0 then -1
          else lines.indexWhere(line => markerOf(line).exists(PolicyAdapter.Fixed.contains), done + 1)
        if start >= 0 then
          Prompt(trim(lines.take(start)), ofSession(sectionsOf(lines.drop(start))), options, x.url, inSystem = true)
        else
          val user = content("user", messages.reverse).linesIterator.toVector
          Prompt(trim(lines), ofSession(sectionsOf(user)), options, x.url, inSystem = false)
      }
    }

  /** The calls of `calls` with a new prompt (5.8), each with the call before it and its prompt. `calls` are policy
    * calls in their order, and `before` is the policy call right before them. With no `before`, the first call has a
    * new prompt. After it, a call has a new prompt when its prompt differs from the prompt of the call before it. A
    * call with no prompt (`of`) is skipped.
    */
  def starts(
      calls : Vector[Exchange],
      before: Option[Exchange]
  ): Vector[(Exchange, Prompt, Option[(Exchange, Prompt)])] =
    type Start = (Exchange, Prompt, Option[(Exchange, Prompt)])
    val first = before.flatMap(x => of(x).map(x -> _))
    calls.foldLeft((first, Vector.empty[Start])) { case ((last, out), x) =>
      of(x).fold((last, out)) { prompt =>
        val fresh = last.forall((_, earlier) => prompt.changes(earlier).nonEmpty)
        (Some(x -> prompt), if fresh then out :+ ((x, prompt, last)) else out)
      }
    }._2

  /** The calls of the API reference `source` (5.8): each `def` of its `object api`, with its signature up to the `=` of
    * its body and the comment right above it. A source with no `object api` has no calls.
    */
  def calls(source: String): Vector[ApiCall] =
    val lines = source.linesIterator.toVector
    lines.indexWhere(ApiObject.matches) match
      case -1 => Vector.empty
      case at =>
        val outer = indent(lines(at))
        val body  = lines.drop(at + 1).takeWhile(line => line.isBlank || indent(line) > outer)
        val inner = body.find(!_.isBlank).fold(outer)(indent)
        body.indices.toVector.flatMap { i =>
          val line = body(i)
          if line.isBlank || indent(line) != inner then None
          else
            line.trim match
              case Def(name) =>
                val decl = declaration(body, i, inner)
                Some(ApiCall(name, decl.take(bodyStart(decl)).replaceAll("\\s+", " ").trim, comment(body.take(i))))
              case _ => None
        }

  /** The declaration that starts at `lines(i)`, on one line: that line, and each next line that is not blank and has a
    * deeper indent than `inner` or comes while a bracket is open.
    */
  private def declaration(lines: Vector[String], i: Int, inner: Int): String =
    @tailrec def grow(j: Int, text: String): String =
      lines.lift(j) match
        case Some(line) if !line.isBlank && (indent(line) > inner || outside(text)._2 > 0) =>
          grow(j + 1, s"$text ${line.trim}")
        case _ => text
    grow(i + 1, lines(i).trim)

  /** The text of an option: a string as it is, and each other value as JSON. */
  def words(json: Json): String =
    json match
      case Json.String(value) => value
      case other              => other.print

  /** The name of the marker on `line`, or none for a line that is no marker. */
  private[inspect] def markerOf(line: String): Option[String] =
    line match
      case Marker(name) => Some(name)
      case _            => None

  /** The sections of `lines`: each marker line with its name, and the lines up to the next marker line. The lines
    * before the first marker are in no section.
    */
  private[inspect] def sectionsOf(lines: Vector[String]): Vector[(String, String)] =
    val starts = lines.indices.filter(i => markerOf(lines(i)).isDefined).toVector
    starts.zip(starts.drop(1) :+ lines.size).flatMap((a, b) => markerOf(lines(a)).map(_ -> trim(lines.slice(a + 1, b))))

  /** The first section of each fixed field of the session in `found`, in the order of `found`. */
  private def ofSession(found: Vector[(String, String)]): Vector[(String, String)] =
    found.filter((name, _) => SessionFields.contains(name)).distinctBy(_._1)

  /** `lines` with no blank lines at the start and at the end, as one text. */
  private def trim(lines: Vector[String]): String =
    lines.dropWhile(_.isBlank).reverse.dropWhile(_.isBlank).reverse.mkString("\n")

  private def indent(line: String): Int = line.takeWhile(_ == ' ').length

  /** The index of the `=` before the body of the declaration `decl`, or its length when it has no body. The `=` is
    * outside each bracket and each string, and it is not a part of `=>`, `==`, `<=`, `>=` or `!=`.
    */
  private def bodyStart(decl: String): Int =
    outside(decl)._1.collectFirst {
      case (i, 0)
          if decl(i) == '=' && !decl.lift(i + 1).exists("=>".contains(_)) &&
            !decl.lift(i - 1).exists("<>!=".contains(_)) => i
    }.getOrElse(decl.length)

  /** Each character of `text` outside the string literals, with its index and the depth of the brackets before it, and
    * the depth of the brackets at the end of the text.
    */
  private def outside(text: String): (Vector[(Int, Int)], Int) =
    @tailrec def scan(i: Int, depth: Int, quoted: Boolean, out: Vector[(Int, Int)]): (Vector[(Int, Int)], Int) =
      if i >= text.length then (out, depth)
      else
        val c = text(i)
        if quoted then scan(if c == '\\' then i + 2 else i + 1, depth, c != '"', out)
        else
          val next = c match
            case '(' | '[' | '{' => depth + 1
            case ')' | ']' | '}' => depth - 1
            case _               => depth
          scan(i + 1, next, c == '"', out :+ (i -> depth))
    scan(0, 0, quoted = false, Vector.empty)

  /** The comment that ends on the last line of `above`, on one line with no comment marks, or the empty text. */
  private def comment(above: Vector[String]): String =
    val lines = above.reverse.takeWhile(!_.isBlank)
    if !lines.headOption.exists(_.trim.endsWith("*/")) then ""
    else
      lines.indexWhere(_.trim.startsWith("/**")) match
        case -1   => ""
        case open => lines.take(open + 1).reverse
            .map(_.trim.stripPrefix("/**").trim.stripSuffix("*/").trim.stripPrefix("*").trim)
            .filter(_.nonEmpty).mkString(" ")
