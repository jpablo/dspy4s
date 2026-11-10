package dspy4s.harness.cli

import dspy4s.harness.kernel.{ArgMatch, EffectResult, Entry, GuardRule, Logged, Source, TurnOutcome}

/** The most lines of the output of a cell that the terminal shows. */
private val OutputLines = 40

/** The text of an entry for the terminal (CODE_HARNESS_DESIGN.md, 10.1), or none for an entry that the person does not
  * need to see.
  *
  *   - `Acted`: the text of the step, and the code of its cell, if any.
  *   - `Observed`: the output of the cell, cut to a few lines with a note of the rest.
  *   - `EffectDone` with `NeedsApproval`: the call and the commands that answer it (5.5).
  *   - `TurnEnded`: the outcome, apart from `Replied`, whose text the `Acted` already showed.
  *   - `StopRequested`, `JobsStopRequested`, `Granted` and `Ended`: one line each.
  *   - An `Input` from a source that is not `User`, for example the message at the end of a job: its text.
  *   - The other entries give none.
  */
def entryText(logged: Logged): Option[String] = rawText(logged).map(printable)

/** `text` with no control character apart from a new line: a tab becomes two spaces, and the others go. So the output
  * of a cell or the text of a model cannot send terminal escape sequences to the terminal of the person.
  */
private def printable(text: String): String =
  text.flatMap {
    case '\n'                           => "\n"
    case '\t'                           => "  "
    case c if Character.isISOControl(c) => ""
    case c                              => c.toString
  }

private def rawText(logged: Logged): Option[String] =
  logged.entry match
    case Entry.Acted(_, text, code) =>
      Some((Vector(text).filter(_.nonEmpty) ++ code.map(c => s"```scala\n$c\n```")).mkString("\n"))
    case Entry.Observed(output, outcome, _) =>
      val lines = output.linesIterator.toVector
      val shown =
        if lines.size <= OutputLines then lines
        else lines.take(OutputLines) :+ s"[${lines.size - OutputLines} more lines]"
      Some((s"> $outcome" +: shown).mkString("\n"))
    case Entry.EffectDone(_, EffectResult.NeedsApproval(rule)) =>
      Some(s"A call waits for your approval: ${callText(rule)}. Answer with /allow once, /allow turn or " +
        "/allow session, or send a message to refuse.")
    case Entry.TurnEnded(TurnOutcome.Replied(_))                      => None
    case Entry.TurnEnded(outcome)                                     => Some(s"The turn ended: $outcome.")
    case Entry.StopRequested(reason)                                  => Some(s"The turn stops: $reason.")
    case Entry.JobsStopRequested(which, reason)                       => Some(s"The jobs $which stop: $reason.")
    case Entry.Granted(_, rule, lifetime)                             => Some(s"Allowed ($lifetime): ${callText(rule)}.")
    case Entry.Ended(outcome, reason)                                 => Some(s"The session ended: $outcome, $reason.")
    case Entry.Input(_, text, _) if logged.meta.source != Source.User => Some(s"[${logged.meta.source}] $text")
    case _                                                            => None

/** The text of the call of a rule: its op and its arguments. */
private def callText(rule: GuardRule): String =
  rule.args match
    case ArgMatch.Exact(value)        => s"${rule.op} ${value.print}"
    case ArgMatch.Prefix(field, text) => s"${rule.op} with $field starting with $text"
    case ArgMatch.AnyArgs             => s"each call of ${rule.op}"
