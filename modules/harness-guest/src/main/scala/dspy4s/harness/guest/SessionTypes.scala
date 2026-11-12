package dspy4s.harness.guest

// A cell can make these types, for example for `start`, so they have `@assumeSafe`, which needs capture checking (5.1).
import language.experimental.captureChecking

import caps.assumeSafe

// The types of the sessions that the guest API (`Sessions.start`, `Sessions.list`) and the kernel (`SessionConfig`)
// both use. The kernel depends on harness-guest, so they are here, as `Mode` is (decision of 2026-11-09).

/** The strategies of the context (7). */
@assumeSafe enum StrategyName derives CanEqual:
  case Window, PrefixSummary, MemoryTree

@assumeSafe object StrategyName

/** Where a session works (8.1).
  *
  * A proposal for the review: the table of 8.1 names these cases, but the design gives no type. A path is a text, so
  * the configuration has the same JSON on each host.
  */
@assumeSafe enum Scope derives CanEqual:

  /** The home of the user: the scope of the controller. */
  case Home(path: String)

  case Directory(path: String)

  /** A git worktree at `path` with the branch `branch` of the repository `repo`. In `start`, the path is empty, and the
    * host makes the worktree in a folder of its own from the branch `base` (8.7). Only such a worktree can go in the
    * release plan of its session (7.9).
    */
  case Worktree(path: String, branch: String, repo: String, base: String)

  /** A new directory that only this session uses. In `start`, the path is empty, and the host makes the folder (8.7).
    */
  case Scratch(path: String)

  /** A copy at `path` of the workspace at `from`: the scope of a rollout, or of a child that does not share the scope
    * of its parent.
    */
  case Fork(path: String, from: String)

@assumeSafe object Scope

/** A reference to another session, for `tell`, `observe` and `zoom` (8.2). It is plain data, so the store can hold it.
  */
@assumeSafe final case class SessionRef(id: String) derives CanEqual

@assumeSafe object SessionRef

/** What `list` shows about a session (8.2, 10.2). The catalog row gives all of it, so `list` reads no journal (8.7):
  * the id, the goal, the scope, the owner, the parent of a fork, the status, the last `seq`, and the part of the ledger
  * that the controller needs. The owner and the status are words: the owner is `the person`, `the session ID`, `an
  * optimizer` or `a cell`, and the status is `idle`, `in a turn`, `stopping`, `releasing` or `ended` with the outcome,
  * for example `ended (merged)`.
  *
  * A proposal for the review: the design names the fields and gives no type. The goal is empty for a session with no
  * goal, and the scope is none before the first `Activated`. From the ledger: `read` is the number of the files that
  * the session read, `made` the paths that it wrote or edited, `commands` the number of its commands, and `head` and
  * `clean` the state of its scope after its last turn. The decisions and the open questions of CODE_HARNESS.md 7.4 come
  * with the graph of phase 10.
  */
final case class SessionCard(
    session : SessionRef,
    goal    : String,
    scope   : Option[Scope],
    owner   : String,
    parent  : Option[SessionRef],
    status  : String,
    lastSeq : Long,
    read    : Int,
    made    : Vector[String],
    commands: Int,
    head    : Option[String],
    clean   : Option[Boolean]
) derives CanEqual
