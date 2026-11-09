package dspy4s.harness.kernel

import dspy4s.harness.guest.Mode
import org.scalacheck.Gen
import zio.blocks.schema.json.Json

/** One random run of the kernel, as in the simulation of section 12. A host with no threads sends random commands and
  * random worker commits through `decide`, commits each result, and sometimes crashes or loses power.
  *
  * @param journal
  *   the journal after the run
  * @param live
  *   the state that the host kept with `evolve` of each entry that it committed
  * @param durable
  *   the length of the journal at the last flush: after a power loss, only `journal.take(durable)` stays
  * @param changed
  *   the guest API calls whose effect ran, so that the world changed
  * @param answered
  *   the requests of the `Send` commands whose sender got an answer
  */
final case class Run(
    journal : Vector[Logged],
    live    : SessionState,
    durable : Int,
    changed : Vector[EffectId],
    answered: Vector[RequestId]
)

/** The generators of the laws (12). */
object Schedules:

  /** Random runs with crashes, stops, late messages, grants, jobs and power losses. The owners of the sessions are the
    * user, a session, a cell and an optimizer, and the guards have rules that overlap.
    */
  def schedules: Gen[Run] = ???

  /** The states at the end of random runs. */
  def states: Gen[SessionState] = ???

  /** Random commands from a client, a worker and a person. */
  def commands: Gen[Command] = ???

  /** Random guards with rules that overlap, so that two rules can match one call. */
  def guards: Gen[Guard] = ???

  /** Random calls of the guest API: an operation and its arguments. */
  def calls: Gen[(String, Json)] = ???

  /** The problems of a journal against the language of 4.1. It is empty for a word of the language. */
  def grammar(journal: Vector[Logged]): Vector[String] = ???

/** Small values for the tests of single cases. */
object Fixtures:
  val versions: Versions = Versions("3.10.2-RC1", "1")
  val meta: Meta         = Meta(0L, Source.User, Taint.Trusted, required = true)
  val session: SessionId = SessionId("s1")
  val args: Json         = Json.Object(("command", Json.String("git push origin main")))

  def config(
      kind : TurnKind = TurnKind.Chat,
      steps: Int      = 3,
      owner: Owner    = Owner.User,
      guard: Guard    = Guard.allowAll
  ): SessionConfig =
    SessionConfig(
      kind,
      steps,
      Mode.full,
      Mode.full,
      StrategyName.Window,
      Scope.Scratch("/tmp/session"),
      owner,
      guard,
      "",
      ModelChoice("model", Map.empty),
      None,
      Set.empty
    )

  def activated(c: SessionConfig = config()): Entry = Entry.Activated(versions, c)

  def input(request: String, source: Source = Source.User): (Source, Entry) =
    source -> Entry.Input(RequestId(request), s"message $request", Map.empty)

  /** A journal with these entries in this order, each from its sender. */
  def journalFrom(entries: (Source, Entry)*): Vector[Logged] =
    entries.toVector.zipWithIndex.map { case ((source, entry), i) =>
      Logged(i.toLong, meta.copy(source = source), entry)
    }

  /** A journal with these entries in this order, all from the user. */
  def journal(entries: Entry*): Vector[Logged] = journalFrom(entries.map(Source.User -> _)*)

  /** The journal `start` with these entries at its end. */
  def append(start: Vector[Logged], entries: Vector[Entry]): Vector[Logged] =
    start ++ entries.zipWithIndex.map((entry, i) => Logged((start.size + i).toLong, meta, entry))

  /** The state after the commit of these entries. */
  def commit(s: SessionState, entries: Vector[Entry]): SessionState =
    entries.foldLeft(s)((now, entry) => evolve(now, Logged(now.nextSeq, meta, entry)))

  /** The state after a command that `decide` accepts. */
  def run(s: SessionState, command: Command): SessionState =
    decide(command, s) match
      case Right(entries)  => commit(s, entries)
      case Left(rejection) => throw AssertionError(s"decide refused $command: $rejection")

  /** The entries of an active session with an open turn before its first step. One input waits. */
  def turnEntries(c: SessionConfig = config()): Vector[Entry] =
    Vector(activated(c), Entry.Input(RequestId("r1"), "hi", Map.empty), Entry.TurnStarted)

  /** The entries of a session in a cell. The `Acted` entry has the `seq` 4, so the cell is 4. */
  def cellEntries(c: SessionConfig = config()): Vector[Entry] =
    turnEntries(c) ++ Vector(Entry.StepStarted(0), Entry.Acted("", "", Some("1 + 1")))

  val cell: Long = 4L

  def idle: SessionState   = state(journal(activated()))
  def inTurn: SessionState = state(journal(turnEntries()*))
  def inCell: SessionState = state(journal(cellEntries()*))
  def ended: SessionState  = state(journal(activated(), Entry.Ended(SessionOutcome.Parked, "done")))
