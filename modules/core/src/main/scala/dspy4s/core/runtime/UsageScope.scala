package dspy4s.core.runtime

import dspy4s.core.contracts.LmUsage
import dspy4s.core.contracts.RuntimeContext

import scala.collection.mutable

/** The usage of the model calls of one usage scope, for each model name (upstream `UsageTracker`,
  * `dspy/utils/usage_tracker.py`). [[UsageScope.record]] adds to it. More than one thread can add to one tracker at the
  * same time. The lm module gives this type to the users as `dspy4s.lm.runtime.UsageTracker`.
  */
final class UsageTracker:
  private val data = mutable.Map.empty[String, Vector[LmUsage]]

  def addUsage(model: String, usage: LmUsage): Unit =
    this.synchronized {
      val entries = data.getOrElse(model, Vector.empty)
      data.update(model, entries :+ usage)
    }

  def usageData: Map[String, Vector[LmUsage]] =
    this.synchronized {
      data.toMap
    }

  def totalUsage: Map[String, LmUsage] =
    this.synchronized {
      data.view.mapValues(_.foldLeft(LmUsage.empty)(_.combine(_))).toMap
    }

/** The usage scopes, the one sink of the LM usage (decision D9 of UPGRADE-3.2.1-to-3.4.0). A scope keeps the usage of
  * its model calls for each model, in a [[UsageTracker]]. Two parts of the code open a scope:
  *
  *   - A program call ([[measure]]). `Module.apply` opens one scope for each call, and the result of the call gets the
  *     total of the scope as its LM usage. Upstream `Module.__call__` opens a usage tracker for the call and puts its
  *     total on the result (`dspy/primitives/module.py`, `track_usage` and `_set_lm_usage`).
  *   - A user tracker ([[track]]). `UsageTracking.withTracker` and `UsageTracking.withNewTracker` of the lm module open
  *     it (upstream `dspy.track_usage`, `dspy/utils/usage_tracker.py`).
  *
  * The record point is the framework code that calls a model (`LanguageModel.callAndRecordUsage` in the lm module). It
  * records the usage of each successful reply one time, with the model name of the request ([[record]]). Upstream
  * records each LM call into `settings.usage_tracker` (`dspy/clients/execution.py`, `finalize`). The model itself does
  * not record. Thus a model with no managed wrapper counts, and a model that calls a second model counts one time only.
  * A direct `call` or `stream` of a model, also of `ManagedLanguageModel`, counts in no scope. A reply with no usage,
  * for example a cache hit of `ManagedLanguageModel`, adds nothing. A call that fails gives no reply, so it adds
  * nothing.
  *
  * A record adds the usage to each open scope of the thread: the scopes of the program calls and the scopes of the user
  * trackers. Thus a nested scope has the total of its own model calls, and each outer scope has them too, one time.
  * With `trackUsage = Some(false)` in the context of the record, the scopes of the user trackers get nothing. The
  * scopes of the program calls still get the usage, because the port keeps one usage on each result.
  *
  * The open scopes travel to the worker threads of the framework with the runtime context (a
  * [[ContextPropagation.Carrier]]), so the model calls of a parallel run count in the scopes of the code that started
  * the run. More than one thread can add to one scope at the same time.
  */
object UsageScope:

  /** One open scope. The scope of a user tracker (`ofUser = true`) gets nothing when `trackUsage` is `false`. */
  private final case class Open(tracker: UsageTracker, ofUser: Boolean)

  /** The open scopes of this thread, the outermost first. They travel with the runtime context to the worker threads
    * (`ParallelExecutor`, the futures, the producer of `streamify`).
    */
  private val active = ScopedLocal[Vector[Open]](Vector.empty, travels = true)

  /** The result of `body` and the total usage of the model calls that `body` made, also on the worker threads that it
    * started. This is the scope of a program call. The total is `None` when no model call gave a usage.
    */
  def measure[A](body: => A): (A, Option[LmUsage]) =
    val tracker = new UsageTracker
    val value   = open(Open(tracker, ofUser = false))(body)
    (value, tracker.totalUsage.values.reduceOption(_.combine(_)))

  /** The result of `body`, with `tracker` as an open scope of a user tracker. Each record in `body` adds to `tracker`,
    * also on the worker threads that `body` started, when `trackUsage` is not `false`.
    */
  def track[A](tracker: UsageTracker)(body: => A): A =
    open(Open(tracker, ofUser = true))(body)

  /** Add the usage of one model call of the model `model` to each open scope of this thread. `None` (a reply with no
    * usage) changes nothing. With `trackUsage = Some(false)` in the context, the scopes of the user trackers get
    * nothing. With no open scope, the usage goes nowhere.
    */
  def record(model: String, usage: Option[LmUsage])(using context: RuntimeContext): Unit =
    val toUsers = context.trackUsage.getOrElse(true)
    for
      value <- usage
      scope <- active.get
      if toUsers || !scope.ofUser
    do scope.tracker.addUsage(model, value)

  private def open[A](scope: Open)(body: => A): A =
    active.within(active.get :+ scope)(body)
