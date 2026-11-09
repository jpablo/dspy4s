package dspy4s.harness.kernel

import dspy4s.harness.guest.Mode
import zio.blocks.schema.Schema
import zio.blocks.schema.json.Json

/** Who waits for a session (8.1): the user, an optimizer, a session (`start`) or a cell (`rlm`). A cell and an
  * optimizer have no person who can answer a question (5.5).
  */
enum Owner derives Schema, CanEqual:
  case User, Optimizer, Cell
  case Session(id: SessionId)

  /** True for the user and for a session. */
  def hasPerson: Boolean = ???

/** A chat turn gives one last step to answer. A program call ends, and the caller runs the finisher (4.3). */
enum TurnKind derives Schema, CanEqual:
  case Chat, Program

/** The strategies of the context (7). */
enum StrategyName derives Schema, CanEqual:
  case Window, PrefixSummary, MemoryTree

/** Where a session works (8.1).
  *
  * A proposal for the review: the table of 8.1 names these cases, but the design gives no type. A path is a text, so
  * the configuration has the same JSON on each host.
  */
enum Scope derives Schema, CanEqual:

  /** The home of the user: the scope of the controller. */
  case Home(path: String)

  case Directory(path: String)

  case Worktree(path: String, branch: String)

  /** A new directory that only this session uses. */
  case Scratch(path: String)

  /** A copy at `path` of the workspace at `from`: the scope of a rollout, or of a child that does not share the scope
    * of its parent.
    */
  case Fork(path: String, from: String)

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
    ceiling : Mode,       // the most that a session started from here can get
    strategy: StrategyName,
    scope   : Scope,
    owner   : Owner,
    guard   : Guard,      // the rules of 5.5
    preamble: String,     // the types, `submit` and the input fields of this session (5.1)
    model   : ModelChoice,
    goal    : Option[String],
    env     : Set[String] // more names of variables for a command (5.6)
) derives Schema, CanEqual
