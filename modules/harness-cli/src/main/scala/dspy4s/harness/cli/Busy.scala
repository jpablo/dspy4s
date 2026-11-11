package dspy4s.harness.cli

import dspy4s.harness.host.Activity
import dspy4s.harness.kernel.{Entry, Logged}
import dspy4s.harness.textPrefix

/** What a session does now, for the busy prompt of the client (CODE_HARNESS_DESIGN.md, 10.6): a short label, the time
  * of its start in milliseconds, the step of the turn, from 0, and true for a command (`exec` or `spawn`), which can
  * wait for the walk for secret files.
  *
  * A live session of 2026-11-11 showed `$ "ls -la"` and then the prompt `> ` for many seconds. The prompt of a person
  * with no open turn looked the same, so the person could not see that the session worked.
  */
final case class Busy(label: String, sinceMillis: Long, step: Int, command: Boolean = false) derives CanEqual

object Busy:

  /** The frames of the spinner. Each frame shows for `FrameMillis`. */
  private val Spinner     = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
  private val FrameMillis = 125L

  /** The most characters of a command in a label. */
  private val CommandChars = 60

  /** What runs after `logged`, from `current`, what ran before it. None is a session with no open turn. The time of a
    * new value is the time of its entry.
    *
    *   - `TurnStarted`: the turn starts. `TurnEnded`: none.
    *   - `StepStarted`: the model works on the step.
    *   - `Acted` with code, and `EffectDone`: the cell runs. `Observed`: the cell ended.
    *   - `EffectStarted`: the command in quotes for `exec` and `spawn`, else the op of the call.
    *   - `StopRequested`: the turn stops.
    *   - Each other entry keeps `current`.
    */
  def after(current: Option[Busy], logged: Logged): Option[Busy] =
    val step                                = current.fold(0)(_.step)
    def at(label   : String, n: Int = step) = Some(Busy(label, logged.meta.atMillis, n))
    def ofStep(text: String)                = at(s"step ${step + 1}: $text")
    logged.entry match
      case Entry.TurnStarted                => at("the turn starts", 0)
      case Entry.TurnEnded(_)               => None
      case Entry.StepStarted(n)             => at(s"step ${n + 1}: the model works", n)
      case Entry.Acted(_, _, Some(_))       => ofStep("the cell runs")
      case Entry.EffectDone(_, _)           => ofStep("the cell runs")
      case Entry.Observed(_, _, _)          => ofStep("the cell ended")
      case Entry.EffectStarted(_, op, args) => commandIn(args).filter(_ => op == "exec" || op == "spawn") match
          case Some(text) => ofStep(s"$op ${inQuotes(cut(text, CommandChars))}").map(_.copy(command = true))
          case None       => ofStep(op)
      case Entry.StopRequested(_) => at("the turn stops")
      case _                      => current

  /** The prompt of the line of the person: `> ` with no open turn, else a frame of the spinner, the time since the
    * start of `busy`, and `> `. The time `nowMillis` sets the frame, so each frame shows for `FrameMillis`.
    */
  def prompt(busy: Option[Busy], nowMillis: Long): String =
    busy.fold("> ") { running =>
      val frame = Spinner.charAt(((nowMillis / FrameMillis) % Spinner.length).toInt)
      s"$frame ${elapsed(nowMillis - running.sinceMillis)} > "
    }

  /** The label of `busy` for the right side of the line, at most `width` characters, with `…` at the end of a cut text.
    * During a command, it also gives `activity` of the host after the label, because the command can wait for it. The
    * text of the activity tells the person why the command waits, so the label is cut first. None gives the empty text.
    */
  def rightPrompt(busy: Option[Busy], activity: Option[Activity], width: Int): String =
    busy.fold("") { running =>
      activity.filter(_ => running.command).map(activityText) match
        case None       => fit(running.label, width)
        case Some(text) =>
          val room = width - text.length - Separator.length
          if room < MinLabel then fit(text, width) else s"${fit(running.label, room)}$Separator$text"
    }

  /** The text of an activity of the host. A number has a comma for each group of three digits. */
  private def activityText(activity: Activity): String =
    activity match
      case Activity.SecretScan(folders, None)        => s"the scan for secret files: ${number(folders)} folders"
      case Activity.SecretScan(folders, Some(total)) =>
        s"the scan for secret files: ${number(folders)} of ${number(total)} folders"

  private def number(n: Long): String = "%,d".formatLocal(java.util.Locale.ROOT, n)

  /** The text between the label and the activity, and the least room of a label next to an activity. */
  private val Separator = " · "
  private val MinLabel  = 8

  /** `text`, or its start and `…` when it has more than `width` characters. */
  private def fit(text: String, width: Int): String =
    if text.length <= width then text else s"${textPrefix(text, (width - 1).max(0))}…"

  /** A time of `millis` milliseconds in whole seconds, or in minutes and seconds from one minute. A negative time is 0
    * seconds.
    */
  private def elapsed(millis: Long): String =
    val seconds = millis.max(0L) / 1000
    if seconds < 60 then s"$seconds s" else s"${seconds / 60} m ${seconds % 60} s"

  /** The start of `text`, at most `units` units, with `…` when the text is longer. */
  private def cut(text: String, units: Int): String =
    if text.length <= units then text else s"${textPrefix(text, units)}…"
