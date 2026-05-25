package dspy4s.core.runtime

import dspy4s.core.contracts.SignatureLayout

/** Identity of the predictor currently running an LM call.
  *
  * Used by the streaming runtime to route per-LM-call tokens to the correct
  * [[dspy4s.streaming.contracts.StreamListener]]s with the correct
  * signature-derived field labels.
  */
final case class ActivePredict(name: String, layout: SignatureLayout)

/** Thread-local stack of [[ActivePredict]] entries, pushed for the duration of
  * a `DynamicPredict.execute` body and read by downstream components (notably
  * `StreamingLanguageModelWrapper`) that need to know which predictor's
  * signature applies to the LM call they are observing.
  *
  * Stack semantics handle nested calls (e.g. a `DynamicPredict` invoked from inside
  * another `DynamicPredict.run` via a custom composite module): the most recent push
  * always wins via `current`.
  *
  * Context is propagated across thread boundaries by
  * [[ContextPropagation]], which copies the stack into worker threads at
  * capture time. Concurrent invocations on different threads remain isolated
  * because the underlying storage is a [[ThreadLocal]].
  */
object ActivePredictContext:
  private val tl: ThreadLocal[List[ActivePredict]] =
    ThreadLocal.withInitial(() => Nil)

  def current: Option[ActivePredict] = tl.get.headOption

  def stack: List[ActivePredict] = tl.get

  def withActive[A](active: ActivePredict)(thunk: => A): A =
    val previous = tl.get
    tl.set(active :: previous)
    try thunk
    finally tl.set(previous)

  def withActive[A](name: String, layout: SignatureLayout)(thunk: => A): A =
    withActive(ActivePredict(name, layout))(thunk)

  /** Replace the entire stack; used by [[ContextPropagation]] when copying
    * runtime state across thread boundaries. */
  def restore[A](snapshot: List[ActivePredict])(thunk: => A): A =
    val previous = tl.get
    tl.set(snapshot)
    try thunk
    finally tl.set(previous)
