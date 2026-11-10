package dspy4s.harness

import dspy4s.harness.kernel.{Command, Logged, Rejection, SessionState, Source}

import scala.annotation.nowarn

/** A journal in memory (CODE_HARNESS_DESIGN.md, 9): `Harness.forward` needs no writer and no thread other than the cell
  * thread. It is the writer of 5.6 for one call: each entry gets the next `seq`, the time of `clock`, its source from
  * `sourceOf`, and the mask of `secrets` before it joins the log. A command that `decide` refuses adds nothing.
  *
  * A cell thread and a stop can call `run` at the same time, so `run` holds a lock.
  */
@nowarn("msg=unused") // the skeleton: the proof uses the parameters
final class MemoryJournal(clock: () => Long, secrets: Secrets) extends Journal:
  def state: SessionState                                                    = ???
  def log: Vector[Logged]                                                    = ???
  def run(command: Command, sender: Source): Either[Rejection, SessionState] = ???
