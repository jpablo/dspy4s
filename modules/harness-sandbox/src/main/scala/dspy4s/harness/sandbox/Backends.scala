package dspy4s.harness.sandbox

/** The backends of this module, in the order of the choice. */
object Backends:
  val all: Vector[Backend] = Vector(Seatbelt, Bubblewrap)

  /** The first backend that works on this host: Seatbelt on macOS, bubblewrap on Linux, or none. With none, `exec`
    * fails, and the command does not run (5.4).
    */
  def detect(): Option[Backend] = all.find(_.works())
