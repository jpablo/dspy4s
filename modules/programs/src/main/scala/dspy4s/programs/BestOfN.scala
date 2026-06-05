package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.updated
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.Prediction
import zio.blocks.schema.DynamicValue

import scala.util.control.NonFatal

/** Typed `BestOfN`: runs an inner typed program up to `n` times and keeps the highest-reward `Prediction[O]`,
  * short-circuiting once `rewardFn` reaches `threshold`. Output-preserving — it returns the inner program's `O`
  * unchanged (a `Module[TypedCall[I], Prediction[O]]`), so it composes around any typed program (`Predict`,
  * `ChainOfThought`, …). The repeated samples are made distinct by a per-attempt `rolloutId` (cache-busting);
  * `failCount` bounds tolerated failures before giving up (defaults to `n`).
  *
  * `callInputs` is empty: a generic wrapper has no `Signature` to encode `I` for its own trace entry, so the
  * meaningful inputs live on the nested inner program's event. The best-of-`n` selection loop lives in
  * [[BestOfN.selectBest]] (generic over the attempt result). */
final case class BestOfN[I, O](
    module: Module[TypedCall[I], Prediction[O]],
    n: Int,
    rewardFn: (I, Prediction[O]) => Double,
    threshold: Double,
    failCount: Option[Int] = None
) extends Module[TypedCall[I], Prediction[O]]:
  require(n > 0, "n must be greater than 0")

  override val moduleName: String = "best_of_n"

  override protected def callInputs(call: TypedCall[I]): DynamicValue.Record = DynamicValue.Record.empty
  override protected def callTraceEnabled(call: TypedCall[I]): Boolean       = call.traceEnabled
  override protected def tracePayload(prediction: Prediction[O]): DynamicValue.Record = prediction.raw.values

  override protected def forward(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    val rolloutStart = call.rolloutId.getOrElse(0)
    BestOfN.selectBest[Prediction[O]](n, threshold, failCount, moduleName)(
      runAttempt = idx =>
        module.apply(call.copy(
          rolloutId = Some(rolloutStart + idx),                                  // framework cache-busting selector
          config    = call.config.updated("temperature", DynamicValues.fromAny(1.0d))  // provider knob
        )),
      reward = prediction => BestOfN.guardedReward(moduleName)(rewardFn(call.input, prediction))
    )

  /** Convenience entry mirroring the typed caller signature; builds a [[TypedCall]] and dispatches through the
    * wrapped [[apply]]. */
  def apply(
      input: I,
      config: DynamicValue.Record = DynamicValue.Record.empty,
      traceEnabled: Boolean = true
  )(using RuntimeContext): Either[DspyError, Prediction[O]] =
    apply(TypedCall(input, config, traceEnabled))

object BestOfN:

  /** The best-of-`n` selection loop, generic over the attempt result `A` (a `Prediction[O]` for [[BestOfN]]).
    * Each attempt runs in an isolated trace/history context; the winning attempt's trace/history are propagated
    * to the caller's context. Picks the highest-reward attempt, short-circuits at `threshold`, and tolerates up
    * to `failCount` failures.
    *
    * `runAttempt` builds and runs attempt `idx` (the caller varies `rolloutId` / `config` by index) under the
    * isolated `RuntimeContext` supplied here; `reward` scores a successful attempt. */
  private[programs] def selectBest[A](
      n: Int,
      threshold: Double,
      failCount: Option[Int],
      label: String
  )(
      runAttempt: Int => (RuntimeContext ?=> Either[DspyError, A]),
      reward: A => Either[DspyError, Double]
  ): Either[DspyError, A] =
    val baseContext       = RuntimeEnvironment.current
    var remainingFailures = failCount.getOrElse(n)
    var bestReward        = Double.NegativeInfinity
    var best: Option[A]   = None
    var bestTrace         = Vector.empty[TraceEntry]
    var bestHistory       = Vector.empty[HistoryEntry]
    var lastError: Option[DspyError] = None

    var idx = 0
    while idx < n do
      val isolated = baseContext.copy(trace = Vector.empty, history = Vector.empty)
      val (attemptResult, trace, history) = RuntimeEnvironment.withContext(isolated) {
        given RuntimeContext = RuntimeEnvironment.current
        val result  = runAttempt(idx)
        val current = RuntimeEnvironment.current
        (result, current.trace, current.history)
      }

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
              if score >= threshold then idx = n else idx += 1

        case Left(error) =>
          lastError = Some(error)
          if idx > remainingFailures then return Left(error)
          remainingFailures -= 1
          idx += 1

    best match
      case Some(value) =>
        bestTrace.foreach(RuntimeEnvironment.appendTrace)
        bestHistory.foreach(RuntimeEnvironment.appendHistory)
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
