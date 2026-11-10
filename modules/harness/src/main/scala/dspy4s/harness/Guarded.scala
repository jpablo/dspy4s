package dspy4s.harness

import dspy4s.harness.guest.NeedsApproval
import dspy4s.harness.kernel.{EffectResult, GuardResult, SessionState, guardCheck}
import zio.blocks.schema.json.Json

/** Why the handler `guarded` stops a call (CODE_HARNESS_DESIGN.md, 5.5): the result for its `EffectDone`, and the
  * exception that the cell gets.
  */
final case class GuardStop(result: EffectResult, error: RuntimeException)

/** The handler `guarded` (5.3, 5.5). The recorder calls it after the commit of the `EffectStarted` of each call, so
  * `guardCheck` reads the grants at that place, and a `Once` grant stays until the outcome of its call.
  *
  *   - `GuardResult.Run` gives `Right(())`, and the call runs.
  *   - `GuardResult.NeedsPerson(rule)` gives `EffectResult.NeedsApproval(rule)` and a `NeedsApproval` error with the op
  *     and the text of the arguments. `evolve` adds the rule to `pending`, so a client finds it in the journal, and a
  *     `catch` in the cell cannot hide it.
  *   - `GuardResult.Refused(reason)` gives `EffectResult.Error(reason)` and an error with that reason.
  *
  * A proposal for the review: the recorder calls `guarded` for each call of each session, also in a program call. The
  * guard of a program call is `Guard.allowAll`, so each of its calls runs as before.
  */
def guarded(current: SessionState, op: String, args: Json): Either[GuardStop, Unit] =
  guardCheck(current, op, args) match
    case GuardResult.Run               => Right(())
    case GuardResult.NeedsPerson(rule) =>
      Left(GuardStop(EffectResult.NeedsApproval(rule), NeedsApproval(op, args.print)))
    case GuardResult.Refused(reason) => Left(GuardStop(EffectResult.Error(reason), IllegalStateException(reason)))
