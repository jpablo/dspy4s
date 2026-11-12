package dspy4s.harness.host

import dspy4s.core.contracts.RuntimeContext
import dspy4s.harness.{Budget, Journal}
import dspy4s.harness.guest.{Llm, Scope, SessionCard, SessionRef, Sessions, StrategyName}
import dspy4s.harness.kernel.{Command, EffectId, RequestId, SessionConfig, SessionId}

import java.util.UUID

/** The handler of `Sessions^` of a session `caller` (8.2, 8.7). Each call that fails throws an exception with a plain
  * message, so the cell fails with it. The model of the controller reads the message, so an error of the host gives its
  * words (`HostError.words`) and not a Scala value. Each id of a `SessionRef` must be `SessionId.valid` and have a row
  * in the catalog, else the call fails before it reaches the host.
  *
  *   - `list` gives the card of each row of the catalog (`cardOf`), apart from the row of `caller`.
  *   - `tell` sends the message to the session with the source `Controller.messageSource(caller)` through
  *     `Peers.runAs`, so it returns after the receiver commits (8.6). The request id holds `caller` and the open `tell`
  *     effect of `journal`, so a second send after a crash commits nothing. A session that the catalog does not have,
  *     or whose row has ended, gives an error, and nothing opens.
  *   - `start` gives the new session a random UUID as its id, makes its scope (`Peers.make`), and opens it with the
  *     configuration of `configure` for that scope and the goal as its task, in one commit (`Peers.openWith`). The
  *     runtime gives `WorkRuntime.ownedConfig` as `configure`. A crash before the commit leaves the effect `Unknown`,
  *     and a second call makes another session. The request id of the task holds `caller` and the open `start` effect.
  *     It gives the ref of the new session.
  *   - `observe` gives the answer of `probe` about the journal of that session, with the budget of the current turn of
  *     `caller`, the link of the open `observe` effect, and the context of the runtime. It changes nothing in that
  *     session.
  *   - `zoom` gives the part `ref` of the memory of that session (`Peers.zoomInto`).
  */
final class HostSessions(
    peers    : Peers,
    caller   : SessionId,
    journal  : Journal,
    probe    : Probe,
    budget   : () => Budget,
    configure: (String, Scope, StrategyName) => SessionConfig
)(using RuntimeContext)
    extends Sessions:

  def list(): Vector[SessionCard] = peers.rows.filterNot(_.session == caller).map(HostSessions.cardOf)

  def tell(session: SessionRef, message: String): Unit =
    val row = rowOf("tell", session)
    row.status match
      case SessionStatus.Ended(_) => throw IllegalStateException(s"tell: the session ${session.id} has ended")
      case _                      => ()
    val send = Command.Send(HostSessions.requestOf("tell", caller, effect("tell")), message, Map.empty)
    peers.runAs(row.session, send, Controller.messageSource(caller)).left
      .foreach(error =>
        throw IllegalStateException(s"tell: the session ${session.id} did not take the message: ${error.words}")
      )

  def start(goal: String, scope: Scope, strategy: StrategyName): SessionRef =
    val id   = SessionId(UUID.randomUUID().toString)
    val made = peers.make(scope, id).fold(error => throw IllegalArgumentException(s"start: $error"), identity)
    val task = new Command.Send(HostSessions.requestOf("start", caller, effect("start")), goal, Map.empty)
    peers.openWith(id, configure(goal, made, strategy), task, Controller.messageSource(caller)).left
      .foreach(error => throw IllegalStateException(s"start: the session did not open: ${error.words}"))
    SessionRef(id.value)

  def observe(session: SessionRef, question: String)(using Llm): String =
    val row     = rowOf("observe", session)
    val journal = peers.journalOf(row.session).fold(error => throw IllegalStateException(s"observe: $error"), identity)
    probe.ask(row.session, journal, question, ProbeLink(caller, effect("observe")), budget())
      .fold(error => throw IllegalStateException(s"observe: $error"), identity)

  def zoom(session: SessionRef, ref: String): String =
    peers.zoomInto(rowOf("zoom", session).session, ref)
      .fold(error => throw IllegalArgumentException(s"zoom: $error"), identity)

  /** The row of `session` in the catalog. An id that is not valid, or that has no row, makes the call `op` fail. */
  private def rowOf(op: String, session: SessionRef): CatalogRow =
    if !SessionId.valid(session.id) then
      throw IllegalArgumentException(s"$op: ${session.id} is not the id of a session")
    peers.rows.find(_.session.value == session.id)
      .getOrElse(throw IllegalArgumentException(s"$op: there is no session ${session.id}"))

  /** The open effect of the call `op` in the journal of `caller`: the recorder committed its intent before the call. */
  private def effect(op: String): EffectId =
    journal.state.open.collectFirst { case (id, call) if call.op == op => id }
      .getOrElse(throw IllegalStateException(s"$op: the call has no open effect in the journal"))

object HostSessions:

  /** The card of a row of the catalog (8.7): the id, the goal or an empty text, the scope, the owner in words
    * (`Owner.words`), the parent, the status in words (`SessionStatus.words`), the last `seq`, the paths that the
    * session made, the number of its commands, and its head. The controller reads the card with a model, so it holds no
    * Scala value.
    */
  def cardOf(row: CatalogRow): SessionCard =
    SessionCard(
      SessionRef(row.session.value),
      row.goal.getOrElse(""),
      row.scope,
      row.owner.words,
      row.parent.map(parent => SessionRef(parent.value)),
      row.status.words,
      row.lastSeq,
      row.ledger.read.size,
      row.ledger.made,
      row.ledger.commands,
      row.ledger.head,
      row.ledger.clean
    )

  /** The request id of the operation `op` of `caller` in its effect `effect`: `op:<caller>:<cell>.<n>` (8.6). */
  def requestOf(op: String, caller: SessionId, effect: EffectId): RequestId =
    RequestId(s"$op:${caller.value}:${effect.cell}.${effect.n}")
