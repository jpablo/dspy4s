package dspy4s.harness.kernel

/** The reports of a session to its owner (8.6). A report is a pure function of the journal, so a second send has the
  * same request id and the same text.
  *
  * An `Input` from `owner` opens a debt at the first step or stop that sees it. While the debt is open, each
  * `TurnEnded` and `Ended` gives one `Send`. `Submitted`, `Exhausted`, `Aborted` and `Ended` close the debt, and
  * `Replied` keeps it open. The request id holds the session and the `seq` of that entry.
  *
  * For the review: the design gives `reports(session, journal)`, with no owner. The function must know which sender is
  * the owner, and `Source` has no case for a parent session. So this signature takes the source of the owner, as the
  * sketch of section 12 does.
  */
def reports(session: SessionId, owner: Source, journal: Vector[Logged]): Vector[Command.Send] = ???

/** The reports that the owner does not have yet: the requests that are not in the state of the owner (8.6). The report
  * pass sends them.
  */
def missing(
    session   : SessionId,
    owner     : Source,
    journal   : Vector[Logged],
    ownerState: SessionState
): Vector[Command.Send] =
  ???
