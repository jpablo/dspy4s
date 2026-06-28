package dspy4s.programs.runtime

import dspy4s.core.contracts.AdapterRef
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.runtime.RuntimeEnvironment

import scala.util.control.NonFatal

/** The shared best-of-`n` reducer behind [[dspy4s.programs.BestOfN]] and [[dspy4s.programs.Refine]] (the
  * `bestOf` operation of the program-composition algebra; see `docs/refactor/algebra-2-program-composition.md`).
  *
  * Generic over the attempt result `A`. Each attempt runs in an isolated trace/history context; the winning
  * attempt's trace/history are propagated to the caller. The reducer keeps the highest-reward attempt,
  * short-circuits at `threshold`, and tolerates up to `failCount` failures.
  *
  * The two combinators differ only in how the attempt stream is generated:
  *
  *   - '''selectBest''' (independent samples, = [[dspy4s.programs.BestOfN]]) passes no `feedback`: attempts vary
  *     only by `rolloutId` / `config`, so the stream is order-independent.
  *   - '''feedback''' (sequential stream, = [[dspy4s.programs.Refine]]) passes a `feedback` hook: after a
  *     sub-threshold, non-last attempt it inspects that attempt (value + trace + score) and produces the
  *     adapter override the NEXT attempt runs under (Refine's `hint_`-injecting adapter), making the stream
  *     order-dependent. */
object AttemptSelection:

  /** Drive up to `n` attempts, keeping the highest-reward result and short-circuiting once `reward` reaches
    * `threshold`. `label` names the loop in the no-success error.
    *
    * @param runAttempt builds and runs attempt `idx` under the isolated [[RuntimeContext]] supplied here (the
    *                   caller varies `rolloutId` / `config` by index).
    * @param reward     scores a successful attempt; a `Left` aborts the whole reduction.
    * @param feedback   optional inter-attempt hook. After a sub-threshold attempt that is not the last, it is
    *                   given the attempt's `(value, trace, score)` and returns the `Option[AdapterRef]` the
    *                   next attempt's isolated context runs under (`None` keeps the base adapter). The hook is
    *                   AUXILIARY: a `Left` charges the failure budget and keeps the best-so-far (the prior
    *                   carried adapter is retained), mirroring the module-failure path. Omitted (`None`) for
    *                   independent best-of-`n`. */
  private[programs] def bestOf[A](
      n: Int,
      threshold: Double,
      failCount: Option[Int],
      label: String
  )(
      runAttempt: Int => (RuntimeContext ?=> Either[DspyError, A]),
      reward: A => Either[DspyError, Double],
      feedback: Option[(A, Vector[TraceEntry], Double) => Either[DspyError, Option[AdapterRef]]] = None
  ): Either[DspyError, A] =
    val baseContext       = RuntimeEnvironment.current
    var remainingFailures = failCount.getOrElse(n)
    var bestReward        = Double.NegativeInfinity
    var best: Option[A]   = None
    var bestTrace         = Vector.empty[TraceEntry]
    var bestHistory       = Vector.empty[HistoryEntry]
    var lastError: Option[DspyError] = None
    // Adapter override carried from a sub-threshold attempt's feedback into the next attempt (None until the
    // first feedback fires; for Refine this is the hint-injecting adapter derived from the generated advice).
    var carriedAdapter: Option[AdapterRef] = None

    var idx = 0
    while idx < n do
      val (attemptResult, trace, history) =
        RuntimeEnvironment.isolatedAttempt(baseContext, carriedAdapter)(runAttempt(idx))

      attemptResult match
        case Right(value) =>
          reward(value) match
            case Left(error) => return Left(error)
            case Right(score) =>
              if score > bestReward then
                bestReward  = score
                best        = Some(value)
                bestTrace   = trace
                bestHistory = history

              if score >= threshold then
                idx = n // short-circuit at threshold
              else if idx == n - 1 then
                idx += 1 // last attempt; no feedback to generate
              else
                // Produce the next attempt's adapter override from this attempt's feedback. Auxiliary: a
                // failure charges the failure budget and keeps `best`, retaining the prior carried adapter.
                feedback match
                  case None => ()
                  case Some(generate) =>
                    generate(value, trace, score) match
                      case Right(nextAdapter) => carriedAdapter = nextAdapter
                      case Left(error) =>
                        lastError = Some(error)
                        if remainingFailures <= 0 then return Left(error)
                        remainingFailures -= 1
                idx += 1

        case Left(error) =>
          lastError = Some(error)
          if remainingFailures <= 0 then return Left(error)
          remainingFailures -= 1
          idx += 1

    best match
      case Some(value) =>
        RuntimeEnvironment.propagateAttempt(bestTrace, bestHistory)
        Right(value)
      case None =>
        Left(lastError.getOrElse(RuntimeError(label, "No successful predictions were produced")))

  /** Evaluate a user reward function, lifting any thrown exception into the `Either` channel. */
  private[programs] def guardedReward(label: String)(score: => Double): Either[DspyError, Double] =
    try Right(score)
    catch
      case NonFatal(error) =>
        Left(RuntimeError(
          label,
          s"Reward function failed: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
        ))
