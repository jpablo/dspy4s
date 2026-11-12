package dspy4s.harness.kernel

import zio.blocks.schema.Schema
import zio.blocks.schema.json.Json

/** The ledger of a session (CODE_HARNESS.md, 7.4; CODE_HARNESS_DESIGN.md, 8.3): what it read and what it made, with no
  * LM. The controller sees it on the card of the session.
  *
  * @param read
  *   the paths that the session read, each once, in the order of the first read
  * @param made
  *   the paths that the session wrote or edited, each once, in the order of the first write
  * @param commands
  *   the number of `exec` and `spawn` calls
  * @param head
  *   the git head of the last `ScopeRecorded`
  * @param clean
  *   the state of the scope at the last `ScopeRecorded`
  * @param released
  *   the steps of the release plan of the session (7.9)
  */
final case class Ledger(
    read    : Vector[String],
    made    : Vector[String],
    commands: Int,
    head    : Option[String],
    clean   : Option[Boolean],
    released: Vector[ReleaseStep]
) derives Schema,
      CanEqual

object Ledger:
  val empty: Ledger = Ledger(Vector.empty, Vector.empty, 0, None, None, Vector.empty)

/** The state of the fold of a ledger: the ledger, and the effects of the open cell, which wait for their outcome. */
final case class LedgerState(ledger: Ledger, open: Map[EffectId, OpenCall]) derives CanEqual

object LedgerState:
  val empty: LedgerState = LedgerState(Ledger.empty, Map.empty)

/** The ledger as a fold of the journal (11: Derived, a pure `Fold`).
  *
  *   - An effect counts at its `EffectDone`. A `read` with a value adds its `path` to `read`. A `write` or an `edit`
  *     with a value or with `Unknown` adds its `path` to `made`, because a cut write can have changed the file. An
  *     `exec` or a `spawn` with a value or with `Unknown` adds one command. An error or a pending call counts for
  *     nothing.
  *   - `ScopeRecorded` sets `head` and `clean`. `Released` adds its step.
  *   - Each other entry changes nothing.
  *
  * The arguments of a file call hold the absolute path (`recorded` with a root), so one file has one path.
  */
def ledger: Fold[Logged, LedgerState, Ledger] =
  Fold(
    LedgerState.empty,
    (acc, logged) =>
      logged.entry match
        case Entry.EffectStarted(id, op, args) => acc.copy(open = acc.open + (id -> OpenCall(op, args)))
        case Entry.EffectDone(id, result)      =>
          val counts = acc.open.get(id).fold(acc.ledger)(call => counted(acc.ledger, call, result))
          LedgerState(counts, acc.open - id)
        case Entry.ScopeRecorded(head, clean) =>
          acc.copy(ledger = acc.ledger.copy(head = Some(head), clean = Some(clean)))
        case Entry.Released(step) => acc.copy(ledger = acc.ledger.copy(released = acc.ledger.released :+ step))
        case _                    => acc
    ,
    _.ledger
  )

/** The ledger after the outcome `result` of `call`. */
private def counted(ledger: Ledger, call: OpenCall, result: EffectResult): Ledger =
  // `done`: the call gave a value. `touched`: the call can have changed the file or run, also when a stop cut it.
  val done = result match
    case EffectResult.Value(_, _) => true
    case _                        => false
  val touched = done || result == EffectResult.Unknown
  call.op match
    case "read" if done              => pathArg(call.args).fold(ledger)(p => ledger.copy(read = once(ledger.read, p)))
    case "write" | "edit" if touched => pathArg(call.args).fold(ledger)(p => ledger.copy(made = once(ledger.made, p)))
    case "exec" | "spawn" if touched => ledger.copy(commands = ledger.commands + 1)
    case _                           => ledger

/** The text of the field `path` of the arguments of a file call. */
private def pathArg(args: Json): Option[String] =
  args match
    case Json.Object(fields) => fields.collectFirst { case ("path", Json.String(path)) => path }
    case _                   => None

/** `paths` with `path` at its end, when it is not in `paths`. */
private def once(paths: Vector[String], path: String): Vector[String] =
  if paths.contains(path) then paths else paths :+ path
