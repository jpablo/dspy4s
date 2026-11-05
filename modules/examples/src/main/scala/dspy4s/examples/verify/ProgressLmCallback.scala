package dspy4s.examples.verify

import dspy4s.core.contracts.{CallbackEvent, CallbackHandler, LmEndEvent, RuntimeContext}

import java.util.concurrent.atomic.AtomicInteger

/** The live progress of a smoke run: it prints one character for each completed LM call (`.` for a call that passed,
  * `x` for a call that failed), and it counts the calls and the failures. A `CallbackHandler` in the `RuntimeContext`
  * is the general dspy4s hook for observation. Put a logger or a tracer in its place when you need one.
  */
final class ProgressLmCallback extends CallbackHandler:
  private val calls                                                      = new AtomicInteger(0)
  private val errors                                                     = new AtomicInteger(0)
  def count: Int                                                         = calls.get()
  def errorCount: Int                                                    = errors.get()
  override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
    event match
      case e: LmEndEvent =>
        val _ = calls.incrementAndGet()
        e.response match
          case Left(_) => val _ = errors.incrementAndGet(); System.out.print("x")
          case _       => System.out.print(".")
        System.out.flush()
      case _ => ()
