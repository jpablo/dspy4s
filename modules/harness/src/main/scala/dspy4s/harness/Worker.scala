package dspy4s.harness

import dspy4s.harness.kernel.{CellOutcome, Command, Entry, Logged, Rejection, SessionState, Source}
import zio.blocks.schema.json.Json

/** The reply of the policy for one step (CODE_HARNESS_DESIGN.md, 4.6): the thought, the text, and the code of a cell,
  * if any.
  */
final case class PolicyReply(reasoning: String, text: String, code: Option[String]) derives CanEqual

/** The result of one cell (4.6). `writes` holds the store writes of a cell that completed. A cell that did not complete
  * has no writes (6).
  */
final case class CellResult(output: String, outcome: CellOutcome, writes: Map[String, Json]) derives CanEqual

/** The effects of a turn (4.6). A test gives a script, and a harness gives its policy leaf and a REPL. */
trait TurnEffects:
  def callPolicy(state: SessionState, mustAnswer: Boolean): Either[String, PolicyReply]
  def runCell(cell    : Long, code              : String): CellResult

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
  *   - `CallPolicy`: `StepStarted`, then the `Acted` of the reply from the policy. A failed call ends the turn with
  *     `TurnEnded(Failed)`.
  *   - `RunCell`: the cell, then `EffectDone(Unknown)` for each effect that a stop left open, and `Observed`.
  *   - `End`: the entries of `closing`, and `TurnEnded`.
  *
  * An exception of `effects` goes on to the caller, and the turn stays open, as after a crash. The next `Activate`
  * repairs it (4.4).
  */
def drive(journal: Journal, effects: TurnEffects): SessionState = ???
