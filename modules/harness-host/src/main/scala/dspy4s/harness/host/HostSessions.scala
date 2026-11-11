package dspy4s.harness.host

import dspy4s.core.contracts.RuntimeContext
import dspy4s.harness.{Budget, HarnessLimits, Journal}
import dspy4s.harness.guest.{Llm, Scope, SessionCard, SessionRef, Sessions, StrategyName}
import dspy4s.harness.kernel.{EffectId, RequestId, SessionConfig, SessionId}

import scala.annotation.nowarn

/** The handler of `Sessions^` of a session `caller` with the configuration `config` (8.2, 8.7). Each call that fails
  * throws an exception with a plain message, so the cell fails with it. Each id of a `SessionRef` must be
  * `SessionId.valid` and have a row in the catalog, else the call fails before it reaches the host.
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
@nowarn("msg=unused")
final class HostSessions(
    peers    : Peers,
    caller   : SessionId,
    config   : SessionConfig,
    journal  : Journal,
    probe    : Probe,
    budget   : () => Budget,
    limits   : HarnessLimits,
    configure: (String, Scope, StrategyName) => SessionConfig
)(using RuntimeContext)
    extends Sessions:

  def list(): Vector[SessionCard] = ???

  def tell(session: SessionRef, message: String): Unit = ???

  def start(goal: String, scope: Scope, strategy: StrategyName): SessionRef = ???

  def observe(session: SessionRef, question: String)(using Llm): String = ???

  def zoom(session: SessionRef, ref: String): String = ???

object HostSessions:

  /** The card of a row of the catalog (8.7): the id, the goal or an empty text, the scope, the owner and the status in
    * words, the parent, the last `seq`, the paths that the session made, the number of its commands, and its head.
    */
  def cardOf(row: CatalogRow): SessionCard = ???

  /** The request id of the operation `op` of `caller` in its effect `effect`: `op:<caller>:<cell>.<n>` (8.6). */
  def requestOf(op: String, caller: SessionId, effect: EffectId): RequestId = ???
