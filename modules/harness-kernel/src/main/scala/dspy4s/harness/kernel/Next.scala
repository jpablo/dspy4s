package dspy4s.harness.kernel

/** The next piece of work of a session (4.3). */
enum Next derives CanEqual:

  /** No open turn, or an effect is in progress. */
  case Wait

  case CallPolicy(step: Int, mustAnswer: Boolean)

  case RunCell(cell: Long, code: String)

  case End(outcome: TurnOutcome)

/** What comes next, from the state alone (4.3). So a session is an unfold of work and a fold of results. */
def next(s: SessionState): Next = ???
