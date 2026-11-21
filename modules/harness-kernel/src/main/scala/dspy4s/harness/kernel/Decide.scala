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
  *     `unattended` is at the limit of the activation (`SessionConfig.unattendedLimit`). So it waits in the journal,
  *     and the next message of the person starts the turn that sees it. The end of a turn starts no turn for such a
  *     message either. `Activate` after a restart reads the limit of the new configuration, so a higher limit or no
  *     limit starts the turn of the message that waits. The limit `NoLimit` never holds a message. While a goal loop is
  *     on, the limit is its N (8.5).
  *
  * The rules of the goal loop (8.5):
  *   - `StartLoop` from the person gives `GoalLoopStarted`, and from another sender `Rejection.NotPerson`. A limit
  *     below 1 gives `Rejection.LoopLimit`. `Begin` with a loop above 0 gives `GoalLoopStarted` after `Activated`.
  *   - `StopLoop` from each sender gives `GoalLoopEnded(Off)`, an `Abort` of the person `GoalLoopEnded(Stopped)`, and
  *     an `Activate` `GoalLoopEnded(Restarted)` before its `Activated`. With no loop, they give no end. The end of a
  *     loop starts no turn by itself: after a `GoalLoopEnded` while a message waits, `held` holds each message, apart
  *     from a message of the person, until a turn or a new loop starts. `StartLoop` with no open turn starts the turn
  *     of a message that it frees.
  *   - `RecordGoalCheck` gives no entry when no check is due (`goalCheckDue`), else the entries of `goalChecked`.
  *   - The end of a turn of a session with no goal ends its loop at the limit (`limitEnd`).
  *   - `ChangeGoal` gives `GoalChanged` in an open session, also during a turn. The goal of the session gives no entry.
  *   - A `Grant` with no pending call that it covers starts the turn of a message that waits for the person, because
  *     the grant is an act of the person and `held` frees the message.
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
      // A restart takes the permission of the loop back (8.5). After `GoalLoopEnded(Restarted)`, `held` holds each
      // message that waits, apart from a message of the person (`afterLoop`).
      val ended     = if s.loop.isDefined then Vector(Entry.GoalLoopEnded(LoopEnd.Restarted)) else Vector.empty
      val activated = (repair(s) ++ ended) :+ Entry.Activated(versions, config)
      val now       = after(s, activated)
      Right(if now.waiting > 0 && !held(now) && !now.releasing then activated :+ Entry.TurnStarted else activated)
    case Command.Begin(versions, config, request, text, loop) =>
      if s.nextSeq != 0 then Left(Rejection.NotNew)
      else
        val start = if loop > 0 then Vector(Entry.GoalLoopStarted(loop)) else Vector.empty
        Right((Entry.Activated(versions, config) +: start) ++
          Vector(Entry.Input(request, text, Map.empty), Entry.TurnStarted))
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
        val ends       = limitEnd(fresh, now)
        val last       = after(now, ends)
        val inputWaits = last.position == Position.NoTurn && last.waiting > 0 && !held(last) && !last.releasing
        if inputWaits && fresh.nonEmpty then (fresh ++ ends) :+ Entry.TurnStarted else fresh ++ ends
      }
    case Command.Abort(reason) =>
      val stop =
        if s.position != Position.NoTurn && !s.stopping then Vector(Entry.StopRequested(reason)) else Vector.empty
      // A stop of the person ends the goal loop, and a stop of the harness keeps it (8.5).
      val ends =
        if sender == Source.User && s.loop.isDefined then Vector(Entry.GoalLoopEnded(LoopEnd.Stopped)) else Vector.empty
      Right(stop ++ ends)
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
      if !s.pending.exists(rule.covers) then
        // A grant is an act of the person, so it frees a message that waits for the person, and that message starts
        // its turn (8.7, 8.5).
        val now    = after(s, Vector(granted))
        val starts = now.position == Position.NoTurn && now.waiting > 0 && !held(now) && !now.releasing
        Right(if starts then Vector(granted, Entry.TurnStarted) else Vector(granted))
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
    case Command.ChangeGoal(_) if s.closed                        => Left(Rejection.SessionClosed)
    case Command.ChangeGoal(goal) if s.config.goal.contains(goal) => Right(Vector.empty)
    case Command.ChangeGoal(goal)                                 => Right(Vector(Entry.GoalChanged(goal)))
    case Command.StartLoop(_) if sender != Source.User            => Left(Rejection.NotPerson)
    case Command.StartLoop(limit) if limit < 1                    => Left(Rejection.LoopLimit(limit))
    case Command.StartLoop(_) if s.closed                         => Left(Rejection.SessionClosed)
    case Command.StartLoop(_) if s.releasing                      => Left(Rejection.Releasing)
    case Command.StartLoop(limit)                                 =>
      // The new limit can free a message that waits, so it starts its turn (8.5).
      val started = Entry.GoalLoopStarted(limit)
      val now     = after(s, Vector(started))
      val starts  = now.position == Position.NoTurn && now.waiting > 0 && !held(now) && !now.releasing
      Right(if starts then Vector(started, Entry.TurnStarted) else Vector(started))
    case Command.StopLoop               => Right(if s.loop.isDefined then Vector(Entry.GoalLoopEnded(LoopEnd.Off)) else Vector.empty)
    case Command.RecordGoalCheck(check) => Right(if goalCheckDue(s) then goalChecked(s, check) else Vector.empty)

/** The entries of a check of the goal that is due (8.5): `GoalChecked`, then the goal message and `TurnStarted`, or the
  * end of the loop. A met goal ends it with `Met`, the third same blocker with `Blocked`, and the limit with `Limit`.
  * After an end, a message that waits waits for the person (`afterLoop`), so the end starts no turn.
  */
private def goalChecked(s: SessionState, check: GoalCheck): Vector[Entry] =
  val checked = Entry.GoalChecked(check)
  val now     = after(s, Vector(checked))
  val ending  = now.loop.flatMap { loop =>
    if check.met then Some(LoopEnd.Met(check.reason))
    else if loop.blocked >= GoalLoop.BlockedChecks then Some(LoopEnd.Blocked(check.blocker))
    else if unattendedUsed(now) >= loop.limit then Some(LoopEnd.Limit)
    else None
  }
  ending match
    case Some(why) =>
      val ended  = Entry.GoalLoopEnded(why)
      val last   = after(now, Vector(ended))
      val starts = last.waiting > 0 && !held(last) && !last.releasing
      Vector(checked, ended) ++ (if starts then Vector(Entry.TurnStarted) else Vector.empty)
    case None =>
      // The request id holds the `seq` of the `GoalChecked`, so one check gives one message.
      val text = goalText(s.config.goal.getOrElse(""), check)
      Vector(checked, Entry.Input(RequestId(s"goal:${s.nextSeq}"), text, Map.empty), Entry.TurnStarted)

/** The end of the loop of a session with no goal after `fresh` (8.5): `Limit` when `fresh` ends a turn and the count of
  * the unattended turns of `now` is at the limit of the loop. A session with a goal ends it in its check.
  */
private def limitEnd(fresh: Vector[Entry], now: SessionState): Vector[Entry] =
  val turnEnds = fresh.exists {
    case Entry.TurnEnded(_) => true
    case _                  => false
  }
  val atLimit = now.loop.exists(loop => now.config.goal.isEmpty && unattendedUsed(now) >= loop.limit)
  if turnEnds && atLimit then Vector(Entry.GoalLoopEnded(LoopEnd.Limit)) else Vector.empty

/** True when the journal has the end of the job `job` (10.3). */
private def ended(s: SessionState, job: EffectId): Boolean = s.jobs.get(job).exists(_.end.isDefined)

/** True when a turn must wait for the person (8.7): the unattended turns are at their limit, and no message of the
  * person waits. The limit is the limit of the activation (`SessionConfig.unattendedLimit`), or the N of the goal loop
  * while it is on, and the turns that the loop gave count (8.5). `NoLimit` never holds. After the end of a goal loop
  * while a message waited, each message waits for the person too (`afterLoop`), until a turn or a new loop starts.
  */
private[kernel] def held(s: SessionState): Boolean =
  !s.personWaits &&
    (s.afterLoop ||
      (unattendedLimit(s) match
        case TurnLimit.AtMost(turns) => unattendedUsed(s) >= turns
        case TurnLimit.NoLimit       => false))

/** True when a message waits for the person (8.7): the session is open, no turn is open, a message waits, no release
  * runs, and the limit of the unattended turns holds the message (`held`). A client shows a notice then, because no
  * turn starts until the person writes.
  */
def waitsForPerson(s: SessionState): Boolean =
  !s.closed && s.position == Position.NoTurn && s.waiting > 0 && !s.releasing && held(s)

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
    // The read of the derived data of a step comes before its `Acted` (7.2, choice 3).
    case Entry.DerivedRead(_, _) => !s.stopping &&
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
    case (Command.Grant(_, _, _), Entry.Input(_, _, _))        => Source.Harness
    case (Command.Begin(_, _, _, _, _), Entry.Activated(_, _)) => Source.Harness
    case _                                                     => sender
