package dspy4s.harness.repl

import dspy4s.harness.guest.{Access, Mode}

/** A capability of a mode (CODE_HARNESS_DESIGN.md, 5.2). `Access.Full` gives `C^`. `Access.Read` gives the bare `C`
  * only when the bare type is a read-only view (`reads`), and else it gives nothing. The givens of the REPL and the API
  * reference both read the table `Capability.all`, so the reference names a call only when the call compiles.
  *
  * @param name
  *   the name of the trait in the guest API
  * @param level
  *   the level of the capability in a mode
  * @param givenName
  *   the name of the given in the preamble of the REPL
  * @param field
  *   the name of the handler in `Bindings`
  * @param handler
  *   the handler in `Bindings`
  */
final case class Capability(
    name     : String,
    reads    : Boolean,
    level    : Mode => Access,
    givenName: String,
    field    : String,
    handler  : Bindings => Option[?]
):

  /** True when `mode` gives this capability: `C^` for `full`, and else the bare `C` or `C^`. */
  def givenIn(mode: Mode, full: Boolean): Boolean =
    level(mode) match
      case Access.Full => true
      case Access.Read => reads && !full
      case Access.None => false

object Capability:

  /** The capabilities of a mode, in the order of the givens of the preamble. */
  val all: Vector[Capability] = Vector(
    Capability("Files", reads = true, _.files, "files", "files", _.files),
    Capability("Shell", reads = false, _.shell, "shell", "shell", _.shell),
    Capability("Network", reads = false, _.network, "net", "network", _.network),
    Capability("Llm", reads = false, _.llm, "llm", "llm", _.llm),
    Capability("Store", reads = true, _.store, "state", "store", _.store),
    Capability("Sessions", reads = true, _.sessions, "peers", "sessions", _.sessions),
    Capability("Children", reads = false, _.children, "children", "children", _.children)
  )

  /** The capability of the trait `name`, or `None` when the trait is not a capability of a mode. */
  def named(name: String): Option[Capability] = all.find(_.name == name)
