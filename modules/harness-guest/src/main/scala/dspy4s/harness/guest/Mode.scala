package dspy4s.harness.guest

import zio.blocks.schema.Schema

/** One level of access to a capability (CODE_HARNESS_DESIGN.md, 5.2). The levels form a chain: `None < Read < Full`. */
enum Access derives Schema, CanEqual:
  case None, Read, Full

  def <=(that: Access): Boolean = ???

  /** The lower level of the two. */
  def meet(that: Access): Access = ???

/** The capabilities of a session (5.2). A mode is a product of seven chains, so the modes form a bounded
  * meet-semilattice with `Mode.full` at the top. The REPL makes the givens of the preamble from a mode, and
  * `SessionConfig` holds two modes.
  */
final case class Mode(
    files   : Access,
    shell   : Access,
    network : Access,
    llm     : Access,
    sessions: Access,
    children: Access,
    store   : Access
) derives Schema, CanEqual:

  /** True when each capability of this mode is at most the capability of `that`. */
  def <=(that: Mode): Boolean = ???

  /** The mode with the lower level of each capability. */
  def meet(that: Mode): Mode = ???

/** The modes of the table of 5.2. */
object Mode:
  val full: Mode = Mode(Access.Full, Access.Full, Access.Full, Access.Full, Access.Full, Access.Full, Access.Full)

  /** Each capability apart from the network. */
  val local: Mode = Mode(Access.Full, Access.Full, Access.None, Access.Full, Access.Full, Access.Full, Access.Full)

  val readOnly: Mode = Mode(Access.Read, Access.None, Access.None, Access.Full, Access.Read, Access.None, Access.Full)

  val controller: Mode = Mode(Access.Read, Access.None, Access.None, Access.Full, Access.Full, Access.Full, Access.Full)

  /** A child and a rollout have the mode `base.meet(Mode.closed)`. They can look at other sessions, and they cannot
    * start work outside themselves.
    */
  val closed: Mode = Mode(Access.Full, Access.Full, Access.Full, Access.Full, Access.Read, Access.Full, Access.Full)
