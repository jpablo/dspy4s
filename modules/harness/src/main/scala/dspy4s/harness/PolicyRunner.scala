package dspy4s.harness

/** How `HarnessEffects` runs the call of the policy LM (CODE_HARNESS_DESIGN.md, 10.3). A program call runs it on the
  * thread of `callPolicy` (`Direct`). A work session of the host runs it on a thread of its own, so that a hard stop
  * can cut a long call. The update of the derived data and the projection of the log stay on the thread of
  * `callPolicy`, so a call that a stop cut changes no state of the effects.
  */
trait PolicyRunner:

  /** The result of `call`, or an error when a stop cut the call: then the error is `PolicyRunner.Stopped`. */
  def run[A](call: () => Either[String, A]): Either[String, A]

object PolicyRunner:

  /** The error of a call that a stop cut. A caller can tell it from the error of the model. */
  val Stopped = "the model call stopped, because the turn stops"

  /** Runs the call on the thread of the caller. */
  object Direct extends PolicyRunner:
    def run[A](call: () => Either[String, A]): Either[String, A] = call()
