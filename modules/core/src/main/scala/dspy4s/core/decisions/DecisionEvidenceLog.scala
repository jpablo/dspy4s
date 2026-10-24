package dspy4s.core.decisions

import dspy4s.core.contracts.PredictorKey
import dspy4s.core.runtime.ScopedLocal

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

  /** The log of the innermost open scope of this thread. The scope travels with the runtime context to the worker
    * threads of the framework, as the usage tracker does (upstream: the collector is a setting of `dspy.context`, which
    * the DSPy workers copy).
    */
  private val active = ScopedLocal[Option[Log]](None, travels = true)

  /** The result of `body` and the entries of its scope, in the order of the calls. */
  def recording[A](body: => A): (A, Vector[Entry]) =
    val log   = new Log
    val value = active.within(Some(log))(body)
    (value, log.snapshot)

  /** Add an entry to the innermost open scope. */
  def record(predictor: PredictorKey, field: String, evidence: DecisionEvidence): Unit =
    active.get.foreach(_.add(Entry(predictor, field, evidence)))
