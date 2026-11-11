package dspy4s.harness.host

import dspy4s.harness.kernel.{Command, Logged, SessionId, SessionState}

/** The report pass (CODE_HARNESS_DESIGN.md, 8.6 and 8.7). A report is a pure function of the journal of a session
  * (`missing`), so a second send has the same request id, and the owner commits it once.
  *
  * The live path: after a `TurnEnded` or an `Ended` of a session whose owner is a session, the host posts the missing
  * reports to the owner, if the owner is open in this host. The pass covers the reports that a crash or a closed owner
  * cut: `LocalHost.reportTo` at the open of an owner, and `repairable` at the start of a host. These functions are
  * pure, and the host runs their sends.
  */
object ReportPass:

  /** The reports that `owner`, in the state `ownerState`, does not have (`missing` with the source
    * `Controller.messageSource(owner)`), for each row of `rows` whose owner is `owner`, in the order of the rows and of
    * `seq`. Each report comes with the session that sends it. A session whose journal does not read gives none, so one
    * bad file does not stop the reports of the others.
    */
  def owed(
      owner     : SessionId,
      ownerState: SessionState,
      rows      : Vector[CatalogRow],
      journalOf : SessionId => Either[String, Vector[Logged]]
  ): Vector[(SessionId, Command.Send)] = ???

  /** The sessions of `rows` with a session as the owner and an open turn (`InTurn` or `Stopping`): the sessions that
    * the start of a host opens, so the repair commits `TurnEnded(Crashed)` (4.4).
    */
  def repairable(rows: Vector[CatalogRow]): Vector[SessionId] = ???
