package dspy4s.harness.host

import dspy4s.harness.HarnessLimits
import dspy4s.harness.guest.{Access, Data, Mode, Scope, StrategyName, preamble}
import dspy4s.harness.kernel.{ArgMatch, Guard, GuardRule, ModelChoice, Owner, SessionConfig, SessionId, Source}
import dspy4s.harness.kernel.{TurnKind, Verdict, VerdictRule}
import zio.blocks.schema.json.Json

import java.nio.file.Path

/** The controller (CODE_HARNESS.md, 7.1 and 7.3; CODE_HARNESS_DESIGN.md, 8.1 and 8.7): one session above the work
  * sessions of a person. It can manage work, but it cannot do the work. It starts sessions, sends them messages, reads
  * their cards, their memory and their journals, and gets their reports.
  */
object Controller:

  /** The id of the controller in the root of a host (8.7): one controller for each user (8.1). */
  val id: SessionId = SessionId("controller")

  /** The mode of the controller (8.7): `Mode.controller` with no `Children^`, as a work session has no `rlm`. It reads
    * files, and it has no `Shell` and no `Files^`, so `write`, `edit` and `exec` do not compile (CODE_HARNESS.md, 7.3).
    */
  val mode: Mode = Mode.controller.copy(children = Access.None)

  /** The ceiling of the controller (8.1): its own mode and the mode of a work session, so a session that it starts gets
    * `WorkRuntime.workMode`.
    */
  val ceiling: Mode = Mode.local.copy(children = Access.None)

  /** The first instructions of the policy of the controller. An optimizer can change them. */
  val instructions: String =
    "Help the person manage work sessions, with Scala 3 code in a REPL. You cannot write files or run commands. To " +
      "do work in a folder, start a session with start(goal, scope, strategy), and tell the person its id. Use " +
      "sessions() to see the sessions, tell(session, message) to send a message to a session, observe(session, " +
      "question) to ask about a session, and zoom(session, ref) to read a part of its memory. A report of a session " +
      "comes to you as a message. At each step, give a short text and the code of one cell, or only a text. A step " +
      "with no code ends the turn, and its text is the reply to the person."

  /** The task of each turn of the controller of the person with the home `home`: the home. `instructions` are the
    * instructions of the policy of the controller (`WorkRuntime.activate`), so they come one time, in the system
    * message.
    */
  def task(home: Path): String = s"The home of the person: $home."

  /** The configuration of the controller of the person with the home `home` (8.1, 8.7): a chat turn with `limits.steps`
    * steps, the owner `User`, `mode`, `ceiling`, the memory tree, the scope `Home`, the guard `guard`, the preamble of
    * a session with no input fields, and the model `model` with the option bag `options` of its provider
    * (CLI_SCREEN.md, 14.4).
    */
  def config(home: Path, model: String, limits: HarnessLimits, options: Map[String, Json] = Map.empty): SessionConfig =
    SessionConfig(
      kind = TurnKind.Chat,
      steps = limits.steps,
      mode = mode,
      ceiling = ceiling,
      strategy = StrategyName.MemoryTree,
      scope = Scope.Home(home.toString),
      owner = Owner.User,
      guard = guard,
      preamble = preamble(summon[Data[String]].shape).getOrElse(""),
      model = ModelChoice(model, options),
      goal = None,
      env = Set.empty
    )

  /** The guard of the controller (8.7): `Ask` for a `start` whose scope is a `Directory`, because that session works in
    * a folder of the person. `mode` has no network, so the rules of 5.5 for `git push` do not apply.
    */
  val guard: Guard = Guard(Vector(VerdictRule(GuardRule("start", ArgMatch.Prefix("kind", "Directory")), Verdict.Ask)))

  /** The source of a message of `sender` in the journal of another session (8.2): `Source.Controller` from the
    * controller, and `Source.Child(sender)` from another session.
    */
  def messageSource(sender: SessionId): Source = if sender == id then Source.Controller else Source.Child(sender)
