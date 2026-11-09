package dspy4s.harness.kernel

/** The entries that a command can commit (4.5). It keeps each journal inside the language of 4.1. The table of 4.5
  * gives the entries of each command, and the rules for `Commit` come after it.
  */
def decide(command: Command, s: SessionState): Either[Rejection, Vector[Entry]] =
  command match
    case Command.Activate(versions, config) =>
      val activated = repair(s) :+ Entry.Activated(versions, config)
      Right(if after(s, activated).waiting > 0 then activated :+ Entry.TurnStarted else activated)
    case Command.Send(request, _, _) if s.requests(request) => Right(Vector.empty)
    case Command.Send(_, _, _) if s.closed                  => Left(Rejection.SessionClosed)
    case Command.Send(request, text, values)                =>
      val input = Entry.Input(request, text, values)
      Right(if s.position == Position.NoTurn then Vector(input, Entry.TurnStarted) else Vector(input))
    case Command.Commit(entries) =>
      // A late outcome of a closed effect adds nothing, because an interrupt does not wait for a commit in progress.
      val fresh = entries.filter {
        case Entry.EffectDone(id, _) => s.open.contains(id)
        case _                       => true
      }
      val checked = fresh.foldLeft[Either[Rejection, SessionState]](Right(s)) {
        case (Right(now), entry) if allowed(now, entry) => Right(after(now, Vector(entry)))
        case (Right(_), entry)                          => Left(Rejection.OutOfOrder(entry))
        case (refused, _)                               => refused
      }
      checked.map { now =>
        val inputWaits = now.position == Position.NoTurn && now.waiting > 0
        if inputWaits && fresh.nonEmpty then fresh :+ Entry.TurnStarted else fresh
      }
    case Command.Abort(reason) =>
      Right(if s.position != Position.NoTurn && !s.stopping then Vector(Entry.StopRequested(reason)) else Vector.empty)
    case Command.StopJobs(which, reason) =>
      if s.closed then Left(Rejection.SessionClosed) else Right(Vector(Entry.JobsStopRequested(which, reason)))
    case Command.Grant(request, _, _) if s.requests(request) => Right(Vector.empty)
    case Command.Grant(_, _, _) if s.closed                  => Left(Rejection.SessionClosed)
    case Command.Grant(request, rule, lifetime)              =>
      val granted = Entry.Granted(request, rule, lifetime)
      if !s.pending.exists(rule.covers) then Right(Vector(granted))
      else
        val input = Entry.Input(request, approvedText(rule), Map.empty)
        Right(if s.position == Position.NoTurn then Vector(granted, input, Entry.TurnStarted)
        else Vector(granted, input))
    case Command.Close(outcome, reason) =>
      if s.closed then Right(Vector.empty)
      else if s.position != Position.NoTurn then Left(Rejection.TurnOpen)
      else Right(Vector(Entry.Ended(outcome, reason)))

/** True when `entry` of a `Commit` is valid at this place of the language of 4.1. */
private def allowed(s: SessionState, entry: Entry): Boolean =
  entry match
    case Entry.ScopeRecorded(_, _)                      => !s.closed
    case _ if s.closed || s.position == Position.NoTurn => false
    case Entry.StepStarted(i)                           => !s.stopping && s.position == Position.BeforeStep(i)
    case Entry.Acted(_, _, _)                           => !s.stopping &&
      (s.position match
        case Position.InPolicy(_) => true
        case _                    => false)
    case Entry.EffectStarted(id, _, _) => !s.stopping &&
      (s.position match
        case Position.InCell(_, cell, _) => id.cell == cell
        case _                           => false)
    case Entry.EffectDone(id, _) => s.open.contains(id)
    case Entry.Observed(_, _, _) => s.open.isEmpty &&
      (s.position match
        case Position.InCell(_, _, _) => true
        case _                        => false)
    case Entry.TurnEnded(_) => s.open.isEmpty &&
      (s.position match
        case Position.InCell(_, _, _) => false
        case _                        => true)
    case _ => false

/** The state after some entries, for the checks of `decide`. The `seq` and the meta are not known yet, and `evolve`
  * reads only the `seq` of them.
  */
private def after(s: SessionState, entries: Vector[Entry]): SessionState =
  val meta = Meta(0L, Source.Harness, Taint.Trusted, required = true)
  entries.foldLeft(s)((now, entry) => evolve(now, Logged(now.nextSeq, meta, entry)))

/** The text of the `Input` after a grant that answers a pending call (5.5). The harness writes it, so a client cannot
  * put words in it.
  */
def approvedText(rule: GuardRule): String =
  s"The person approved a call of `${rule.op}`. Make the call again if you still need it."

/** The sender of an entry that `command` commits for `sender` (5.5). The `Input` after a grant comes from the harness,
  * so its turn gives a report to the owner (8.6).
  */
def sourceOf(command: Command, sender: Source, entry: Entry): Source =
  (command, entry) match
    case (Command.Grant(_, _, _), Entry.Input(_, _, _)) => Source.Harness
    case _                                              => sender
