package dspy4s.harness.kernel

/** The reports of a session to its owner (8.6). A report is a pure function of the journal, so a second send has the
  * same request id and the same text.
  *
  * An `Input` from `owner` opens a debt at the first step or stop that sees it. While the debt is open, each
  * `TurnEnded` and `Ended` gives one `Send`. `Submitted`, `Exhausted`, `Aborted` and `Ended` close the debt, and
  * `Replied` keeps it open. The request id holds the session and the `seq` of that entry, and not the text. So a new
  * text does not change which reports the owner has.
  *
  * The owner reads the text with a model, so the text gives the outcome in words, and not as a Scala value:
  *
  *   - `TurnEnded`: the text of `TurnOutcome.report`, then a line that says that the session stays open and that `tell`
  *     gives it more work. The end line of the turn in the terminal of the session shows only the first line. The
  *     second line tells the owner that the session did not end, so the owner sends a change to this session and starts
  *     no new session.
  *   - `Ended`: the text of `SessionOutcome.report` with the reason. The terminal shows the same text.
  *   - `GoalLoopEnded`: the text of `LoopEnd.report`, then the report of the last turn, then the line that says that
  *     the session stays open (8.5).
  *
  * While a goal loop is on, a `TurnEnded` gives no report, and the debt changes as before (8.5). Each `GoalLoopEnded`
  * gives one report, also when no debt is open, and so does an `Ended` while a loop is on. So the owner pays no model
  * call for each turn of a long loop, and it gets the end.
  *
  * For the review: the design gives `reports(session, journal)`, with no owner. The function must know which sender is
  * the owner, and `Source` has no case for a parent session. So this signature takes the source of the owner, as the
  * sketch of section 12 does.
  */
def reports(session: SessionId, owner: Source, journal: Vector[Logged]): Vector[Command.Send] =
  def send(seq: Long, text: String): Command.Send =
    Command.Send(RequestId(s"report:${session.value}:$seq"), text, Map.empty)
  val open = s"The session ${session.value} stays open. To give it more work, send it a message with tell."
  debtAfter(owner, journal).due.collect {
    case (Logged(seq, _, Entry.TurnEnded(outcome)), _)     => send(seq, s"${outcome.report}\n$open")
    case (Logged(seq, _, Entry.Ended(outcome, reason)), _) => send(seq, outcome.report(reason))
    case (Logged(seq, _, Entry.GoalLoopEnded(end)), last)  =>
      send(seq, ((end.report +: last.map(_.report).toVector) :+ open).mkString("\n"))
  }

/** True when the next step of the session owes `owner` a report at the end of its turn (8.6): the debt of `reports` is
  * open after `journal`, or `journal` has an `Input` of `owner` that no step or stop saw yet, because the next step
  * sees it. The task of a work session asks for `submit` only while this is true.
  */
def owes(owner: Source, journal: Vector[Logged]): Boolean =
  val debt = debtAfter(owner, journal)
  debt.open || debt.unseen

/** The state of the debt of 8.6 after a journal. `unseen` is true when an `Input` of the owner came after the last step
  * or stop. `open` is true when the debt is open. `due` holds the `TurnEnded`, `Ended` and `GoalLoopEnded` entries that
  * give a report, in the order of the journal, each with the outcome of the last turn before it. `looping` is true
  * while a goal loop is on, and `last` is the outcome of the last turn (8.5).
  */
private final case class ReportDebt(
    unseen : Boolean,
    open   : Boolean,
    due    : Vector[(Logged, Option[TurnOutcome])],
    looping: Boolean,
    last   : Option[TurnOutcome]
)

/** The state of the debt of `owner` after `journal` (8.6). `reports` and `owes` use this one fold, so they agree. */
private def debtAfter(owner: Source, journal: Vector[Logged]): ReportDebt =
  def ends(outcome: TurnOutcome): Boolean =
    outcome match
      case TurnOutcome.Submitted(_) | TurnOutcome.Exhausted | TurnOutcome.Aborted => true
      case _                                                                      => false
  val start = ReportDebt(unseen = false, open = false, due = Vector.empty, looping = false, last = None)
  journal.foldLeft(start) { (debt, logged) =>
    logged.entry match
      case Entry.Input(_, _, _)                          => debt.copy(unseen = debt.unseen || logged.meta.source == owner)
      case Entry.StepStarted(_) | Entry.StopRequested(_) => debt.copy(unseen = false, open = debt.open || debt.unseen)
      case Entry.TurnEnded(outcome) if debt.looping      =>
        debt.copy(open = debt.open && !ends(outcome), last = Some(outcome))
      case Entry.TurnEnded(outcome) if debt.open =>
        debt.copy(open = !ends(outcome), due = debt.due :+ (logged -> None), last = Some(outcome))
      case Entry.TurnEnded(outcome)                       => debt.copy(last = Some(outcome))
      case Entry.Ended(_, _) if debt.open || debt.looping =>
        debt.copy(open = false, looping = false, due = debt.due :+ (logged -> None))
      case Entry.GoalLoopStarted(_) => debt.copy(looping = true)
      case Entry.GoalLoopEnded(_)   => debt.copy(looping = false, due = debt.due :+ (logged -> debt.last))
      case Entry.Activated(_, _)    => debt.copy(looping = false)
      case _                        => debt
  }

/** The reports that the owner does not have yet: the requests that are not in the state of the owner (8.6). The report
  * pass sends them.
  */
def missing(
    session   : SessionId,
    owner     : Source,
    journal   : Vector[Logged],
    ownerState: SessionState
): Vector[Command.Send] =
  reports(session, owner, journal).filterNot(send => ownerState.requests(send.request))
