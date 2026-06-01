package dspy4s.core.runtime

import dspy4s.core.contracts.SignatureLayout

/** Identity of the predictor currently running an LM call.
  *
  * Used by the streaming runtime to route per-LM-call tokens to the correct
  * [[dspy4s.streaming.contracts.StreamListener]]s with the correct
  * signature-derived field labels.
  */
final case class ActivePredict(name: String, layout: SignatureLayout)

/** Thread-local stack of [[ActivePredict]] entries. A predictor's `execute` (in `PredictEngine`) pushes its
  * entry via [[withActive]] for the duration of the body and pops it on exit; downstream components -- notably
  * `StreamingLanguageModelWrapper` -- read [[current]] to learn which predictor's signature applies to the LM
  * call they are observing.
  *
  * Stack semantics handle nested calls (e.g. a `DynamicPredict` invoked from inside another via a custom
  * composite module): the most recent push wins via [[current]], and the caller resurfaces when the inner
  * scope exits.
  *
  * '''Single-thread by design.''' The push and the read are always co-located on one thread -- the LM call that
  * reads [[current]] runs synchronously inside the `withActive` body that pushed it. So this stack deliberately
  * does NOT cross thread boundaries: when work runs on a pool thread (a `Future`, a parallel worker, or
  * Streamify's producer thread), that thread re-runs `execute` and re-establishes its own stack, which is
  * exactly the identity its LM calls should see. [[ContextPropagation]] carries the [[RuntimeContext]] across
  * threads but intentionally leaves this stack alone. Concurrent invocations stay isolated because the storage
  * is a [[ThreadLocal]].
  */
object ActivePredictContext:
  private val tl: ThreadLocal[List[ActivePredict]] =
    ThreadLocal.withInitial(() => Nil)

  def current: Option[ActivePredict] = tl.get.headOption

  /** The full active-predictor stack, innermost first. */
  def stack: List[ActivePredict] = tl.get

  /** Publish `active` as the innermost running predictor for the dynamic extent of `thunk`, then pop it
    * (restoring the prior stack) on exit.
    *
    * '''Why this is needed.''' When a predictor runs an LM call, the call goes through the generic,
    * provider-facing `LanguageModel.call(request)` -- which carries only an `LmRequest` and has no slot for
    * dspy4s's predictor name or output signature. Yet the streaming wrapper observing that call needs both: the
    * name to label emitted `TokenEvent`s (`predictName`) and the signature `layout` to split the stream into
    * per-field chunks. `withActive` is the out-of-band channel that carries that identity from
    * `PredictEngine.execute` down to the wrapper without widening the provider-facing LM contract.
    *
    * The try/finally bounds the identity to exactly the predict's execution, so an LM call made after the
    * predict returns is never misattributed to it; the stack lets a nested predict shadow its caller via
    * [[current]] and the caller resurface once the inner scope completes. */
  def withActive[A](active: ActivePredict)(thunk: => A): A =
    val previous = tl.get
    tl.set(active :: previous)
    try thunk
    finally tl.set(previous)

  def withActive[A](name: String, layout: SignatureLayout)(thunk: => A): A =
    withActive(ActivePredict(name, layout))(thunk)
