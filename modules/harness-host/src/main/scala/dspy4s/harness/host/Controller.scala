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
      "comes to you as a message. A session stays open after its turn ends. To give a session more work or a " +
      "change, use tell(session, message). Start a new session only for new work. At each step, give a short text " +
      "and the code of one cell, or only a text. A step with no code ends the turn, and its text is the reply to the " +
      "person."

  /** The task of each turn of the controller of the person with the home `home`: the home, then how the person answers
    * a call that needs an approval, then `commands`. `instructions` are the instructions of the policy of the
    * controller (`WorkRuntime.activate`), so they come one time, in the system message.
    *
    * The person answers an approval in the terminal client: `/allow` grants the call, and a message starts a new turn,
    * which clears the pending calls. These are facts of the client, and an optimizer can change `instructions`, so they
    * go in the task. In the live session of 2026-11-15, the controller told the person to approve a call in a "host
    * UI", and the question of the person refused the call. The task is the same at each turn of an activation, so the
    * prompt cache stays valid.
    *
    * In the live session of 2026-11-16, the model told the person to type `/allow 1 turn` for the call 71. The model
    * does not know the number of a call, and `/allow` with no number allows each call that waits, so the task asks for
    * no number.
    */
  def task(home: Path): String =
    s"The home of the person: $home. When a call needs an approval, end the turn and tell the person to type " +
      s"/allow, with no number. A message of the person instead of /allow refuses the call.\n\n$commands"

  /** The commands of the terminal client, in words for the model: what the person can type, and what each command does.
    * Only the person types a command, so the model tells the person which command to type. In the live session of
    * 2026-11-16, the person asked how to change the reasoning effort. The prompt named no command, so the model
    * described config files that do not exist.
    *
    * harness-cli depends on harness-host, so this text cannot read `commandHelp` of the client. A law of the client
    * (`ViewLaws`) checks that the text names each command of `commandHelp`. The text is a constant, so the task stays
    * the same during an activation.
    */
  val commands: String =
    "The person types commands in the terminal client. Only the person types them. When the person asks how to do a " +
      "thing in the client, tell the person which command to type. The client shows one session: the controller or " +
      "a work session. The commands:\n" +
      "/stop stops the turn. /stopall stops the turn and each job of the turn.\n" +
      "/allow [N] [once|turn|session] allows each call that waits for an approval, or only the call N of /status. " +
      "The last word sets how long the approval stays. The default is once.\n" +
      "/jobs lists the jobs of the session. /stopjob ID stops the job ID of /jobs.\n" +
      "/status shows the folder, the turn, the calls that wait for an approval and the jobs.\n" +
      "/reasoning off|line|short|full sets how much of the reasoning of a step the client shows.\n" +
      "/effort [LEVEL] shows the reasoning effort and its levels, or sets the effort of the next steps.\n" +
      "/show ID shows the cell ID or the call ID of the session in full.\n" +
      "/wait SECONDS waits before the next line of a script.\n" +
      "/help shows the commands.\n" +
      "/sessions lists the sessions. /open ID shows the session ID. /reopen ID opens again the session ID, which " +
      "has ended. /controller shows the controller again.\n" +
      "/close ends the session as parked, and shows and runs its release plan. /close merged, /close abandoned " +
      "REASON and /close replaced ID end it with another outcome. The controller never ends.\n" +
      "/quit ends the client. The sessions stay open."

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
