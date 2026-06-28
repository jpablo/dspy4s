package dspy4s.programs.runtime

import dspy4s.core.contracts.DspyError

import scala.annotation.tailrec

/** The bounded agentic-iteration primitive shared by `ReAct` / `CodeAct` / `RLM` / `ProgramOfThought` (the
  * `agentLoop` / `retryUntil` core of Algebra 2; see `docs/refactor/algebra-2-program-composition.md`).
  *
  * It captures only the control flow the four agents genuinely share: iterate up to `maxIterations`, carrying a
  * state `St`; each step either continues with a new state or finishes with a terminal result `R`; if the budget
  * is exhausted, a terminal `onExhausted` produces `R`. It deliberately does NOT impose a common
  * action/observation/classify vocabulary — code-truth shows the per-step semantics (tool dispatch vs interpreter
  * vs sub-LM, and where the "done" signal comes from) are irreducibly different, so each agent keeps its own
  * `step` closure. The two carriers used in practice:
  *
  *   - `St = Vector[entry]`, `R = Vector[entry]`, `onExhausted = Right(_)` — a trajectory the loop returns for a
  *     downstream extractor (ReAct / CodeAct, via [[TrajectoryAgent]]).
  *   - `R = Prediction[O]` with the result produced inside the loop (RLM's SUBMIT; PoT's first success), and an
  *     `onExhausted` that runs a fallback (RLM extract) or surfaces the last error (PoT). */
object AgentLoop:

  /** The outcome of one iteration: keep going with an updated state, or finish with a terminal result. */
  enum Step[+St, +R]:
    case Continue(state: St)
    case Done(result: R)

  /** Drive `step` from `state` for up to `maxIterations` iterations (0-indexed). Returns the first `Done`
    * result, the `onExhausted` terminal if the budget runs out, or the first `Left` a step raises. */
  @tailrec
  def run[St, R](state: St, iteration: Int, maxIterations: Int)(
      onExhausted: St => Either[DspyError, R]
  )(
      step: (St, Int) => Either[DspyError, Step[St, R]]
  ): Either[DspyError, R] =
    if iteration >= maxIterations then onExhausted(state)
    else
      step(state, iteration) match
        case Left(error)                 => Left(error)
        case Right(Step.Done(result))    => Right(result)
        case Right(Step.Continue(next))  => run(next, iteration + 1, maxIterations)(onExhausted)(step)
