package dspy4s.harness.host

import dspy4s.core.contracts.RuntimeContext
import dspy4s.harness.{HarnessLimits, Journal, PolicyInput, PolicyStep, Secrets, SubAnswer, SubQuery}
import dspy4s.harness.guest.Mode
import dspy4s.harness.kernel.{Guard, SessionConfig, SessionId}
import dspy4s.harness.sandbox.{Backend, HostList}
import dspy4s.programs.strategies.Predict

import java.nio.file.Path
import scala.annotation.nowarn

/** What a work session needs from the machine (CODE_HARNESS_DESIGN.md, 8.1): the home folder for the secret paths
  * (5.4), the host list of a mode with `Network^`, the backend of the sandbox, and the secrets to mask (5.6).
  */
final case class WorkEnv(home: Path, hosts: HostList, backend: Option[Backend], secrets: Secrets)

/** The runtime of a work session (8.1): the user owns the session, and each message starts a chat turn.
  *
  *   - A turn runs with the effects of a harness (`HarnessEffects`): the `policy` leaf, the window strategy, and a REPL
  *     with the mode and the preamble of the configuration. The task of the policy is `WorkRuntime.instructions` and
  *     the scope. The window gives the messages and the replies of the earlier turns (7).
  *   - The handlers are the handlers of the mode through `recorded`, and the recorder calls `guarded` for each call
  *     (5.5). `exec` and the jobs run in the sandbox, in the scope of the session, through `JobShell`.
  *   - `llmQuery` goes to the `subQuery` leaf, and `rlm` runs a child, as in a program call (9).
  *   - The REPL lives as long as the activation, so a value of a turn stays for the next turns. A new activation has a
  *     new REPL, and the store comes back from the journal (11, scenario 3).
  *
  * A proposal for the review: each turn gets a new budget of `limits.modelCalls` model calls (8.2), because a work
  * session has no end of a call. The context of the runtime gives the LM, so the key never goes into the journal.
  */
@nowarn("msg=unused") // the skeleton: the proof uses the parameters
final class WorkRuntime(
    env     : WorkEnv,
    limits  : HarnessLimits,
    policy  : Predict[PolicyInput, PolicyStep],
    subQuery: Predict[SubQuery, SubAnswer]
)(using RuntimeContext)
    extends SessionRuntime:

  def activate(session: SessionId, config: SessionConfig, journal: Journal, jobs: JobTable): Activation = ???

object WorkRuntime:

  /** The first instructions of the policy of a work session. An optimizer can change them. */
  val instructions: String =
    "Work with the person in the folder of the session, with Scala 3 code in a REPL. At each step, give a short text " +
      "and the code of one cell, or only a text. The cell runs, and the next step sees its output. Use the functions " +
      "of the API reference. Keep a value for a later cell or turn in a val or in the store. A step with no code " +
      "ends the turn, and its text is the reply to the person. If a call needs an approval, end the turn and ask."

  /** The first rules of 5.5: in a mode with `Network^`, `exec` with the prefix `git push` gets `Ask`. In another mode
    * the sandbox already stops a push, so the guard has no rule.
    */
  def defaultGuard(mode: Mode): Guard = ???

  /** The configuration of a work session in the folder `scope` (8.1): a chat turn with `limits.steps` steps, the owner
    * `User`, `mode` as the mode and the ceiling, the window strategy, `defaultGuard(mode)`, the preamble of a session
    * with no input fields, and the model `model`.
    */
  def config(scope: Path, mode: Mode, model: String, limits: HarnessLimits): SessionConfig = ???

  /** A runtime with the first leaves: the policy with `instructions`, and the `subQuery` leaf of a harness. */
  def forWork(env: WorkEnv, limits: HarnessLimits = HarnessLimits())(using RuntimeContext): WorkRuntime = ???
