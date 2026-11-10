package dspy4s.harness.kernel

/** The next piece of work of a session (4.3). */
enum Next derives CanEqual:

  /** No open turn, or an effect is in progress. */
  case Wait

  case CallPolicy(step: Int, mustAnswer: Boolean)

  case RunCell(cell: Long, code: String)

  case End(outcome: TurnOutcome)

/** What comes next, from the state alone (4.3). So a session is an unfold of work and a fold of results. */
def next(s: SessionState): Next =
  if s.position == Position.NoTurn then Next.Wait
  else if s.stopping then Next.End(TurnOutcome.Aborted)
  else
    val limit = s.config.steps
    s.position match
      case Position.BeforeStep(i) if i < limit                                    => Next.CallPolicy(i, mustAnswer = false)
      case Position.BeforeStep(i) if i == limit && s.config.kind == TurnKind.Chat =>
        Next.CallPolicy(i, mustAnswer = true)
      case Position.BeforeStep(_)                 => Next.End(TurnOutcome.Exhausted)
      case Position.InCell(i, _, _) if i >= limit => Next.End(TurnOutcome.Exhausted)
      case Position.InCell(_, cell, code)         => Next.RunCell(cell, code)
      case Position.Answered(text)                => Next.End(TurnOutcome.Replied(text))
      case Position.Finished(value)               => Next.End(TurnOutcome.Submitted(value))
      case Position.InPolicy(_) | Position.NoTurn => Next.Wait
