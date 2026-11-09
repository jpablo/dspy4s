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
def guardCheck(s: SessionState, op: String, args: Json): GuardResult = ???

/** True when the goal check can run (8.5): after a turn, with no input that waits, and with no pending call. */
def goalCheckDue(s: SessionState): Boolean = ???
