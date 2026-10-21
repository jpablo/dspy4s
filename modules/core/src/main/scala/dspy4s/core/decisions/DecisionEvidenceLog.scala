package dspy4s.core.decisions

import dspy4s.core.contracts.PredictorKey
import dspy4s.core.runtime.ContextPropagation

/** Experimental. The scoped log of the decision evidence of each call (upstream `record_evidence` in
  * `dspy/adapters/decision.py`). The runtime keeps this bookkeeping, not the modules (decision 6 of D6): a predictor
  * calls [[record]] after it reads the evidence of an output, and ReAnchor reads the log of a pass over the training
  * set.
  *
  *   - [[recording]] opens a new log for the duration of `body`. A nested log is independent: the entries of an inner
  *     scope do not go to the outer scope, and the outer scope gets its entries again after the inner scope ends, also
  *     when `body` throws.
  *   - The scope travels with the runtime context to the worker threads of the framework (for example the parallel
  *     executor), as the usage tracker does.
  *   - [[record]] with no open scope does nothing. It is safe from more than one thread.
  */
object DecisionEvidenceLog:

  /** One entry: the evidence that the predictor with the key `predictor` read for its output `field`. */
  final case class Entry(predictor: PredictorKey, field: String, evidence: DecisionEvidence) derives CanEqual

  /** The log of one scope. More than one thread can add to it (upstream: a list with a lock). */
  private final class Log:
    private var entries: Vector[Entry] = Vector.empty

    def add(entry: Entry): Unit = synchronized { entries = entries :+ entry }

    def snapshot: Vector[Entry] = synchronized(entries)

  /** The log of the innermost open scope of this thread. */
  private val active: ThreadLocal[Option[Log]] = ThreadLocal.withInitial(() => None)

  // The scope must travel with the runtime context to the worker threads of the framework, as the usage tracker does
  // (upstream: the collector is a setting of `dspy.context`, which the DSPy workers copy). The object registers the
  // carrier when it starts, and `recording` starts the object, so the carrier is there before a scope can be captured.
  ContextPropagation.registerCarrier(new ContextPropagation.Carrier:
    override def capture(): ContextPropagation.Snapshot =
      val captured = active.get()
      new ContextPropagation.Snapshot:
        override def restore[A](thunk: => A): A = within(captured)(thunk))

  /** The result of `body` and the entries of its scope, in the order of the calls. */
  def recording[A](body: => A): (A, Vector[Entry]) =
    val log   = new Log
    val value = within(Some(log))(body)
    (value, log.snapshot)

  /** Add an entry to the innermost open scope. */
  def record(predictor: PredictorKey, field: String, evidence: DecisionEvidence): Unit =
    active.get().foreach(_.add(Entry(predictor, field, evidence)))

  /** Run `thunk` with `log` as the open scope of this thread, then put back the previous scope. */
  private def within[A](log: Option[Log])(thunk: => A): A =
    val previous = active.get()
    active.set(log)
    try thunk
    finally active.set(previous)
