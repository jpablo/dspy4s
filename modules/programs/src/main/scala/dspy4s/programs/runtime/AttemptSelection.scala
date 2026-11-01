package dspy4s.programs.runtime

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeDelta
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.AttemptCount
import dspy4s.programs.FailureCount
import dspy4s.programs.compose.{Mode, mode}
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.Prediction
import dspy4s.programs.contracts.ProgramCall

import scala.util.control.NonFatal

/** The shared best-of-`n` reducer behind [[dspy4s.programs.strategies.BestOfN]] and
  * [[dspy4s.programs.strategies.Refine]] (the `bestOf` operation of the program-composition algebra; see
  * `docs/refactor/algebra-2-program-composition.md`).
  *
  * Generic over the attempt result `A`. Each attempt runs in an isolated trace/history context; the winning attempt's
  * trace/history are propagated to the caller. The reducer keeps the highest-reward attempt, short-circuits at
  * `threshold`, and tolerates up to `failCount` failures.
  *
  * The reward also runs in an isolated context, and the reducer discards its delta. Thus the model calls of a reward
  * function leave no trace and no history (upstream `best_of_n.py` and `refine.py` run the reward under
  * `dspy.context(trace=[])`, with the note "Not including the trace of reward_fn"). The reward does not run with the
  * hint adapter of the attempt. The critic call of the feedback step stays outside the isolation, as upstream, so its
  * trace reaches the caller.
  *
  * The usage scope of the call is not isolated: the model calls of each attempt, of each reward, and of each feedback
  * count in the usage of the result of the caller (decision D9 of UPGRADE-3.2.1-to-3.4.0). The reducer does not change
  * the usage of the result that it keeps; `Module.apply` of the caller sets the total.
  *
  * The two combinators differ in the state carried between attempts:
  *
  *   - '''selectBest''' (= [[dspy4s.programs.strategies.BestOfN]]) passes no `feedback`: attempts vary only by
  *     `rolloutId` / `config`. Execution is still ordered because threshold short-circuiting and ties prefer earlier
  *     attempts.
  *   - '''feedback''' (sequential stream, = [[dspy4s.programs.strategies.Refine]]) passes a `feedback` hook: after a
  *     sub-threshold, non-last attempt it inspects that attempt (value + trace + score) and produces the context the
  *     NEXT attempt runs under (Refine's context has the `hint_`-injecting adapter), making the stream order-dependent.
  */
object AttemptSelection:

  /** The step after a sub-threshold attempt that is not the last. It gets the `(value, trace, score)` of the attempt,
    * and the base context of the loop as its `using` value. It gives the context of the next attempt.
    */
  private[programs] type Feedback[A] =
    (A, Vector[TraceEntry], Double) => RuntimeContext ?=> Either[DspyError, RuntimeContext]

  /** The attempt with the highest reward so far, with its trace and history. */
  private final case class Best[A](value: A, score: Double, delta: RuntimeDelta)

  /** Drive up to `n` attempts, keeping the highest-reward result and short-circuiting once `reward` reaches
    * `threshold`. `label` names the loop in the no-success error.
    *
    * @param runAttempt
    *   builds and runs attempt `idx` under the isolated [[RuntimeContext]] supplied here (the caller varies `rolloutId`
    *   / `config` by index).
    * @param reward
    *   scores a successful attempt, in an isolated context from the base context of the loop; a `Left` charges the
    *   failure budget and the reduction continues with the best-so-far retained (upstream parity: reward_fn exceptions
    *   count toward fail_count).
    * @param feedback
    *   optional inter-attempt hook ([[Feedback]]). The hook is AUXILIARY: a `Left` charges the failure budget and keeps
    *   the best-so-far (the prior context stays), as a module failure does. It is `None` for best-of-`n` with no
    *   carried feedback.
    */
  private[programs] def bestOf[A](
      n        : AttemptCount,
      threshold: Double,
      failCount: Option[FailureCount],
      label    : String
  )(
      runAttempt: Int => (RuntimeContext ?=> Either[DspyError, A]),
      reward    : A => Either[DspyError, Double],
      feedback  : Option[Feedback[A]] = None
  ): Either[DspyError, A] =
    val baseContext                  = RuntimeEnvironment.current
    var attemptContext               = baseContext
    var remainingFailures            = failCount.getOrElse(n)
    var best: Option[Best[A]]        = None
    var lastError: Option[DspyError] = None

    // A score must beat the score of `best`. With no `best`, it must beat negative infinity.
    def keepBest(value: A, score: Double, delta: RuntimeDelta): Unit =
      if score > best.fold(Double.NegativeInfinity)(_.score) then best = Some(Best(value, score, delta))

    var idx = 0
    while idx < n do
      val executed = RuntimeEnvironment.isolatedAttempt(attemptContext)(runAttempt(idx))
      val outcome  =
        for
          value  <- executed.value
          score  <- RuntimeEnvironment.isolatedAttempt(baseContext)(reward(value)).value
          _       = keepBest(value, score, executed.delta)
          reached = score >= threshold
          _      <- feedback match
                 case Some(step) if !reached && idx < n - 1 =>
                   step(value, executed.delta.trace, score)(using baseContext).map(next => attemptContext = next)
                 case _ => Right(())
        yield reached

      outcome match
        case Right(true)  => idx = n // short-circuit at threshold
        case Right(false) => idx += 1
        case Left(error)  =>
          // Each failure charges the failure budget: of the attempt, of the reward, and of the feedback. Upstream DSPy
          // catches reward_fn exceptions and continues, because a stop would discard the attempts of the budget and
          // the best-so-far.
          lastError = Some(error)
          if remainingFailures <= 0 then return Left(error)
          remainingFailures -= 1
          idx += 1

    best match
      case Some(kept) =>
        RuntimeEnvironment.propagate(kept.delta)
        Right(kept.value)
      case None => Left(lastError.getOrElse(RuntimeError(label, "No successful predictions were produced")))

  /** [[bestOf]] over `module`: each attempt runs `call` at temperature 1.0 with its own rollout id, from the rollout id
    * of `call`. `rewardFn` scores an attempt, and a thrown exception is a reward failure ([[guardedReward]]). `label`
    * names the loop in the errors.
    */
  private[programs] def sample[I, O](
      module   : Module[I, O],
      call     : ProgramCall[I],
      rewardFn : (I, Prediction[O]) => Double,
      n        : AttemptCount,
      threshold: Double,
      failCount: Option[FailureCount],
      label    : String,
      feedback : Option[Feedback[Prediction[O]]] = None
  ): Either[DspyError, Prediction[O]] =
    val rolloutStart = call.rolloutId.getOrElse(0)
    bestOf[Prediction[O]](n, threshold, failCount, label)(
      runAttempt = idx => module.mode(Mode.temperature(1.0d) ++ Mode.rolloutId(rolloutStart + idx))(call),
      reward = prediction => guardedReward(label)(rewardFn(call.input, prediction)),
      feedback = feedback
    )

  /** Evaluate a user reward function, lifting any thrown exception into the `Either` channel. */
  private[programs] def guardedReward(label: String)(score: => Double): Either[DspyError, Double] =
    try Right(score)
    catch
      case NonFatal(error) => Left(RuntimeError(
          label,
          s"Reward function failed: ${DspyError.messageOf(error)}"
        ))
