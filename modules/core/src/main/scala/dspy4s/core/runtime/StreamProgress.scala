package dspy4s.core.runtime

import java.util.concurrent.atomic.AtomicBoolean

/** The visible stream output of the adapter calls that run now (upstream `dspy/clients/call_context.py`
  * `adapter_fallback_scope` and `stream_emitted`).
  *
  * A predictor opens a [[Scope]] with [[withScope]] for one adapter call. A streaming model calls [[markEmitted]] when
  * it gives a token to a listener. A predictor does not try a fallback adapter for a call whose scope has output: the
  * listener already has the output of that call.
  *
  * [[markEmitted]] marks each open scope of the thread, so a nested call also marks the calls that contain it. As
  * [[ActivePredictContext]], the stack is thread-local: the model call runs on the thread of the predictor.
  */
object StreamProgress:

  /** The progress of one adapter call. */
  final class Scope private[StreamProgress] ():
    private val flag = AtomicBoolean(false)

    /** `true` when a listener got stream output during the scope. */
    def emitted: Boolean = flag.get

    private[StreamProgress] def mark(): Unit = flag.set(true)

  /** The open scopes of this thread, the innermost first. */
  private val open = ScopedLocal[List[Scope]](Nil, travels = false)

  /** Run `body` with a new open scope, and close the scope after `body`. */
  def withScope[A](body: Scope => A): A =
    val scope = new Scope()
    open.within(scope :: open.get)(body(scope))

  /** Record that a listener got stream output. Each open scope of the thread gets the mark. */
  def markEmitted(): Unit = open.get.foreach(_.mark())
