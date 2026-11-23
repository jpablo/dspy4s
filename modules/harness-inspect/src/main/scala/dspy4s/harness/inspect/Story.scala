package dspy4s.harness.inspect

import dspy4s.harness.host.SessionFile.Row
import dspy4s.harness.host.{jobName, jobStatusOf, jobStatusWords, modeName}
import dspy4s.harness.kernel.*

import zio.blocks.schema.json.Json

import java.util.Locale
import scala.collection.mutable.ArrayBuffer

import Html.escape

/** A part of a step or of the start of a turn (docs/code-harness/OBSERVABILITY.md, 5.7), in the order of the journal.
  */
enum Part:

  /** The policy calls of a step, and the `Acted` of the last one. */
  case Model(calls: Vector[Exchange], acted: Option[Row])

  /** The code of an `Acted`. */
  case Cell(acted: Row)

  /** An API call: its `EffectStarted`, and its `EffectDone` when it came. */
  case Call(started: Row, done: Option[Row])

  /** The `Observed` of a cell. */
  case Output(row: Row)

  /** Summary calls of the memory that come one after another. */
  case Memory(calls: Vector[Exchange])

  /** Another model call, for example of `llmQuery` or of the finisher. */
  case Llm(call: Exchange)

  /** Each other entry, for example `StopRequested`. A `DerivedRead` has no row in the views. */
  case Note(row: Row)

  /** The rows of the journal in this part. An `Acted` with code is in its `Model` and in its `Cell`. */
  def rows: Vector[Row] =
    this match
      case Model(_, acted)     => acted.toVector
      case Cell(acted)         => Vector(acted)
      case Call(started, done) => started +: done.toVector
      case Output(row)         => Vector(row)
      case Memory(_) | Llm(_)  => Vector.empty
      case Note(row)           => Vector(row)

  def exchanges: Vector[Exchange] =
    this match
      case Model(calls, _) => calls
      case Memory(calls)   => calls
      case Llm(call)       => Vector(call)
      case _               => Vector.empty

/** A step: from its `StepStarted` to the next `StepStarted` or to the end of its turn. */
final case class Step(start: Row, parts: Vector[Part]):
  def rows: Vector[Row]           = start +: parts.flatMap(_.rows)
  def exchanges: Vector[Exchange] = parts.flatMap(_.exchanges)

/** A turn: the `Input` right before its `TurnStarted`, the parts before its first step, its steps, and its `TurnEnded`.
  * A turn that runs has no end.
  */
final case class Turn(message: Option[Row], start: Row, lead: Vector[Part], steps: Vector[Step], end: Option[Row]):
  def rows: Vector[Row]           = message.toVector ++ (start +: lead.flatMap(_.rows)) ++ steps.flatMap(_.rows) ++ end
  def exchanges: Vector[Exchange] = lead.flatMap(_.exchanges) ++ steps.flatMap(_.exchanges)
  def parts: Vector[Part]         = lead ++ steps.flatMap(_.parts)

/** An item of the chat and the trajectory of a session (5.7). */
enum Item:
  case OfTurn(turn: Turn)

  /** An `Input` that starts no turn, for example a message of another session. */
  case Message(row: Row)

  /** Each other entry outside a turn, for example `Activated` or `JobEnded`. */
  case Note(row: Row)

  /** Summary calls of the memory outside a turn, after the entry `at`. */
  case Memory(calls: Vector[Exchange], at: Long)

  /** Another model call outside a turn, after the entry `at`. */
  case Llm(call: Exchange, at: Long)

  /** The new prompt of a policy call (5.8). It comes right before the item that holds the call, and `at` is the key of
    * that item. The call stays in that item, so this item holds no entry and no exchange.
    */
  case NewPrompt(mark: PromptMark, at: Long)

  /** The place of the item in the journal: the `seq` of its first entry, or of the entry that its calls come after. The
    * keys of the items of a journal never go down, so the follow replaces the items from a `seq` (5.7).
    */
  def key: Long =
    this match
      case OfTurn(turn)     => turn.message.getOrElse(turn.start).seq
      case Message(row)     => row.seq
      case Note(row)        => row.seq
      case Memory(_, at)    => at
      case Llm(_, at)       => at
      case NewPrompt(_, at) => at

  /** The `seq` of each entry of the item, once each, in order. */
  def seqs: Vector[Long] =
    val rows = this match
      case OfTurn(turn)                               => turn.rows
      case Message(row)                               => Vector(row)
      case Note(row)                                  => Vector(row)
      case Memory(_, _) | Llm(_, _) | NewPrompt(_, _) => Vector.empty
    rows.map(_.seq).distinct.sorted

  /** The id of each exchange of the item. */
  def exchangeIds: Vector[String] =
    this match
      case OfTurn(turn)     => turn.exchanges.map(_.id)
      case Memory(calls, _) => calls.map(_.id)
      case Llm(call, _)     => Vector(call.id)
      case _                => Vector.empty

/** The numbers of a turn (5.7): its time, its steps, its cells, its API calls, the tokens of its policy calls, and its
  * errors. A turn that runs has no time.
  */
final case class Summary(
    millis    : Option[Long],
    steps     : Int,
    cells     : Int,
    calls     : Int,
    prompt    : Long,
    completion: Long,
    errors    : Int
)

/** The chat and the trajectory of a session (5.7): the items of its journal and of its exchanges, and their HTML. */
object Story:

  private val Summaries = Set("compress", "merge", "shorten")

  /** The items of `rows` and `exchanges`. Each exchange comes after the last entry whose `seq` is not above its
    * `after`, as in the tree of the entries (5.2). The exchanges go in the order of their `after`, and two exchanges
    * with the same `after` keep the order of the file. Each entry and each exchange is in exactly one item. An item
    * with a call of `prompts` comes after a `NewPrompt` item of that call (5.8).
    */
  def of(rows: Vector[Row], exchanges: Vector[Exchange], prompts: Map[String, PromptMark] = Map.empty): Vector[Item] =
    val seqs                                          = rows.map(_.seq)
    def anchor(x: Exchange): Option[Int]              = Option(seqs.lastIndexWhere(_ <= x.after)).filter(_ >= 0)
    val sorted                                        = exchanges.sortBy(_.after)
    val (loose, placed)                               = sorted.partition(anchor(_).isEmpty)
    val after: Map[Int, Vector[Exchange]]             = placed.groupBy(x => anchor(x).getOrElse(-1))
    val events: Vector[Either[Row, (Exchange, Long)]] = loose.map(x => Right((x, x.after))) ++ rows.indices.flatMap {
      i =>
        Left(rows(i)) +: after.getOrElse(i, Vector.empty).map(x => Right((x, rows(i).seq)))
    }
    Walk(events, prompts).items

  /** The walk of the events of `of`: the entries and the exchanges in their order. */
  private final class Walk(events: Vector[Either[Row, (Exchange, Long)]], prompts: Map[String, PromptMark]):
    private val out                         = Vector.newBuilder[Item]
    private var turn: Option[OpenTurn]      = None
    private var pending: Option[Row]        = None
    private val pendingCalls                = ArrayBuffer[Exchange]()
    private var memory: Option[Item.Memory] = None

    private final class OpenTurn(val message: Option[Row], val start: Row):
      val lead                          = ArrayBuffer[Part]()
      val steps                         = ArrayBuffer[(Row, ArrayBuffer[Part])]()
      def parts: ArrayBuffer[Part]      = steps.lastOption.fold(lead)(_._2)
      def close(end: Option[Row]): Turn =
        Turn(message, start, lead.toVector, steps.toVector.map((s, ps) => Step(s, ps.toVector)), end)

    def items: Vector[Item] =
      events.indices.foreach(i => events(i).fold(entry(i, _), (x, at) => exchange(x, at)))
      flushMemory()
      turn.foreach(t => emit(Item.OfTurn(t.close(None))))
      out.result()

    /** Adds `item`, after a `NewPrompt` item for each of its calls that `prompts` has (5.8). */
    private def emit(item: Item): Unit =
      item.exchangeIds.flatMap(prompts.get).foreach(mark => out += Item.NewPrompt(mark, item.key))
      out += item: Unit

    private def entryOf(row: Row): Option[Entry] = row.logged.toOption.map(_.entry)

    /** True when the next entry after the event `i` is a `TurnStarted`. */
    private def startsTurn(i: Int): Boolean =
      events.iterator.drop(i + 1).collectFirst { case Left(row) => row }.flatMap(entryOf).contains(Entry.TurnStarted)

    private def flushMemory(): Unit =
      memory.foreach(emit)
      memory = None

    private def entry(i: Int, row: Row): Unit =
      flushMemory()
      (turn, entryOf(row)) match
        case (_, Some(Entry.TurnStarted)) =>
          turn.foreach(t => emit(Item.OfTurn(t.close(None))))
          val opened = OpenTurn(pending, row)
          opened.lead ++= pendingCalls.map(callPart): Unit
          pendingCalls.clear()
          pending = None
          turn = Some(opened)
        case (None, Some(Entry.Input(_, _, _))) if startsTurn(i) => pending = Some(row)
        case (None, Some(Entry.Input(_, _, _)))                  => out += Item.Message(row): Unit
        case (None, _)                                           => out += Item.Note(row): Unit
        case (Some(t), Some(Entry.TurnEnded(_)))                 =>
          emit(Item.OfTurn(t.close(Some(row))))
          turn = None
        case (Some(t), Some(Entry.StepStarted(_)))                        => t.steps += ((row, ArrayBuffer[Part]())): Unit
        case (Some(t), Some(Entry.Acted(_, _, code))) if t.steps.nonEmpty =>
          val parts = t.parts
          parts.lastOption match
            case Some(Part.Model(calls, None)) => parts(parts.size - 1) = Part.Model(calls, Some(row))
            case _                             => parts += Part.Model(Vector.empty, Some(row)): Unit
          if code.exists(_.trim.nonEmpty) then parts += Part.Cell(row): Unit
        case (Some(t), Some(Entry.EffectStarted(_, _, _))) if t.steps.nonEmpty => t.parts += Part.Call(row, None): Unit
        case (Some(t), Some(Entry.EffectDone(id, _))) if t.steps.nonEmpty      =>
          val parts = t.parts
          val open  = parts.lastIndexWhere {
            case Part.Call(started, None) => entryOf(started).exists {
                case Entry.EffectStarted(effect, _, _) => effect == id
                case _                                 => false
              }
            case _ => false
          }
          if open >= 0 then
            parts(open) match
              case Part.Call(started, _) => parts(open) = Part.Call(started, Some(row))
              case _                     => parts += Part.Note(row): Unit
          else parts += Part.Note(row): Unit
        case (Some(t), Some(Entry.Observed(_, _, _))) if t.steps.nonEmpty => t.parts += Part.Output(row): Unit
        case (Some(t), _)                                                 => t.parts += Part.Note(row): Unit

    private def callPart(x: Exchange): Part =
      if x.predictor.contains("policy") then Part.Model(Vector(x), None)
      else if x.predictor.exists(Summaries) then Part.Memory(Vector(x))
      else Part.Llm(x)

    private def exchange(x: Exchange, at: Long): Unit =
      (turn, pending) match
        case (Some(t), _) =>
          val parts = t.parts
          (parts.lastOption, callPart(x)) match
            case (Some(Part.Model(calls, None)), Part.Model(_, _)) =>
              parts(parts.size - 1) = Part.Model(calls :+ x, None)
            case (Some(Part.Memory(calls)), Part.Memory(_)) => parts(parts.size - 1) = Part.Memory(calls :+ x)
            case (_, part)                                  => parts += part: Unit
        case (None, Some(_)) => pendingCalls += x: Unit
        case (None, None)    =>
          if x.predictor.exists(Summaries) then
            memory = Some(memory.fold(new Item.Memory(Vector(x), at))(m => m.copy(calls = m.calls :+ x)))
          else
            flushMemory()
            emit(Item.Llm(x, at))

  // ── The numbers ──

  private def outcomeOf(row: Row): Option[CellOutcome] =
    row.logged.toOption.map(_.entry).collect { case Entry.Observed(_, outcome, _) => outcome }

  private def failedCall(row: Row): Boolean =
    row.logged.toOption.map(_.entry).exists {
      case Entry.EffectDone(_, EffectResult.Error(_)) => true
      case _                                          => false
    }

  private def failedExchange(x: Exchange): Boolean = x.error.nonEmpty || x.status.exists(_ != 200L)

  /** True for the outcome of a cell that did not end well: each outcome but `Ok` and `Submitted`. */
  private def failedOutcome(outcome: CellOutcome): Boolean =
    outcome match
      case CellOutcome.Ok | CellOutcome.Submitted(_) => false
      case _                                         => true

  /** The numbers of `turn` (5.7). */
  def summary(turn: Turn): Summary =
    val parts  = turn.parts
    val policy = parts.collect { case Part.Model(calls, _) => calls }.flatten
    val usage  = policy.flatMap(_.usage)
    val errors = parts.count {
      case Part.Output(row)         => outcomeOf(row).exists(failedOutcome)
      case Part.Call(_, Some(done)) => failedCall(done)
      case _                        => false
    } + turn.exchanges.count(failedExchange)
    Summary(
      turn.end.flatMap(at).zip(at(turn.start)).map((end, start) => end - start),
      turn.steps.size,
      parts.count { case Part.Cell(_) => true; case _ => false },
      parts.count { case Part.Call(_, _) => true; case _ => false },
      usage.map(_._1).sum,
      usage.map(_._2).sum,
      errors
    )

  private def at(row: Row): Option[Long] = row.logged.toOption.map(_.meta.atMillis)

  /** The end of `turn` in words: the reply, or what ended the turn. A turn that runs is `Working`. */
  def endText(turn: Turn): String =
    turn.end.flatMap(_.logged.toOption).map(_.entry) match
      case Some(Entry.TurnEnded(outcome)) => outcome match
          case TurnOutcome.Replied(text)    => text
          case TurnOutcome.Submitted(value) => s"The turn submitted ${value.print}."
          case TurnOutcome.Exhausted        => "The turn used all of its steps, and it ended with no reply."
          case TurnOutcome.Aborted          => "The turn stopped, because a stop came."
          case TurnOutcome.Crashed          => "The turn crashed: its host ended during the turn."
          case TurnOutcome.Failed(error)    => s"The turn failed: $error"
      case _ => "Working"

  // ── The HTML ──

  private[inspect] def thousands(n: Long): String = String.format(Locale.ROOT, "%,d", java.lang.Long.valueOf(n))

  /** A time in words: milliseconds under a second, then seconds with one decimal, then minutes and seconds. */
  private def duration(millis: Long): String =
    if millis < 1_000L then s"$millis ms"
    else if millis < 60_000L then String.format(Locale.ROOT, "%.1f s", java.lang.Double.valueOf(millis / 1000.0))
    else s"${millis / 60_000L} min ${millis % 60_000L / 1000L} s"

  private[inspect] def plural(n: Long, word: String): String = s"${thousands(n)} $word${if n == 1L then "" else "s"}"

  /** The cells of the numbers of a row: the time, the tokens in and out, the cached part, and the tokens per second. */
  private def numbers(time: Option[Long], x: Option[Exchange]): String =
    val usage  = x.flatMap(_.usage)
    val cached =
      for
        call   <- x
        (p, _) <- usage
        hit    <- call.cached
        if p > 0L
      yield s"${Math.round(100.0 * hit / p)}%"
    val speed =
      for
        call   <- x
        (_, c) <- usage
        end    <- call.ended
        from   <- call.first.orElse(call.at)
        if c > 0L && end > from
      yield s"${Math.round(c * 1000.0 / (end - from))} tok/s"
    Vector(
      time.map(duration),
      usage.map((p, c) => s"${thousands(p)} → ${thousands(c)}"),
      cached,
      speed
    ).map(cell => s"""<span class="n">${escape(cell.getOrElse(""))}</span>""").mkString

  private def timeOf(x: Exchange): Option[Long] = x.at.zip(x.ended).map((a, b) => b - a)

  private def row(kind: String, summary: String, more: String, cells: String, bad: Boolean = false): String =
    s"""<div class="r k-${kind.toLowerCase(Locale.ROOT)}${if bad then " bad" else ""}">""" +
      s"""<span class="kind">$kind</span><div class="sum">$summary$more</div>$cells</div>"""

  /** A text in short: its first line, cut at `limit` characters, and a click for the whole text. */
  private def short(text: String, limit: Int = 160): (String, String) =
    val first = text.linesIterator.nextOption().getOrElse("")
    val cut   = if first.length > limit then first.take(limit) + "…" else first
    val more  =
      if cut == text then ""
      else s"""<details><summary>all</summary><pre>${escape(text)}</pre></details>"""
    (escape(cut), more)

  /** A text of the model in short (L31): its first line, cut at `limit` characters, with its inline Markdown, and a
    * click for the whole text as Markdown.
    */
  private def shortMarkdown(text: String, limit: Int = 160): (String, String) =
    val first = text.linesIterator.nextOption().getOrElse("")
    val cut   = if first.length > limit then first.take(limit) + "…" else first
    val more  =
      if cut == text then ""
      else s"""<details><summary>all</summary><div class="md">${Markdown.html(text)}</div></details>"""
    (Markdown.inline(cut), more)

  /** The link of a row to the page of an exchange (L29): its number in the exchange file, as its page gives it. */
  private[inspect] def xref(session: SessionId, x: Exchange): String =
    val number = x.index + 1
    s"""<a class="xref" href="/s/${escape(session.value)}/x/${escape(x.id)}" """ +
      s"""title="Exchange $number: the HTTP request and its reply">#$number</a>"""

  /** The path of a URL, or the URL when it has no path. */
  private def pathOf(url: String): String =
    scala.util.Try(java.net.URI(url)).toOption.flatMap(u => Option(u.getRawPath)).filter(_.nonEmpty).getOrElse(url)

  /** The row of an HTTP request to a model (5.7, L29): its link, the predictor when it is not the policy, the method
    * and the path, the status, the word `stream` for a stream, the error, and the numbers of the request.
    */
  private def httpRow(session: SessionId, x: Exchange): String =
    val state = if x.open then "no reply yet" else x.status.fold("no status")(_.toString)
    val facts = Vector(
      x.predictor.filterNot(_ == "policy"),
      Some(s"POST ${pathOf(x.url)}"),
      Some(state),
      Option.when(x.stream)("stream"),
      x.error.flatMap(firstLine(_, 120)).map(e => s"error: $e")
    ).flatten.mkString(" · ")
    row(
      "HTTP",
      s"""${xref(session, x)} <span class="note">${escape(facts)}</span>""",
      "",
      numbers(timeOf(x), Some(x)),
      failedExchange(x)
    )

  private def who(row: Row): String =
    row.logged.toOption.map(_.meta.source).fold("") {
      case Source.User               => "You"
      case Source.Controller         => "The controller"
      case Source.Harness            => "The harness"
      case Source.Policy             => "The model"
      case Source.Child(session)     => s"Session ${session.value.take(8)}"
      case Source.Parent(session, _) => s"Session ${session.value.take(8)}"
    }

  private def clock(row: Row): String = at(row).fold("")(Html.time)

  /** A message of the chat: who wrote it, and the minute, with the time to the millisecond on a hover. `html` is the
    * text, already as HTML, and `meta` a line under it.
    */
  private def say(kind: String, by: String, row: Row, html: String, meta: String = ""): String =
    val minute = at(row).fold("")(Html.minute)
    s"""<div class="say $kind"><div class="who" title="${escape(clock(row))}">${escape(s"$by · $minute")}</div>""" +
      s"""<div class="text md">$html</div>""" +
      (if meta.isEmpty then "" else s"""<div class="meta">${escape(meta)}</div>""") + "</div>"

  /** A count in short: `947`, `43.7k`, `1.2M`. */
  private[inspect] def compact(n: Long): String =
    def one(value: Double, unit: String) =
      val text = String.format(Locale.ROOT, "%.1f", java.lang.Double.valueOf(value)).stripSuffix(".0")
      s"$text$unit"
    if n < 1_000L then n.toString else if n < 1_000_000L then one(n / 1e3, "k") else one(n / 1e6, "M")

  /** The first line of `text` that is not blank, cut at `limit` characters, or none. */
  private def firstLine(text: String, limit: Int): Option[String] =
    text.linesIterator.map(_.trim).find(_.nonEmpty).map(l => if l.length > limit then l.take(limit) + "…" else l)

  /** An entry outside a turn in words, for the chat view (5.7): the words of `words`, or none for an entry that the
    * chat leaves out. The chat leaves out the records of the harness (`ScopeRecorded` and `DerivedRead`), and an entry
    * of a turn, which comes outside a turn only on a page that starts in the middle of that turn.
    */
  private[inspect] def event(row: Row): Option[(String, String)] =
    row.logged.toOption.map(_.entry) match
      case Some(
            Entry.ScopeRecorded(_, _) | Entry.DerivedRead(_, _) | Entry.Input(_, _, _) | Entry.TurnStarted |
            Entry.StepStarted(_) | Entry.Acted(_, _, _) | Entry.EffectStarted(_, _, _) | Entry.EffectDone(_, _) |
            Entry.Observed(_, _, _) | Entry.TurnEnded(_)
          ) => None
      case _ => words(row)

  /** An entry in words (5.7, L30): a title and its facts, the minute first. The `NOTE` rows of the trajectory and the
    * lines of the chat use these words. A `DerivedRead` has none, so it has no row.
    */
  private[inspect] def words(row: Row): Option[(String, String)] =
    row.logged match
      case Left(_)       => Some(("A row that does not decode", s"#${row.seq}"))
      case Right(logged) =>
        val minute                             = Html.minute(logged.meta.atMillis)
        def first(text: String): String        = firstLine(text, 160).getOrElse("")
        def model(choice: ModelChoice): String =
          (choice.model +: choice.options.toVector.sortBy(_._1).map {
            case ("reasoning_effort", value) => s"effort ${Prompt.words(value)}"
            case (name, value)               => s"$name ${Prompt.words(value)}"
          }).mkString(" · ")
        logged.entry match
          case Entry.Activated(_, config) =>
            val title = if row.seq == 0L then "Session started" else "Session opened again"
            Some((title, s"$minute · ${model(config.model)} · ${modeName(config.mode)} · ${config.steps} steps a turn"))
          case Entry.ModelChanged(choice)   => Some(("Model", s"$minute · ${model(choice)}"))
          case Entry.GoalChanged(goal)      => Some(("Goal", s"$minute · $goal"))
          case Entry.GoalLoopStarted(limit) => Some(("Goal loop", s"$minute · at most $limit turns with no person"))
          case Entry.GoalChecked(check)     =>
            Some(("Goal check", s"$minute · ${if check.met then "met" else "not met"} · ${check.reason}"))
          case Entry.GoalLoopEnded(why) => Some(("Goal loop ended", s"$minute · ${why.report}"))
          case Entry.JobEnded(job, end) =>
            Some(("Job", s"$minute · ${jobName(job)} ${jobStatusWords(jobStatusOf(end))}"))
          case Entry.Granted(_, rule, life)     => Some(("Allowed", s"$minute · ${ScalaText.line(rule)} ${life.words}"))
          case Entry.StopRequested(reason)      => Some(("Stop", s"$minute · $reason"))
          case Entry.JobsStopRequested(_, r)    => Some(("Stop of jobs", s"$minute · $r"))
          case Entry.Ended(outcome, reason)     => Some(("Session ended", s"$minute · ${outcome.report(reason)}"))
          case Entry.Releasing(plan)            => Some(("Release", s"$minute · ${plural(plan.size.toLong, "step")}"))
          case Entry.Released(step)             => Some(("Released", s"$minute · ${ScalaText.line(step)}"))
          case Entry.ScopeRecorded(head, clean) =>
            Some(("Scope", s"$minute · git head ${head.take(12)} · ${if clean then "no change" else "with changes"}"))
          case Entry.Input(_, text, _)       => Some(("Message", s"$minute · ${who(row)}: ${first(text)}"))
          case Entry.TurnStarted             => Some(("Turn started", minute))
          case Entry.StepStarted(step)       => Some((s"Step ${step + 1}", minute))
          case Entry.Acted(_, text, _)       => Some(("Model text", s"$minute · ${first(text)}"))
          case Entry.EffectStarted(_, op, a) =>
            val args = a match
              case Json.Null => ""
              case other     => s" ${firstLine(other.print, 80).getOrElse("")}"
            Some(("API call", s"$minute · $op$args"))
          case Entry.EffectDone(_, _)        => Some(("API result", s"$minute · ${first(resultWords(Some(row)))}"))
          case Entry.Observed(_, outcome, _) => Some(("Cell output", s"$minute · ${first(outcome.words)}"))
          case Entry.TurnEnded(outcome)      => Some(("Turn ended", s"$minute · ${first(outcome.report)}"))
          case Entry.DerivedRead(_, _)       => None

  /** The words of the outcome of a cell. */
  private def outcomeWords(outcome: CellOutcome): String =
    outcome match
      case CellOutcome.Ok                  => "ok"
      case CellOutcome.CompileError(lines) => s"compile error: ${lines.mkString(" ")}"
      case CellOutcome.Failed(exception)   => s"failed: $exception"
      case CellOutcome.TimedOut(millis)    => s"timed out after ${duration(millis)}"
      case CellOutcome.Submitted(value)    => s"submitted ${value.print}"

  /** The words of the result of an API call. */
  private def resultWords(done: Option[Row]): String =
    done.flatMap(_.logged.toOption).map(_.entry) match
      case Some(Entry.EffectDone(_, result)) => result match
          case EffectResult.Value(_, rendered)  => rendered
          case EffectResult.Error(message)      => s"error: $message"
          case EffectResult.NeedsApproval(rule) => s"waits for an approval: ${rule.op}"
          case EffectResult.Unknown             => "unknown: the call did not end"
      case _ => "running"

  /** The HTML of the parts of a step, or of the start of a turn. */
  private def parts(session: SessionId, all: Vector[Part]): String =
    all.zipWithIndex.map { (part, i) =>
      part match
        case Part.Model(calls, acted) =>
          val act         = acted.flatMap(_.logged.toOption).map(_.entry).collect { case a: Entry.Acted => a }
          val text        = act.map(_.text).getOrElse("")
          val (sum, more) = shortMarkdown(text)
          val reasoning   = act.map(_.reasoning).filter(_.trim.nonEmpty).fold("")(r =>
            s"""<details class="reasoning"><summary>Reasoning</summary><pre>${escape(r)}</pre></details>"""
          )
          // Each request is an HTTP row with its numbers, and the reply that the harness read is the MODEL row.
          calls.map(httpRow(session, _)).mkString +
            acted.fold("")(_ => row("MODEL", sum, more + reasoning, numbers(None, None)))
        case Part.Cell(acted) =>
          val code     = acted.logged.toOption.map(_.entry).collect { case Entry.Acted(_, _, Some(c)) => c }.getOrElse("")
          val output   = all.drop(i + 1).collectFirst { case Part.Output(o) => o }
          val outcome  = output.flatMap(outcomeOf)
          val time     = output.flatMap(at).zip(at(acted)).map((end, start) => end - start)
          val lines    = code.linesIterator.size
          val (sum, _) = short(code, 120)
          val words    = outcome.fold("running")(outcomeWords)
          // A compile error can have many lines, so the row shows its start, and the click shows all of it.
          val (said, _) = short(words.replaceAll("\\s+", " "), 80)
          val body      = s"""<code>$sum</code> <span class="note">${escape(plural(lines, "line"))} · $said</span>"""
          val full      = if outcome.exists(failedOutcome) then s"<pre>${escape(words)}</pre>" else ""
          row(
            "CELL",
            body,
            s"""<details><summary>code</summary><pre>${Highlight.sourceHtml(code)}</pre>$full</details>""",
            numbers(time, None),
            outcome.exists(failedOutcome)
          )
        case Part.Call(started, done) =>
          val (op, args) = started.logged.toOption.map(_.entry).collect {
            case Entry.EffectStarted(_, name, _: Json.Null.type) => (name, "")
            case Entry.EffectStarted(_, name, a)                 => (name, a.print)
          }.getOrElse(("call", ""))
          val time           = done.flatMap(at).zip(at(started)).map((end, start) => end - start)
          val (argText, _)   = short(args, 120)
          val (result, more) = short(resultWords(done), 160)
          row(
            "CALL",
            s"""<b>${escape(op)}</b> <code>$argText</code> → $result""",
            more,
            numbers(time, None),
            done.exists(failedCall)
          )
        case Part.Output(o) =>
          val text        = o.logged.toOption.map(_.entry).collect { case Entry.Observed(out, _, _) => out }.getOrElse("")
          val (sum, more) = if text.isEmpty then ("(no output)", "") else short(text)
          row("OUTPUT", s"<code>$sum</code>", more, numbers(None, None))
        case Part.Memory(calls) => memoryRow(session, calls)
        case Part.Llm(x)        => httpRow(session, x)
        case Part.Note(note)    => noteRow(note)
    }.mkString

  private def memoryRow(session: SessionId, calls: Vector[Exchange]): String =
    val counts = calls.groupBy(_.predictor.getOrElse("call")).toVector.sortBy(_._1)
      .map((p, xs) => s"${xs.size} $p").mkString(", ")
    val time  = calls.flatMap(timeOf).sum
    val links = calls.map(xref(session, _)).mkString(" ")
    val usage = calls.flatMap(_.usage)
    val sum   = if usage.isEmpty then "" else s"${thousands(usage.map(_._1).sum)} → ${thousands(usage.map(_._2).sum)}"
    s"""<div class="r k-memory"><span class="kind">MEMORY</span><div class="sum">${escape(counts)} """ +
      s"""<span class="link">$links</span></div><span class="n">${escape(duration(time))}</span>""" +
      s"""<span class="n">${escape(sum)}</span><span class="n"></span><span class="n"></span></div>"""

  /** A `NOTE` row (5.7, L30): the entry in words, and the card of the entry, as the Entries view shows it, behind a
    * click. A `DerivedRead` has no row, and a row that does not decode is a bad row.
    */
  private def noteRow(note: Row): String =
    words(note).fold("") { (title, facts) =>
      val card = note.logged.toOption.fold("")(logged =>
        s"""<details><summary>entry</summary><div class="v-card">${EntryViews.card(logged)}</div></details>"""
      )
      val line = s"""<b>${escape(title)}</b> <span class="note">${escape(facts)}</span>"""
      row("NOTE", line, card, numbers(None, None), note.logged.isLeft)
    }

  /** The HTML of `items` (5.7): each item with its key. The chat view and the trajectory view show the same items, and
    * the class of the body picks the parts of each view: `c-only` for the chat and `t-only` for the trajectory.
    *
    *   - A turn has the message, the line of its work, and its end. Behind a click, the line opens a short list of the
    *     steps in the chat view (`brief`), and each part of each step in the trajectory view. The turn holds its time
    *     and its tokens in `data-ms`, `data-in` and `data-out`, for the totals of the bar.
    *   - An entry outside a turn is a line of words in the chat (`event`) and a `NOTE` row in the trajectory. An entry
    *     with no words, the memory summaries and the other model calls outside a turn are `quiet`: only the trajectory
    *     shows them.
    *
    * `now` gives the time so far of a turn that runs.
    */
  def html(session: SessionId, items: Vector[Item], now: Long): String =
    items.map { item =>
      val body = item match
        case Item.OfTurn(turn) => turnHtml(session, turn, now)
        case Item.Message(row) => say("other", who(row), row, Markdown.html(messageText(row)))
        case Item.Note(row)    =>
          val note = noteRow(row)
          val line = event(row).fold("")((title, facts) =>
            s"""<div class="ev c-only"><span><b>${escape(title)}</b> ${escape(facts)}</span></div>"""
          )
          if note.isEmpty && line.isEmpty then "" else s"""$line<div class="t-only">$note</div>"""
        case Item.Memory(calls, _)   => memoryRow(session, calls)
        case Item.Llm(x, _)          => httpRow(session, x)
        case Item.NewPrompt(mark, _) => PromptView.html(session, mark)
      // The follow keeps the item of a prompt that it already shows, so its box stays as the person left it (5.8).
      val (kind, attrs) = item match
        case Item.OfTurn(turn) =>
          val s = summary(turn)
          ("turn", s""" data-ms="${s.millis.getOrElse(0L)}" data-in="${s.prompt}" data-out="${s.completion}"""")
        case Item.Message(_)                        => ("message", "")
        case Item.NewPrompt(mark, _)                => ("prompt", s""" data-prompt="${escape(mark.call.id)}"""")
        case Item.Note(row) if event(row).isDefined => ("other", "")
        case _                                      => ("other quiet", "")
      if body.isEmpty then "" else s"""<section class="item $kind" data-key="${item.key}"$attrs>$body</section>"""
    }.mkString

  private def messageText(row: Row): String =
    row.logged.toOption.map(_.entry).collect { case Entry.Input(_, text, _) => text }.getOrElse("")

  /** The time of each step of `turn`: from its `StepStarted` to the next one, or to the end of the turn. */
  private def stepTimes(turn: Turn): Vector[Option[Long]] =
    turn.steps.zipWithIndex.map { (step, k) =>
      val end = turn.steps.lift(k + 1).map(_.start).orElse(turn.end).flatMap(at)
      end.zip(at(step.start)).map((b, a) => b - a)
    }

  /** The number of a step from 1, as the status of the prompt gives it (L29): `StepStarted(0)` is step 1. */
  private def stepNumber(step: Step, k: Int): Int =
    step.start.logged.toOption.map(_.entry).collect { case Entry.StepStarted(i) => i }.getOrElse(k) + 1

  private def replied(turn: Turn): Boolean =
    turn.end.flatMap(_.logged.toOption).map(_.entry).exists {
      case Entry.TurnEnded(TurnOutcome.Replied(_)) => true
      case _                                       => false
    }

  /** True for a turn that ended well: with a reply, or with a value of `submit`, as a child session ends a turn. */
  private def endedWell(turn: Turn): Boolean =
    turn.end.flatMap(_.logged.toOption).map(_.entry).exists {
      case Entry.TurnEnded(TurnOutcome.Replied(_) | TurnOutcome.Submitted(_)) => true
      case _                                                                  => false
    }

  /** The work of a turn in short, for the chat view (5.7): a line for each step with the text of the model, then the
    * first line of the cell and of its output with a mark for the outcome, and the time of the step. A step with no
    * cell at the end of a turn that replied is `The reply`. The memory summaries of the turn come in one line, with a
    * button that opens the turn in the trajectory view.
    */
  private def brief(turn: Turn): String =
    val times = stepTimes(turn)
    val lines = turn.steps.zipWithIndex.map { (step, k) =>
      val acted = step.parts.collectFirst { case Part.Model(_, Some(a)) => a }.flatMap(_.logged.toOption)
        .map(_.entry).collect { case a: Entry.Acted => a }
      val cell   = step.parts.collectFirst { case Part.Cell(a) => a }
      val output = step.parts.collectFirst { case Part.Output(o) => o }
      val failed = output.flatMap(outcomeOf).exists(failedOutcome) || step.parts.exists {
        case Part.Call(_, Some(done)) => failedCall(done)
        case _                        => false
      }
      val code = cell.flatMap(_.logged.toOption).map(_.entry).collect { case Entry.Acted(_, _, Some(c)) => c }
      val out  = output.flatMap(_.logged.toOption).map(_.entry).collect { case Entry.Observed(o, _, _) => o }
      val run  = cell.fold("") { _ =>
        val mark =
          if output.isEmpty then """<span class="run">…</span>"""
          else if failed then """<span class="bad">✗</span>"""
          else """<span class="ok">✓</span>"""
        val result = out.flatMap(firstLine(_, 100)).fold("")(o => s" → ${escape(o)}")
        s"""<span class="c">$mark ${escape(code.flatMap(firstLine(_, 120)).getOrElse(""))}$result</span>"""
      }
      val text =
        if cell.isEmpty && k == turn.steps.size - 1 && replied(turn) then "The reply"
        else acted.fold("Working")(a => firstLine(a.text, 200).getOrElse(""))
      s"""<div class="bstep"><span class="i">${stepNumber(
          step,
          k
        )}</span><span class="t">${Markdown.inline(text)}$run</span>""" +
        s"""<span class="n">${escape(times(k).fold("")(duration))}</span></div>"""
    }
    val memory = turn.parts.collect { case Part.Memory(calls) => calls }.flatten
    val words  =
      if memory.isEmpty then ""
      else
        val count = if memory.size == 1 then "1 memory summary" else s"${memory.size} memory summaries"
        s"$count · ${duration(memory.flatMap(timeOf).sum)} · "
    lines.mkString + s"""<div class="bfoot">${escape(words)}<button data-goto>Open in the trajectory</button></div>"""

  private def turnHtml(session: SessionId, turn: Turn, now: Long): String =
    val s     = summary(turn)
    val time  = s.millis.orElse(at(turn.start).map(now - _)).fold("")(duration)
    val verb  = if turn.end.isEmpty then "Working" else "Worked"
    val facts = Vector(
      Some(s"$verb $time"),
      Some(plural(s.steps, "step")),
      Some(plural(s.cells, "cell")),
      Some(plural(s.calls, "call")),
      Option.when(s.prompt + s.completion > 0L)(s"${compact(s.prompt)} → ${compact(s.completion)} tokens"),
      Option.when(s.errors > 0)(plural(s.errors, "error"))
    ).flatten.mkString(" · ")
    val bad  = turn.end.isDefined && !endedWell(turn)
    val mark =
      if turn.end.isEmpty then """<span class="mk run">…</span>"""
      else if bad then """<span class="mk bad">✗</span>"""
      else """<span class="mk ok">✓</span>"""
    val message = turn.message.fold("")(m => say("person", who(m), m, Markdown.html(messageText(m))))
    val lead    = if turn.lead.isEmpty then "" else s"""<div class="step">${parts(session, turn.lead)}</div>"""
    val times   = stepTimes(turn)
    val steps   = turn.steps.zipWithIndex.map { (step, k) =>
      val took = times(k).fold("")(t => s" · ${duration(t)}")
      s"""<div class="step"><div class="step-head">Step ${stepNumber(step, k)} · ${escape(clock(step.start))}""" +
        s"""${escape(took)}</div>""" + parts(session, step.parts) + "</div>"
    }.mkString
    val end = turn.end.fold(s"""<div class="say working"><div class="text">Working</div></div>""") { e =>
      val text = e.logged.toOption.map(_.entry) match
        case Some(Entry.TurnEnded(TurnOutcome.Replied(reply))) => Markdown.html(reply)
        case _                                                 => s"<p>${escape(endText(turn))}</p>"
      val meta = Vector(at(e).map(Html.minute), s.millis.map(duration)).flatten.mkString(" · ")
      say(if bad then "reply bad" else "reply", "The reply", e, text, meta)
    }
    s"""$message<details class="work"><summary>$mark${escape(facts)}</summary>""" +
      s"""<div class="brief c-only">${brief(turn)}</div><div class="traj t-only">$lead$steps</div></details>$end"""
