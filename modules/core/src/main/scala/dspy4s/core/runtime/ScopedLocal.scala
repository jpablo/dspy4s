package dspy4s.core.runtime

/** A thread-local value with scoped changes. [[within]] sets the value for the extent of a body, then puts back the
  * previous value, also when the body throws.
  *
  * A local that travels registers a [[ContextPropagation.Carrier]] when it is created. Each capture of
  * [[ContextPropagation]] then takes its value to the worker thread, and the worker runs with that value. A local that
  * does not travel stays on its thread: a worker thread starts with `initial`.
  *
  * An `object` that owns a local that travels creates the local in its initializer, so the carrier is registered one
  * time, when the object starts. The object starts before it opens its first scope, so the carrier is there before a
  * capture can need it.
  *
  * @param initial
  *   the value of a thread that has no open scope
  * @param travels
  *   `true` when the value goes with the runtime context to the worker threads
  */
private[dspy4s] final class ScopedLocal[A](initial: => A, travels: Boolean):
  private val local: ThreadLocal[A] = ThreadLocal.withInitial(() => initial)

  /** The value of this thread. */
  def get: A = local.get()

  /** Run `body` with `value` as the value of this thread, then put back the previous value. */
  def within[B](value: A)(body: => B): B =
    val previous = local.get()
    local.set(value)
    try body
    finally local.set(previous)

  if travels then
    ContextPropagation.registerCarrier(new ContextPropagation.Carrier:
      override def capture(): ContextPropagation.Snapshot =
        val captured = get
        new ContextPropagation.Snapshot:
          override def restore[R](thunk: => R): R = within(captured)(thunk))
