package dspy4s.harness.cli

import dspy4s.harness.host.jobName
import dspy4s.harness.textPrefix
import dspy4s.harness.kernel.{
  ArgMatch,
  EffectResult,
  Entry,
  GuardRule,
  JobSelector,
  Logged,
  ModelChoice,
  Source,
  TurnOutcome
}
import zio.blocks.schema.json.Json

/** The most lines of the output of a cell that the terminal shows. */
private val OutputLines = 40

/** The most characters of the reasoning of a step that `ReasoningView.Short` shows. */
private val ShortReasoning = 400

/** The most characters of a command that the line of the start of a call shows. */
private val CommandChars = 160

/** The text of an entry for the terminal (CODE_HARNESS_DESIGN.md, 10.1), or none for an entry that the person does not
  * need to see.
  *
  *   - `Acted`: the reasoning of the step as `reasoning` sets, the text of the step, and the code of its cell, if any.
  *     Each line of the reasoning starts with a bar, so that the reasoning is different from the text.
  *   - `EffectStarted` of `exec` or `spawn`: its command in quotes, cut to a few characters with a note of the rest. A
  *     command can take a long time, and this text shows the person that the session is not stuck. The other calls give
  *     none, because they are quick.
  *   - `Observed`: a first line with the outcome of the cell in words after `> ` (`CellOutcome.words`), then the output
  *     of the cell, cut to a few lines with a note of the rest.
  *   - `EffectDone` with `NeedsApproval`: the call in quotes, and the commands that answer it with the number of the
  *     call, which is the `seq` of the entry (5.5).
  *   - `TurnEnded`: the text of the report to the owner (`TurnOutcome.report`), apart from `Replied`, whose text the
  *     `Acted` already showed.
  *   - `StopRequested`, `JobsStopRequested`, `Granted` and `Ended`: one line each. A stopped job has its `jobName`.
  *     `Granted` gives the lifetime of the grant in words (`GrantLifetime.words`), for example `Allowed for this turn:
  *     `. `Ended` gives the text of `SessionOutcome.report`, as the report to the owner.
  *   - An `Input` from a source that is not `User`, for example the message at the end of a job: its text after the
  *     sender in words (`sourceWords`), for example `[the session s9] text`.
  *   - The other entries give none. A `StepStarted` gives none too: the status line of the editor shows the step while
  *     it runs, with a spinner and the time (`Screen.live`).
  */
def entryText(logged: Logged, reasoning: ReasoningView = ReasoningView.Short): Option[String] =
  rawText(logged, reasoning).map(printable)

/** `text` with no control character apart from a new line: a tab becomes two spaces, and the other control characters
  * go. A mark that changes the direction of the text becomes a visible escape such as `\u202E`. So the output of a cell
  * or the text of a model cannot send escape sequences to the terminal, or turn the text around.
  */
private[cli] def printable(text: String): String =
  text.flatMap {
    case '\n'                           => "\n"
    case '\t'                           => "  "
    case c if Character.isISOControl(c) => ""
    case c if direction(c.toInt)        => escape(c.toInt)
    case c                              => c.toString
  }

/** A command, an argument or another text that a model chose, as one line in quotes. A backslash, a quote, a new line,
  * a return and a tab have the escapes of JSON. Each other character that `hidden` names becomes a `\uXXXX` escape. So
  * the text cannot change the terminal, and two commands that differ do not look the same.
  */
private[cli] def inQuotes(text: String): String =
  val body = text.codePoints().toArray.map { cp =>
    if cp == '\\'.toInt then "\\\\"
    else if cp == '"'.toInt then "\\\""
    else if cp == '\n'.toInt then "\\n"
    else if cp == '\r'.toInt then "\\r"
    else if cp == '\t'.toInt then "\\t"
    else if hidden(cp) then escape(cp)
    else String(Character.toChars(cp))
  }
  "\"" + body.mkString + "\""

/** `text` with a `\uXXXX` escape for each character that `inQuotes` escapes as hidden, and no other change. */
private[cli] def visible(text: String): String =
  text.codePoints().toArray.map(cp => if hidden(cp) then escape(cp) else String(Character.toChars(cp))).mkString

/** True for a character that a terminal does not show as itself, or shows as a plain space:
  *
  *   - a control character, a format character (for example a mark of the direction of the text), or a surrogate with
  *     no pair
  *   - a separator of lines or paragraphs, and each space apart from the plain space U+0020
  *   - a Hangul filler (U+115F, U+1160, U+3164, U+FFA0), which shows as nothing
  *   - a variation selector (U+FE00 to U+FE0F, U+E0100 to U+E01EF)
  */
private def hidden(cp: Int): Boolean =
  val kind = Character.getType(cp)
  Character.isISOControl(cp) || kind == Character.FORMAT ||
  (cp >= 0xd800 && cp <= 0xdfff) ||
  kind == Character.LINE_SEPARATOR || kind == Character.PARAGRAPH_SEPARATOR ||
  (kind == Character.SPACE_SEPARATOR && cp != ' '.toInt) || cp == 0x115f || cp == 0x1160 || cp == 0x3164 ||
  cp == 0xffa0 ||
  (cp >= 0xfe00 && cp <= 0xfe0f) ||
  (cp >= 0xe0100 && cp <= 0xe01ef)

/** True for a mark that changes the direction of the text: each Bidi_Control character of Unicode (U+061C, U+200E,
  * U+200F, U+202A to U+202E, U+2066 to U+2069).
  */
private def direction(cp: Int): Boolean =
  cp == 0x061c || cp == 0x200e || cp == 0x200f ||
    (cp >= 0x202a && cp <= 0x202e) ||
    (cp >= 0x2066 && cp <= 0x2069)

/** The `\uXXXX` escape of each UTF-16 unit of a code point. */
private def escape(cp: Int): String = Character.toChars(cp).map(c => f"\\u${c.toInt}%04x").mkString

private def rawText(logged: Logged, reasoning: ReasoningView): Option[String] =
  logged.entry match
    case Entry.Acted(thought, text, code) =>
      val parts = reasoningText(thought, reasoning).toVector ++ Vector(text).filter(_.nonEmpty) ++
        code.map(c => s"```scala\n$c\n```")
      Option.when(parts.nonEmpty)(parts.mkString("\n"))
    case Entry.EffectStarted(_, op, args)   => startText(op, args)
    case Entry.Observed(output, outcome, _) =>
      val lines = output.linesIterator.toVector
      val shown =
        if lines.size <= OutputLines then lines
        else lines.take(OutputLines) :+ s"[${lines.size - OutputLines} more lines]"
      Some((s"> ${outcome.words}" +: shown).mkString("\n"))
    case Entry.EffectDone(_, EffectResult.NeedsApproval(rule)) =>
      Some(s"A call waits for your approval: ${callText(rule)}. /allow ${logged.seq} allows it, with once, turn or " +
        "session after the number. /allow allows each pending call, and a message refuses them.")
    case Entry.TurnEnded(TurnOutcome.Replied(_))                      => None
    case Entry.TurnEnded(outcome)                                     => Some(outcome.report)
    case Entry.StopRequested(reason)                                  => Some(s"The turn stops: $reason.")
    case Entry.JobsStopRequested(which, reason)                       => Some(s"${jobsWords(which)}: $reason.")
    case Entry.Granted(_, rule, lifetime)                             => Some(s"Allowed ${lifetime.words}: ${callText(rule)}.")
    case Entry.Ended(outcome, reason)                                 => Some(outcome.report(reason))
    case Entry.ModelChanged(model)                                    => Some(changedText(model))
    case Entry.Input(_, text, _) if logged.meta.source != Source.User =>
      Some(s"[${sourceWords(logged.meta.source)}] $text")
    case _ => None

/** The sender of a message in words, for example `the session s9` for `Child(s9)`. */
private[cli] def sourceWords(source: Source): String =
  source match
    case Source.User               => "the person"
    case Source.Controller         => "the controller"
    case Source.Harness            => "the harness"
    case Source.Policy             => "the model"
    case Source.Child(session)     => s"the session ${session.value}"
    case Source.Parent(session, _) => s"the parent session ${session.value}"

/** The jobs of a stop in words, with the name of one job. */
private[cli] def jobsWords(which: JobSelector): String =
  which match
    case JobSelector.One(job)   => s"The job ${jobName(job)} stops"
    case JobSelector.OfLastTurn => "The jobs of the last turn stop"
    case JobSelector.All        => "Each job stops"

/** The reasoning of a step as `view` sets, each line after a bar, or none for an empty reasoning or `Off`. `Line` is
  * one line with the size of the reasoning. `Short` cuts the reasoning to `ShortReasoning` units, with no character cut
  * in two, and adds a note of the rest.
  */
private[cli] def reasoningText(reasoning: String, view: ReasoningView): Option[String] =
  val text = reasoning.strip
  if text.isEmpty || view == ReasoningView.Off then None
  else if view == ReasoningView.Line then
    Some(s"│ The reasoning has ${text.length} characters. /reasoning short shows it.")
  else
    val shown =
      if view == ReasoningView.Full || text.length <= ShortReasoning then text
      else
        val start = textPrefix(text, ShortReasoning)
        s"$start…\n[${text.length - start.length} more characters. /reasoning full shows all the reasoning of the " +
          "next steps.]"
    Some(shown.linesIterator.map(line => s"│ $line").mkString("\n"))

/** The text of the call of a rule: its op and its arguments. A call with a command, as `exec` and `spawn`, shows the
  * command in quotes. Other arguments show as JSON, with an escape for each hidden character.
  */
private[cli] def callText(rule: GuardRule): String =
  rule.args match
    case ArgMatch.Exact(value)        => s"${rule.op} ${commandIn(value).fold(visible(value.print))(inQuotes)}"
    case ArgMatch.Prefix(field, text) => s"${rule.op} with $field starting with ${inQuotes(text)}"
    case ArgMatch.AnyArgs             => s"each call of ${rule.op}"

/** The text of the field `command` of `args`, if any. */
private[cli] def commandIn(args: Json): Option[String] =
  args match
    case Json.Object(fields) => fields.iterator.collectFirst { case ("command", Json.String(text)) => text }
    case _                   => None

/** The text of the start of a call: `$ "command"` for an `exec`, and `[job] $ "command"` for a `spawn`, which starts a
  * background job. Other calls, and a call with no `command` text, give none.
  */
private def startText(op: String, args: Json): Option[String] =
  val prefix = op match
    case "exec"  => Some("$ ")
    case "spawn" => Some("[job] $ ")
    case _       => None
  for
    start   <- prefix
    command <- commandIn(args)
  yield start + startCommand(command)

/** `command` in quotes on one line (`inQuotes`), as each command of a model. A command with more than `CommandChars`
  * units shows its start, with no character cut in two, and a note of the rest. So a long script in a command does not
  * fill the screen.
  */
private def startCommand(command: String): String =
  if command.length <= CommandChars then inQuotes(command)
  else
    val start = textPrefix(command, CommandChars)
    s"${inQuotes(start)} [${command.length - start.length} more characters]"

/** The words of a `ModelChanged` with `model` (CLI_SCREEN.md, 14.4): the new effort, or a change of the other options.
  */
private[cli] def changedText(model: ModelChoice): String =
  Effort.of(model).fold("The options of the model changed. The next step uses them.")(level =>
    s"The effort is now $level. The next step uses it."
  )
