package dspy4s.harness.guest

import zio.blocks.schema.Schema

// The types of the sessions that the guest API (`Sessions.start`, `Sessions.list`) and the kernel (`SessionConfig`)
// both use. The kernel depends on harness-guest, so they are here, as `Mode` is (decision of 2026-11-09).

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

/** A reference to another session, for `tell`, `observe` and `zoom` (8.2). It is plain data, so the store can hold it.
  */
final case class SessionRef(id: String) derives Schema, CanEqual

/** What `list` shows about a session (8.2, 10.2). The catalog gives the deterministic part: the id, the owner, the
  * parent of a fork, the status and the last `seq`.
  *
  * A proposal for the review: the design names these fields and gives no type. The goal and the parts that an LM writes
  * (artifacts, decisions and open questions, as in CODE_HARNESS.md 7.4) come with the controller of phase 9.
  */
final case class SessionCard(
    session: SessionRef,
    goal   : String,
    owner  : String,
    parent : Option[SessionRef],
    status : String,
    lastSeq: Long
) derives Schema, CanEqual
