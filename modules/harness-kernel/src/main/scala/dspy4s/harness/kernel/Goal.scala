package dspy4s.harness.kernel

import zio.blocks.schema.json.Json

/** The text of the goal message after a check that says "not met" (8.5): the goal, the reason of the check, the blocker
  * if any, and "Go on with the work." The kernel writes it, so a client cannot put words in it.
  */
def goalText(goal: String, check: GoalCheck): String =
  val blocker = if check.blocker.isEmpty then "" else s"\nThe blocker: ${check.blocker}"
  s"The goal check says that the goal is not met yet.\nThe goal: $goal\nThe check: ${check.reason}$blocker\n" +
    "Go on with the work."

/** The turns that the loop of `s` has left (8.5): N, minus the count of its unattended turns, minus the turns that it
  * gave. A session with no loop has 0, and the result is never below 0.
  */
def loopLeft(s: SessionState): Int = s.loop.fold(0)(loop => (loop.limit - s.unattended - loop.gave).max(0))

/** The loop of the card of a session (8.5): `on 12/50` while it is on, with the turns used and N, else the words of the
  * last end, or `off` for a session with no loop.
  */
def loopWords(s: SessionState): String =
  s.loop match
    case Some(loop) => s"on ${unattendedUsed(s)}/${loop.limit}"
    case None       => s.loopEnd.fold("off")(_.words)

/** The sessions that got turns from the current loop of `journal` (8.5): the session of each `start` with a `loop`
  * above 0 and an outcome with a value, after the last `GoalLoopStarted`. The host sends `StopLoop` to each one after
  * `/goal off` or a stop of the person.
  */
def loopChildren(journal: Vector[Logged]): Vector[SessionId] =
  val current = journal.lastIndexWhere(_.entry match
    case Entry.GoalLoopStarted(_) => true
    case _                        => false)
  // The `start` calls with a loop that have no outcome yet, and the sessions of the calls with a value.
  val (_, children) = journal.drop(current + 1).foldLeft((Set.empty[EffectId], Vector.empty[SessionId])) {
    case ((open, found), logged) => logged.entry match
        case Entry.EffectStarted(id, op, args) if op == startOp && loopOf(args) > 0 => (open + id, found)
        case Entry.EffectDone(id, EffectResult.Value(value, _)) if open(id)         => (open - id, found ++ sessionOf(value))
        case Entry.EffectDone(id, _)                                                => (open - id, found)
        case _                                                                      => (open, found)
  }
  if current < 0 then Vector.empty else children

/** The session of the value of a `start`: the field `id` of a `SessionRef`. */
private def sessionOf(value: Json): Option[SessionId] =
  value match
    case Json.Object(fields) => fields.collectFirst { case ("id", Json.String(id)) => SessionId(id) }
    case _                   => None

/** The limit of the unattended turns of `s` (8.7, 8.5): the N of its loop while the loop is on, else the limit of the
  * activation (`SessionConfig.unattendedLimit`).
  */
def unattendedLimit(s: SessionState): TurnLimit =
  s.loop.fold(s.config.unattendedLimit)(loop => TurnLimit.AtMost(loop.limit))

/** The unattended turns that `s` used (8.5): its count, and the turns that its loop gave. */
def unattendedUsed(s: SessionState): Int = s.unattended + s.loop.fold(0)(_.gave)

/** The loop after a check (8.5): the count of the same blocker changes. A new blocker sets it to 1, the same blocker
  * adds 1, and no blocker sets it to 0.
  */
private[kernel] def afterCheck(loop: GoalLoop, check: GoalCheck): GoalLoop =
  val count =
    if check.blocker.isEmpty then 0
    else if check.same && loop.blocked > 0 then loop.blocked + 1
    else 1
  loop.copy(last = Some(check), blocked = count, blocker = check.blocker)

/** The operation that starts a session (8.2). Its arguments hold the turns of its loop (8.5). */
private[kernel] val startOp = "start"

/** The turns of the loop that the arguments `args` of a `start` give: the number of the field `loop`, or 0. */
private[kernel] def loopOf(args: Json): Int =
  args match
    case Json.Object(fields) =>
      fields.collectFirst { case ("loop", Json.Number(n)) if n.isValidInt => n.toInt }.getOrElse(0).max(0)
    case _ => 0
