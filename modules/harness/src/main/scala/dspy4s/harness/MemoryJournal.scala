package dspy4s.harness

import dspy4s.harness.kernel.{Command, Logged, Meta, Rejection, SessionState, Source, Taint, decide, evolve, sourceOf}

/** A journal in memory (CODE_HARNESS_DESIGN.md, 9): `Harness.forward` needs no writer and no thread other than the cell
  * thread. It is the writer of 5.6 for one call: each entry gets the next `seq`, the time of `clock`, its source from
  * `sourceOf`, and the mask of `secrets` before it joins the log. A command that `decide` refuses adds nothing.
  *
  * The state is the fold of the masked entries, so the state and the log always agree (4.7, Fold). A cell thread and a
  * stop can call `run` at the same time, so `run` holds a lock.
  */
final class MemoryJournal(clock: () => Long, secrets: Secrets) extends Journal:
  private var entries = Vector.empty[Logged]
  private var current = SessionState.initial

  def state: SessionState = synchronized(current)

  def log: Vector[Logged] = synchronized(entries)

  def run(command: Command, sender: Source): Either[Rejection, SessionState] =
    synchronized {
      decide(command, current, sender).map { added =>
        added.foreach { entry =>
          val meta   = Meta(clock(), sourceOf(command, sender, entry), Taint.Trusted, required = true)
          val logged = Logged(current.nextSeq, meta, secrets.mask(entry))
          entries = entries :+ logged
          current = evolve(current, logged)
        }
        current
      }
    }
