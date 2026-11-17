package dspy4s.harness

import dspy4s.core.contracts.{FieldSpec, SignatureLayout}
import dspy4s.harness.kernel.{Entry, GoalCheck, Logged, Source}
import dspy4s.signatures.Signature
import zio.blocks.schema.Schema

/** The input of the `goal` leaf (CODE_HARNESS_DESIGN.md, 8.5): the goal, the text of the last turn (`turnText`), and
  * the blocker of the last check, or an empty text. A signature needs one name for each field, so the input of the last
  * blocker has the name `lastBlocker`.
  */
final case class GoalQuestion(goal: String, turn: String, lastBlocker: String) derives Schema

/** The output of the `goal` leaf (8.5): `met`, the reason, the blocker (an empty text when nothing stops the work), and
  * `same`, which is true when the blocker is the blocker of the last check.
  */
final case class GoalAnswer(met: Boolean, reason: String, blocker: String, same: Boolean) derives Schema:

  /** The answer as the kernel keeps it in `GoalChecked`. */
  def check: GoalCheck = GoalCheck(met, reason, blocker, same)

/** The `goal` leaf of a work session (8.5): one `Predict` call after each turn of a goal loop. */
object GoalLeaf:

  /** The instructions of the leaf. A claim of the session is not proof, so the leaf looks for the output that shows it.
    */
  val instructions: String =
    "Say if the goal of a work session is met now. Read the goal and the text of the last turn of the session: its " +
      "messages, the text and the code of its steps, the output of its cells, and its outcome. A claim of the session " +
      "is not proof: look for the output that shows it. Give the reason in one or two sentences. Give the blocker: " +
      "what stops the work now, in a few words, or an empty text when the work can go on. Say if the blocker is the " +
      "same as the earlier blocker."

  /** The description of each field of the leaf, by name. The system message shows each one after the name of its field.
    */
  val fields: Map[String, String] = Map(
    "goal"        -> "the goal of the session",
    "turn"        -> "the text of the last turn: its messages, its steps, the code and the output of its cells, its outcome",
    "lastBlocker" -> "the blocker of the earlier check, or an empty text",
    "blocker"     -> "what stops the work now, in a few words, or an empty text",
    "met"         -> "true only when the output of the turn shows that the goal is met now",
    "reason"      -> "why the goal is met or not met, in one or two sentences",
    "same"        -> "true when the blocker is the same as the earlier blocker"
  )

  /** The signature of the leaf, with `instructions` and the descriptions of `fields`. */
  def signature: Signature[GoalQuestion, GoalAnswer] =
    val base                                                   = Signature.derived[GoalQuestion, GoalAnswer]("goal", instructions)
    val layout                                                 = base.layout
    def described(specs: Vector[FieldSpec]): Vector[FieldSpec] =
      specs.map(field => fields.get(field.name).fold(field)(text => field.copy(description = Some(text))))
    SignatureLayout
      .create(layout.name, described(layout.inputFields), described(layout.outputFields), layout.instructions)
      .fold(
        error => throw IllegalStateException(s"the goal signature is not valid: ${error.message}"),
        made => base.copy(layout = made)
      )

  /** The most characters of the text of the last turn in a question. */
  val TurnChars: Int = 12_000

  /** The blocker of a check that failed. Its text does not change, so three failed checks end the loop as blocked. */
  val FailedBlocker: String = "the goal check fails"

  /** The answer of a check that failed with `error` (8.5): not met, with the error as the reason. The blocker is
    * `FailedBlocker`, and it is the same blocker when `blocker`, the last one, is `FailedBlocker` too.
    */
  def failed(error: String, blocker: String): GoalCheck =
    GoalCheck(met = false, s"the goal check failed: $error", FailedBlocker, same = blocker == FailedBlocker)

/** The text of the last turn of `log` (8.5): each message after the turn before it, the text and the code of each step,
  * the output of each cell with its outcome, and the outcome of the turn. A text above `max` characters keeps its end,
  * after a line that says that the start is cut. A log with no turn gives the messages that wait.
  */
def turnText(log: Vector[Logged], max: Int = GoalLeaf.TurnChars): String =
  val start = log.lastIndexWhere(_.entry == Entry.TurnStarted)
  // The turn before the last one ends here, so each message after it belongs to the last turn.
  val from = log.lastIndexWhere(
    _.entry match
      case Entry.TurnEnded(_) => true
      case _                  => false
    ,
    start - 1
  )
  val text = log.drop(from + 1).flatMap(turnLines).mkString("\n")
  if text.length <= max then text else s"$TurnCut\n${textSuffix(text, max)}"

/** The first line of a text of `turnText` with a cut start. */
private val TurnCut = "[The start of the turn is cut.]"

/** The lines of one entry of a turn in `turnText`. Other entries give no line. */
private def turnLines(logged: Logged): Vector[String] =
  logged.entry match
    case Entry.Input(_, text, _)    => Vector(s"A message from ${senderWords(logged.meta.source)}:\n$text")
    case Entry.Acted(_, text, code) => Vector(text).filter(_.nonEmpty).map(t => s"A step: $t") ++
        code.map(c => s"The code of a cell:\n$c")
    case Entry.Observed(output, outcome, _) => Vector(s"The output of the cell (${outcome.words}):\n$output")
    case Entry.StopRequested(reason)        => Vector(s"A stop: $reason")
    case Entry.TurnEnded(outcome)           => Vector(outcome.report)
    case _                                  => Vector.empty

/** The sender of a message in words, for example `the person` or `the session s2`. */
private def senderWords(source: Source): String =
  source match
    case Source.User               => "the person"
    case Source.Controller         => "the controller"
    case Source.Harness            => "the harness"
    case Source.Policy             => "the model"
    case Source.Child(session)     => s"the session ${session.value}"
    case Source.Parent(session, _) => s"the cell of the session ${session.value}"
