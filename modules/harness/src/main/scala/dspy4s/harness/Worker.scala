package dspy4s.harness

import dspy4s.harness.kernel.{CellOutcome, Command, Entry, Logged, Next, Rejection, SessionState, Source, TurnOutcome}
import dspy4s.harness.kernel.{closing, next}
import zio.blocks.schema.json.Json

import scala.annotation.tailrec

/** The reply of the policy for one step (CODE_HARNESS_DESIGN.md, 4.6): the thought, the text, and the code of a cell,
  * if any.
  */
final case class PolicyReply(reasoning: String, text: String, code: Option[String]) derives CanEqual

/** The result of one cell (4.6). `writes` holds the store writes of a cell that completed. A cell that did not complete
  * has no writes (6).
  */
final case class CellResult(output: String, outcome: CellOutcome, writes: Map[String, Json]) derives CanEqual

/** The effects of a turn (4.6). A test gives a script, and a harness gives its policy leaf and a REPL.
  *
  * A proposal for the review: the design gives `callPolicy` the state after `StepStarted`. The policy of a harness
  * reads the log of the journal, so `callPolicy` takes only `mustAnswer`.
  */
trait TurnEffects:
  def callPolicy(mustAnswer: Boolean): Either[String, PolicyReply]
  def runCell(cell         : Long, code: String): CellResult

/** A journal (4.6): the committed entries and their fold. `run` commits the entries that `decide` gives for a command.
  *
  * A proposal for the review: the design gives `run(command)` with no sender, and no `log`. The sender gives the source
  * of each entry through `sourceOf`, so the `Acted` of a step comes from the policy. A strategy reads the log (7).
  */
trait Journal:
  def state: SessionState
  def log: Vector[Logged]
  def run(command: Command, sender: Source): Either[Rejection, SessionState]

  /** A commit of the harness. */
  final def commit(entries: Entry*): Either[Rejection, SessionState] =
    run(Command.Commit(entries.toVector), Source.Harness)

/** Runs the open turn to its end (4.6), and gives the state at `Next.Wait`. The worker holds no decision: `next` says
  * what comes, and the journal holds each result.
  *
  *   - `CallPolicy`: `StepStarted`, then the `Acted` of the reply from the policy. The policy runs only when the
  *     journal accepts the `StepStarted`. A failed call ends the turn with `TurnEnded(Failed)`.
  *   - `RunCell`: the cell, then `EffectDone(Unknown)` for each effect that a stop left open, and `Observed`.
  *   - `End`: the entries of `closing`, and `TurnEnded`.
  *
  * An exception of `effects` goes on to the caller, and the turn stays open, as after a crash. The next `Activate`
  * repairs it (4.4).
  *
  * A stop can come between the read of the state and a commit, so the journal can refuse a `StepStarted` or an `Acted`.
  * Then the next round reads the stop, and `next` ends the turn. The journal must accept the entries after a cell and
  * at the end of a turn. When it refuses them, `drive` throws, because a new round would run the cell again.
  */
@tailrec
def drive(journal: Journal, effects: TurnEffects): SessionState =
  val current = journal.state
  next(current) match
    case Next.Wait                         => current
    case Next.CallPolicy(step, mustAnswer) =>
      journal.commit(Entry.StepStarted(step)).foreach { _ =>
        effects.callPolicy(mustAnswer) match
          case Right(reply) => journal.run(
              Command.Commit(Vector(Entry.Acted(reply.reasoning, reply.text, reply.code))),
              Source.Policy
            ): Unit
          case Left(error) => journal.commit(Entry.TurnEnded(TurnOutcome.Failed(error))): Unit
      }
      drive(journal, effects)
    case Next.RunCell(cell, code) =>
      val result = effects.runCell(cell, code)
      // A stop can leave effects open. The outcome of the cell comes from the cell, not from `closing`.
      val open = closing(journal.state).collect { case done: Entry.EffectDone => done }
      accepted(journal.commit((open :+ Entry.Observed(result.output, result.outcome, result.writes))*), "the cell")
      drive(journal, effects)
    case Next.End(outcome) =>
      accepted(journal.commit((closing(journal.state) :+ Entry.TurnEnded(outcome))*), "the end of the turn")
      drive(journal, effects)

/** Throws when the journal refused entries that the worker must commit. */
private def accepted(result: Either[Rejection, SessionState], what: String): Unit =
  result.left.foreach(rejection => throw IllegalStateException(s"the journal refused the entries of $what: $rejection"))
