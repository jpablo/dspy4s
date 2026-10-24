package dspy4s.core.runtime

import dspy4s.core.contracts.LmUsage

import java.util.concurrent.atomic.AtomicReference

/** The usage scope of a program call (decision D9 of UPGRADE-3.2.1-to-3.4.0). Each program call opens one scope
  * ([[measure]]), and the result of the call gets the total of the scope as its LM usage. Upstream `Module.__call__`
  * opens a usage tracker for the call and puts its total on the result (`dspy/primitives/module.py`, `track_usage` and
  * `_set_lm_usage`, with the tracker of `dspy/utils/usage_tracker.py`).
  *
  * The record point is the framework code that calls a model for a program call (`LanguageModel.callAndRecordUsage` in
  * the lm module). It records the usage of the reply one time for each model call ([[record]]). The model itself does
  * not record into the scope. Thus a model with no managed wrapper counts, and a model that calls a second model counts
  * one time only. A reply with no usage, for example a cache hit of `ManagedLanguageModel`, adds nothing. A call that
  * fails at the provider gives no reply, so it adds nothing.
  *
  * A record adds the usage to each open scope of the thread. Thus a nested call has the total of its own model calls,
  * and the outer call has them too, one time. The open scopes travel to the worker threads of the framework with the
  * runtime context (a [[ContextPropagation.Carrier]]), so the model calls of a parallel run count in the scope of the
  * call that started the run. More than one thread can add to one scope at the same time.
  *
  * This scope is not the user tracker of the lm module (`UsageTracking`). That tracker keeps its rules: the managed
  * model records into it, per model, and only when `trackUsage` is not `false`. The scope counts always, as the port
  * keeps one usage on each result.
  */
object UsageScope:

  /** The total of one open scope. */
  private final class Total:
    private val sum = new AtomicReference[Option[LmUsage]](None)

    def add(usage: LmUsage): Unit =
      val _ = sum.updateAndGet(current => Some(current.fold(usage)(_.combine(usage))))

    def value: Option[LmUsage] = sum.get

  /** The open scopes of this thread, the outermost first. They travel with the runtime context to the worker threads,
    * as the usage tracker of the lm module does.
    */
  private val active = ScopedLocal[Vector[Total]](Vector.empty, travels = true)

  /** The result of `body` and the total usage of the model calls that `body` made, also on the worker threads that it
    * started. The total is `None` when no model call gave a usage.
    */
  def measure[A](body: => A): (A, Option[LmUsage]) =
    val total = new Total
    val value = active.within(active.get :+ total)(body)
    (value, total.value)

  /** Add the usage of one model call to each open scope of this thread. `None` (a reply with no usage) changes nothing.
    * With no open scope, the usage goes nowhere.
    */
  def record(usage: Option[LmUsage]): Unit =
    usage.foreach(value => active.get.foreach(_.add(value)))
