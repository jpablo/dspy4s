package dspy4s.harness.kernel

import zio.blocks.schema.json.Json

/** What the handler `guarded` does with a call (5.5). */
enum GuardResult derives CanEqual:
  case Run

  /** The call waits for a person. The rule has `Exact` arguments: the narrowest rule. */
  case NeedsPerson(rule: GuardRule)

  case Refused(reason: String)

/** The check of a call (5.5). The handler `guarded` calls it after the commit of `EffectStarted`, so it reads the
  * grants at that place. A grant changes only `Ask`, and a session with no person never gets `NeedsPerson`.
  */
def guardCheck(s: SessionState, op: String, args: Json): GuardResult =
  val granted = s.grants.exists(_.rule.matches(op, args))
  s.config.guard.verdict(op, args) match
    case Verdict.Allow                           => GuardResult.Run
    case Verdict.Deny                            => GuardResult.Refused("a rule denies this call")
    case Verdict.Ask if granted                  => GuardResult.Run
    case Verdict.Ask if s.config.owner.hasPerson => GuardResult.NeedsPerson(GuardRule(op, ArgMatch.Exact(args)))
    case Verdict.Ask                             => GuardResult.Refused("no person can answer in this session")

/** True when the goal check can run (8.5): after a turn, with no input that waits, and with no pending call. */
def goalCheckDue(s: SessionState): Boolean =
  !s.closed && s.position == Position.NoTurn && s.waiting == 0 && s.pending.isEmpty
