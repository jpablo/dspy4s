package dspy4s.programs.runtime

import dspy4s.core.contracts.LmUsage

import java.util.concurrent.atomic.AtomicReference

/** The sum of the LM usage of the model calls of one program call. An agent (`ReAct`, `ReActV2`, `RLM`) makes many
  * model calls in one call, and it puts this sum on its result as `RawPrediction.lmUsage`. Upstream `Module.__call__`
  * puts the total usage of the call on the returned `Prediction` (`dspy/primitives/module.py`, `track_usage` and
  * `_set_lm_usage`), and each LM call of the run adds its usage to the tracker (`dspy/utils/usage_tracker.py`).
  *
  * The sum uses [[LmUsage.combine]], the rule of `RawPrediction.followedBy`. More than one thread can add at the same
  * time, for example the sub-LM calls of the RLM tool `llm_query_batched`.
  */
final class RunUsage:
  private val sum = new AtomicReference[Option[LmUsage]](None)

  /** Add the usage of one model call. `None` (a call with no usage) changes nothing. */
  def add(usage: Option[LmUsage]): Unit =
    usage.foreach { value =>
      val _ = sum.updateAndGet(current => Some(current.fold(value)(_.combine(value))))
    }

  /** The sum, or `None` when no model call of the run gave a usage. */
  def total: Option[LmUsage] = sum.get
