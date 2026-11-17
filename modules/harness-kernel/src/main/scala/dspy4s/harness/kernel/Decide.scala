package dspy4s.harness.kernel

/** The entries that a command can commit (4.5). It keeps each journal inside the language of 4.1. The table of 4.5
  * gives the entries of each command, and the rules for `Commit` come after it.
  *
  * The rules of phase 9 (8.7):
  *   - `Begin` gives `Activated`, the `Input` of the task and `TurnStarted` on a journal with no entry, and
  *     `Rejection.NotNew` on each other journal.
  *   - After `Releasing`, `Send` and `Grant` give `Rejection.Releasing`, and `Activate` starts no turn. `Released`,
  *     `JobsStopRequested` through `StopJobs`, and `Close` stay valid. `Ended` closes the release, so a session that
  *     opens again after its end takes messages.
  *   - The unattended turns: a `Send` whose sender is not the person gives its `Input` and no `TurnStarted` when
  *     `unattended` is at `SessionState.UnattendedTurns`. So it waits in the journal, and the next message of the
  *     person starts the turn that sees it. The end of a turn and `Activate` after a restart start no turn for such a
  *     message either.
  *
  * The rule of the model (CLI_SCREEN.md, 14.4): `ChangeModel` gives `ModelChanged` in an open session, also during a
  * turn and a release, and the next call to the policy uses the new model. The model of the session gives no entry, and
  * a session that has ended gives `Rejection.SessionClosed`. Only this command gives `ModelChanged`, so a `Commit` of
  * it is refused.
  *
  * The rule of the end of a job (10.3): `RecordJobEnd` gives `JobEnded` for a job of the state with no end, also with
  * no open turn and during a release. A job with an end, or a job that the state does not have, gives no entry, so a
  * job has one end in the journal. A session that has ended gives `Rejection.SessionClosed`. Only this command gives
  * `JobEnded`, so a `Commit` of it is refused.
  *
  * The rule of the stop of one job (10.3): `StopJobs(One(job))` of a job with an end gives `Rejection.JobEnded`, so the
  * person or the cell learns that the job has ended. `OfLastTurn` and `All` stay valid for each job, because a stop
  * mark after the end keeps the end.
  */
def decide(command: Command, s: SessionState, sender: Source = Source.User): Either[Rejection, Vector[Entry]] =
  command match
    case Command.Activate(versions, config) =>
      val activated = repair(s) :+ Entry.Activated(versions, config)
      val now       = after(s, activated)
      Right(if now.waiting > 0 && !held(now) && !now.releasing then activated :+ Entry.TurnStarted else activated)
    case Command.Begin(versions, config, request, text) =>
      if s.nextSeq != 0 then Left(Rejection.NotNew)
      else Right(Vector(Entry.Activated(versions, config), Entry.Input(request, text, Map.empty), Entry.TurnStarted))
    case Command.Send(request, _, _) if s.requests(request) => Right(Vector.empty)
    case Command.Send(_, _, _) if s.closed                  => Left(Rejection.SessionClosed)
    case Command.Send(_, _, _) if s.releasing               => Left(Rejection.Releasing)
    case Command.Send(request, text, values)                =>
      val input = Entry.Input(request, text, values)
      val waits = sender != Source.User && held(s)
      Right(if s.position == Position.NoTurn && !waits then Vector(input, Entry.TurnStarted) else Vector(input))
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
        val inputWaits = now.position == Position.NoTurn && now.waiting > 0 && !held(now) && !now.releasing
        if inputWaits && fresh.nonEmpty then fresh :+ Entry.TurnStarted else fresh
      }
    case Command.Abort(reason) =>
      Right(if s.position != Position.NoTurn && !s.stopping then Vector(Entry.StopRequested(reason)) else Vector.empty)
    case Command.StopJobs(which, reason) =>
      if s.closed then Left(Rejection.SessionClosed)
      else
        which match
          case JobSelector.One(job) if ended(s, job) => Left(Rejection.JobEnded(job))
          case _                                     => Right(Vector(Entry.JobsStopRequested(which, reason)))
    case Command.Grant(request, _, _) if s.requests(request) => Right(Vector.empty)
    case Command.Grant(_, _, _) if s.closed                  => Left(Rejection.SessionClosed)
    case Command.Grant(_, _, _) if s.releasing               => Left(Rejection.Releasing)
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
    case Command.ChangeModel(_) if s.closed                    => Left(Rejection.SessionClosed)
    case Command.ChangeModel(model) if model == s.config.model => Right(Vector.empty)
    case Command.ChangeModel(model)                            => Right(Vector(Entry.ModelChanged(model)))
    case Command.RecordJobEnd(_, _) if s.closed                => Left(Rejection.SessionClosed)
    case Command.RecordJobEnd(job, end)                        =>
      Right(if s.jobs.get(job).exists(_.end.isEmpty) then Vector(Entry.JobEnded(job, end)) else Vector.empty)

/** True when the journal has the end of the job `job` (10.3). */
private def ended(s: SessionState, job: EffectId): Boolean = s.jobs.get(job).exists(_.end.isDefined)

/** True when a turn must wait for the person (8.7): `unattended` is at `SessionState.UnattendedTurns`, and no message
  * of the person waits.
  */
private def held(s: SessionState): Boolean = s.unattended >= SessionState.UnattendedTurns && !s.personWaits

/** True when `entry` of a `Commit` is valid at this place of the language of 4.1. An `EffectStarted` needs an `n` above
  * the `n` of each call that its cell started, open or closed. A second intent with the id of an open effect would
  * replace the first, and the first would get no outcome of its own. A replay answers each call by its id, so one id
  * must name one call of the cell.
  */
private def allowed(s: SessionState, entry: Entry): Boolean =
  entry match
    case Entry.ScopeRecorded(_, _)                      => !s.closed
    case Entry.Releasing(_)                             => !s.closed && s.position == Position.NoTurn && !s.releasing
    case Entry.Released(_)                              => !s.closed && s.position == Position.NoTurn && s.releasing
    case _ if s.closed || s.position == Position.NoTurn => false
    case Entry.StepStarted(i)                           => !s.stopping && s.position == Position.BeforeStep(i)
    case Entry.Acted(_, _, _)                           => !s.stopping &&
      (s.position match
        case Position.InPolicy(_) => true
        case _                    => false)
    case Entry.EffectStarted(id, _, _) => !s.stopping && id.n > s.lastCall &&
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
  *
  * A grant of a pending call covers only the same arguments, so the text gives the exact call, `op(args)` as in the
  * error of the call. A model that made the call again with another goal text got a second question (G3). The arguments
  * of a long call do not go in the text, because the model has the code of its cell.
  */
def approvedText(rule: GuardRule): String =
  rule.args match
    case ArgMatch.Exact(args) =>
      val shown = args.print
      val call  = if shown.length <= ApprovedArgsChars then s" `${rule.op}($shown)`" else s" of `${rule.op}`"
      s"The person approved the call$call. If you still need it, make the call again with the same arguments. " +
        "A call with other arguments needs a new approval."
    case _ =>
      s"The person approved the calls of `${rule.op}` that the rule covers. Make the call again if you still need it."

/** The most characters of the arguments that `approvedText` gives. */
private val ApprovedArgsChars = 600

/** The sender of an entry that `command` commits for `sender` (5.5). The `Input` after a grant comes from the harness,
  * so its turn gives a report to the owner (8.6).
  */
def sourceOf(command: Command, sender: Source, entry: Entry): Source =
  (command, entry) match
    case (Command.Grant(_, _, _), Entry.Input(_, _, _))     => Source.Harness
    case (Command.Begin(_, _, _, _), Entry.Activated(_, _)) => Source.Harness
    case _                                                  => sender
