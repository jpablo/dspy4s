package dspy4s.harness.kernel

import dspy4s.harness.guest.SessionSchemas.given
import dspy4s.harness.guest.{Mode, Scope, StrategyName}
import zio.blocks.schema.Schema
import zio.blocks.schema.json.Json

/** Who waits for a session (8.1): the user, an optimizer, a session (`start`) or a cell (`rlm`). A cell and an
  * optimizer have no person who can answer a question (5.5).
  */
enum Owner derives Schema, CanEqual:
  case User, Optimizer, Cell
  case Session(id: SessionId)

  /** True for the user and for a session. */
  def hasPerson: Boolean =
    this match
      case User | Session(_) => true
      case Optimizer | Cell  => false

  /** The owner in words, for example `the person` or `the session s2`. The card of a session (8.7) gives these words,
    * so the controller reads no Scala value.
    */
  def words: String =
    this match
      case User        => "the person"
      case Session(id) => s"the session ${id.value}"
      case Optimizer   => "an optimizer"
      case Cell        => "a cell"

/** A chat turn gives one last step to answer. A program call ends, and the caller runs the finisher (4.3). */
enum TurnKind derives Schema, CanEqual:
  case Chat, Program

/** The model of a session (8.1).
  *
  * A proposal for the review: the design gives no type. `options` is the bag of the provider. It holds no key, because
  * `Activated` holds the configuration (5.6). The host finds the key from the model name.
  */
final case class ModelChoice(model: String, options: Map[String, Json]) derives Schema, CanEqual

/** One type for the four kinds of session: the controller, a work session, a child and a rollout (8.1). `Activated`
  * holds it, so it holds no secret (5.6).
  */
final case class SessionConfig(
    kind    : TurnKind,
    steps   : Int,
    mode    : Mode,
    ceiling : Mode, // the most that a session started from here can get
    strategy: StrategyName,
    scope   : Scope,
    owner   : Owner,
    guard   : Guard, // the rules of 5.5
    preamble: String, // the types, `submit` and the input fields of this session (5.1)
    model   : ModelChoice,
    goal    : Option[String],
    env     : Set[String], // more names of variables for a command (5.6)
    host    : Option[HostFacts] = None // the limits and the sandbox of this activation
) derives Schema, CanEqual:

  /** The limit of the turns in a row that no message of the person starts (8.7): the limit of the facts of the host. An
    * activation with no facts, or with the facts of an older host, has `TurnLimit.DefaultTurns` (8), because that host
    * held a message after 8 turns.
    */
  def unattendedLimit: TurnLimit =
    host.flatMap(_.limits.unattendedTurns).getOrElse(TurnLimit.AtMost(TurnLimit.DefaultTurns))

/** The limits and the sandbox of one activation (5.4, 5.6, 6, 7.1, 8.2). The host gives them in the `Activate` of each
  * open, so the journal shows what acted on each turn, and `/config` and the inspector show the same facts. A journal
  * of an older host has none.
  *
  * The fields have no default values. The JSON codec leaves out a field with its default value, and a later default
  * would then change the record of an old activation.
  */
final case class HostFacts(limits: Limits, sandbox: SandboxFacts) derives Schema, CanEqual

/** The limits of the harness for an activation (`HarnessLimits`).
  *
  * @param cellMillis
  *   the time limit of the guest code of a cell. The clock stops during each guest API call.
  * @param cellWallMillis
  *   the wall-clock limit of a cell, with its guest API calls. None means no wall limit. An activation of an older host
  *   has none, because that host had no wall limit.
  * @param callsPerCell
  *   the guest API calls of one cell. Each later call of the cell fails.
  * @param cellsPerRepl
  *   the cells of one REPL. Then the harness starts a new REPL.
  * @param commandSeconds
  *   the time limit of a command of `exec`
  * @param observationChars
  *   the most characters of the output of a cell that the model gets
  * @param memoryChars
  *   the characters of the projection of the memory
  * @param modelCalls
  *   the model calls of one turn, with the queries and the children
  * @param summaryCalls
  *   the calls of `compress` and `merge` of one turn. 0 means no call.
  * @param batch
  *   the queries or children that one batch call runs at the same time
  * @param depth
  *   the depth limit of `rlm`
  * @param unattendedTurns
  *   the turns in a row that no message of the person starts (8.7). At the limit, a message of another session, the
  *   harness or a job waits for the person. None means an activation of an older host, which held such a message after
  *   `SessionState.UnattendedTurns` (8) turns.
  */
final case class Limits(
    cellMillis      : Long,
    cellWallMillis  : Option[Long],
    callsPerCell    : Int,
    cellsPerRepl    : Int,
    commandSeconds  : Int,
    observationChars: Int,
    memoryChars     : Int,
    modelCalls      : Int,
    summaryCalls    : Int,
    batch           : Int,
    depth           : Int,
    unattendedTurns : Option[TurnLimit]
) derives Schema, CanEqual

/** The limit of the turns in a row that no message of the person starts (8.7). `AtMost(n)` holds a message of another
  * session, the harness or a job after `n` such turns. `NoLimit` never holds a message.
  */
enum TurnLimit derives Schema, CanEqual:
  case AtMost(turns: Int)
  case NoLimit

  /** The limit in words, for example `1,000`, or `none` for no limit. */
  def words: String =
    this match
      case AtMost(turns) => grouped(turns.toLong)
      case NoLimit       => "none"

object TurnLimit:

  /** The turns of the default limit (8.7). A host with the default limits has `AtMost(DefaultTurns)`, and an older
    * host, whose facts have no limit, held a message after so many turns. `SessionState.UnattendedTurns` gives the same
    * number. The number is here and not in `SessionState`, because `SessionState` holds a `SessionConfig`, and the
    * configuration must not need the state.
    */
  val DefaultTurns: Int = 8

/** The sandbox of the commands of an activation (5.4, 5.6).
  *
  * @param backend
  *   the OS sandbox, for example `Seatbelt` or `bubblewrap`. With none, no command runs.
  * @param hosts
  *   the patterns of the host list of a mode with `Network^`
  * @param secretPaths
  *   the paths that each command cannot read
  * @param secretNames
  *   the names of the files that each command cannot read, in each folder. A name also covers the name with a dot and
  *   more text, for example `.env.local`.
  * @param env
  *   the names of the variables that a command gets from the host process, when the process has them
  */
final case class SandboxFacts(
    backend    : Option[String],
    hosts      : Vector[String],
    secretPaths: Vector[String],
    secretNames: Vector[String],
    env        : Vector[String]
) derives Schema, CanEqual
