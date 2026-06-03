package dspy4s.core.contracts

import scala.annotation.nowarn
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** The universal callable contract for everything in dspy4s -- predicts, composite programs, optimizers' wrapped
  * candidates, anything you can invoke with an input and get back an `Either[DspyError, Out]`.
  *
  * Variance is the standard "function" variance: contravariant in `In` (a `Module[Any, X]` can satisfy a position
  * expecting `Module[ProgramCall, X]`), covariant in `Out` (a `Module[I, PredictionData]` satisfies
  * `Module[I, DynamicPrediction]`). This makes program composition compose naturally with subtyping.
  *
  *   - [[moduleName]] is the public identity. Used by callbacks, trace entries, and stream-listener routing -- so
  *     end users can recognize a program by its module name in logs and traces. By convention: snake_case
  *     (`"predict"`, `"chain_of_thought"`, `"react"`).
  *   - [[apply]] is the synchronous entry. The required `using RuntimeContext` carries the active settings (LM,
  *     adapter, callbacks, cache), so the caller never threads them by hand.
  *   - [[applyAsync]] is the async entry. The default wraps `apply` in a `Future.successful` -- adequate for tests and
  *     simple cases. [[dspy4s.programs.runtime.BasePredictProgram]] overrides it with
  *     [[dspy4s.core.runtime.ContextPropagation.future]] so the callback / trace / `ActivePredictContext` thread-
  *     locals propagate across the thread boundary correctly.
  *
  * Notable extensions: [[dspy4s.programs.contracts.PredictProgram]] (the predict-shaped subtype every
  * `DynamicPredict` / composite program ultimately extends).
  */
trait Module[-In, +Out]:
  def moduleName: String

  def apply(input: In)(using RuntimeContext): Either[DspyError, Out]

  // The default body doesn't use the ExecutionContext, but it's part of the contract for
  // overrides that run real async work (e.g. BasePredictProgram.applyAsync).
  @nowarn("msg=unused")
  def applyAsync(input: In)(using RuntimeContext, ExecutionContext): Future[Either[DspyError, Out]] =
    Future.successful(apply(input))
