package dspy4s.harness

import dspy4s.harness.kernel.{Logged, Source}

/** What one message of the memory log is (CODE_HARNESS_DESIGN.md, 7.1): a message of the person or of the harness, a
  * cell with its output, or the reply of a turn.
  */
enum MessageKind derives CanEqual:
  case Heard(source: Source)
  case Cell
  case Reply

/** One message of the memory log (7.1): one step of the session. `at` is the time of its last entry, in milliseconds.
  *
  * A proposal for the review: the id of a message is its `Long` index in the log, not an opaque type `MsgId`.
  */
final case class MemoryMessage(kind: MessageKind, text: String, at: Long) derives CanEqual

/** A node of the memory tree (CODE_HARNESS.md, 6.1). The node `(level, index)` covers the messages
  * `[index * 2^level, (index + 1) * 2^level)`. A node of level 0 is one message. A node has a level from 0 to
  * `NodeId.MaxLevel` and an index below `2^(MaxLevel - level)`, so its span fits a `Long`.
  */
final case class NodeId(level: Int, index: Long) derives CanEqual:

  /** The first message of the node, and the message after its last one. */
  def span: (Long, Long) = ???

  /** The two nodes one level down that cover this node, oldest first. None at level 0. */
  def halves: Option[(NodeId, NodeId)] = ???

  /** The node one level up that covers this node. */
  def parent: NodeId = ???

  /** The reference that a view shows and that `zoom` reads: `level.index`, for example `3.5`. */
  def ref: String = ???

object NodeId:

  /** The highest level. A higher level would not fit the span in a `Long`. */
  val MaxLevel = 62

  /** The node of a reference `level.index`, or None for a text of another form, or a level or an index out of the
    * limits. The model writes the reference, so each text is possible.
    */
  def parse(ref: String): Option[NodeId] = ???

/** A summary line of a node (6.1): at most `MemoryLine.MaxBytes` bytes of UTF-8. Only `MemoryLine.of` makes one. */
final case class MemoryLine private (text: String) derives CanEqual

object MemoryLine:
  val MaxBytes = 512

  /** The line of `text`: the text itself when it has at most `MaxBytes` bytes of UTF-8, and else its first bytes up to
    * the end of a character. So a leaf never gives a longer line.
    */
  def of(text: String): MemoryLine = ???

/** The memory of a session (6.1): the log of messages and the built lines. It is a pure value, so a fork shares both.
  */
final case class Memory(log: Vector[MemoryMessage], built: Map[NodeId, MemoryLine]) derives CanEqual

object Memory:
  val empty: Memory = Memory(Vector.empty, Map.empty)

  /** The memory with `message` at the end of its log. */
  def append(m: Memory, message: MemoryMessage): Memory = ???

  /** The nodes that can build now, in the order of their spans: each message with no line, and each node not built
    * whose span is in the log and whose two halves are built.
    */
  def ready(m: Memory): Vector[NodeId] = ???

  /** The memory with `line` for the node `id`. A node that is not ready gives the same memory, so a built node never
    * changes, and a node builds only after its halves (the laws Immutability and Order).
    */
  def buildNode(m: Memory, id: NodeId, line: MemoryLine): Memory = ???

  /** One node for each set bit of `n`, the highest level first. Their spans cover `[0, n)` in order (the law Roots).
    */
  def roots(n: Long): Vector[NodeId] = ???

  /** What the node `id` holds: its message at level 0, or the lines of its two halves. None for a node outside the log
    * or a node whose halves are not built.
    */
  def zoom(m: Memory, id: NodeId): Option[Either[MemoryMessage, (MemoryLine, MemoryLine)]] = ???

  /** The memory of the first `n` messages, with each built node whose span ends at `n` or before. */
  def fork(m: Memory, n: Long): Memory = ???

  /** The key of the line of `id` in a store: the node and a SHA-256 of the texts of its messages. A store line with
    * another digest belongs to other messages, so the strategy does not use it. None for a node outside the log.
    */
  def key(m: Memory, id: NodeId): Option[LineKey] = ???

/** The key of a stored line (7.1): the node, and the SHA-256 in hex of the texts of the messages that it covers. */
final case class LineKey(id: NodeId, digest: String) derives CanEqual

/** A cut through the tree (6.1): nodes whose spans follow each other and cover `[0, upTo)`, oldest first. */
final case class View(parts: Vector[NodeId]) derives CanEqual:

  /** The message after the last one of the view: 0 for an empty view. */
  def upTo: Long = ???

object View:
  val empty: View = View(Vector.empty)

  /** The view with one node of level 0 for each message from `v.upTo` to `until`, at its end. A view that already
    * covers `until` stays the same.
    */
  def extend(v: View, until: Long): View = ???

  /** The view with the two halves of `id` in place of `id`: a zoom into the view. A view with no part `id`, or a node
    * of level 0, gives the same view.
    */
  def split(v: View, id: NodeId): View = ???

  /** The view with `id` in place of its two halves, when they are adjacent parts of the view and `id` is built in `m`.
    * Else it gives the same view. For a built part `p`, `join(split(v, p), m, p) == v` (the law Zoom).
    */
  def join(v: View, m: Memory, id: NodeId): View = ???

  /** The view with at most `high` parts, when the parents that it needs are built. A view of `high` parts or fewer
    * stays the same. A longer view joins two adjacent halves of a built node, the oldest pair first, until it has at
    * most `high / 2` parts or no pair can join.
    */
  def fit(v: View, m: Memory, high: Int): View = ???

  /** The view after one `extend` and one `fit` for each message from `v.upTo + 1` to `until`. The result of `fit`
    * depends on the earlier view, so the view of a session is `advance(View.empty, m, high, n)`, and it depends only on
    * `n`: `advance(advance(v, m, high, k), m, high, n) == advance(v, m, high, n)`. The strategy keeps the view of the
    * last update, and it advances that view to the new messages.
    */
  def advance(v: View, m: Memory, high: Int, until: Long): View = ???

/** The memory log from a journal (7.1): one message for each step. */
object MemoryLog:

  /** The messages of `journal`, oldest first:
    *
    *   - each `Input`, as `Heard` with its source, at the place of the `Input`;
    *   - each `Acted` with code and the `Observed` after it, as one `Cell` message with the text, the code and the
    *     output, at the place of the `Observed`. So an `Input` that comes during the cell, for example the report of a
    *     job, comes before the `Cell` message. A cell that did not end, also after a crash, gives no message;
    *   - each `TurnEnded`, as `Reply` with the text of `Replied`, or a note of the other outcome.
    *
    * The control entries give no message. For a journal `j` and more entries `k`, `messages(j ++ k)` starts with
    * `messages(j)`. The store keys a line by the texts of its messages, so a change of this function does not give old
    * lines to other messages.
    */
  def messages(journal: Vector[Logged]): Vector[MemoryMessage] = ???

  /** The number of messages up to and with the `Reply` of the last `TurnEnded`, or 0 with no `TurnEnded`. The view of a
    * step covers these messages, so it stays the same from the first `Input` of a turn to its end. An `Input` that
    * comes before the last `TurnEnded` is in the view, and the window part can show it too.
    */
  def beforeOpenTurn(journal: Vector[Logged]): Long = ???
