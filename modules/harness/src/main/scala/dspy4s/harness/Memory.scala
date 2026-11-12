package dspy4s.harness

import dspy4s.harness.kernel.{Entry, Logged, Source, TurnOutcome}

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import scala.annotation.tailrec

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
  def span: (Long, Long) = (index << level, (index + 1) << level)

  /** The two nodes one level down that cover this node, oldest first. None at level 0. */
  def halves: Option[(NodeId, NodeId)] =
    Option.when(level > 0)((NodeId(level - 1, 2 * index), NodeId(level - 1, 2 * index + 1)))

  /** The node one level up that covers this node. */
  def parent: NodeId = NodeId(level + 1, index / 2)

  /** The reference that a view shows and that `zoom` reads: `level.index`, for example `3.5`. */
  def ref: String = s"$level.$index"

  /** True when the level and the index are in the limits of `NodeId`. */
  def valid: Boolean = level >= 0 && level <= NodeId.MaxLevel && index >= 0 && index < (1L << (NodeId.MaxLevel - level))

object NodeId:

  /** The highest level. A higher level would not fit the span in a `Long`. */
  val MaxLevel = 62

  /** The node of a reference `level.index`, or None for a text of another form, or a level or an index out of the
    * limits. The model writes the reference, so each text is possible.
    */
  def parse(ref: String): Option[NodeId] =
    ref match
      case Reference(level, index) =>
        for
          l  <- level.toIntOption
          i  <- index.toLongOption
          id <- Some(NodeId(l, i)).filter(_.valid)
        yield id
      case _ => None

  /** A reference with no sign and no leading zero, so each node has one text. */
  private val Reference = """(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,18})""".r

/** A summary line of a node (6.1): at most `MemoryLine.MaxBytes` bytes of UTF-8. Only `MemoryLine.of` makes one. */
final case class MemoryLine private (text: String) derives CanEqual

object MemoryLine:
  val MaxBytes = 512

  /** The line of `text`: the text itself when it has at most `MaxBytes` bytes of UTF-8, and else its first bytes up to
    * the end of a character. So a leaf never gives a longer line.
    */
  def of(text: String): MemoryLine =
    if text.getBytes(UTF_8).length <= MaxBytes then MemoryLine(text)
    else MemoryLine(text.substring(0, cut(text, 0, 0)))

  /** The end of the longest start of `text` from `at` with at most `MaxBytes` bytes, after `bytes` bytes. */
  @tailrec
  private def cut(text: String, at: Int, bytes: Int): Int =
    if at >= text.length then at
    else
      val point = text.codePointAt(at)
      val size  = if point < 0x80 then 1 else if point < 0x800 then 2 else if point < 0x10000 then 3 else 4
      if bytes + size > MaxBytes then at else cut(text, at + Character.charCount(point), bytes + size)

/** The memory of a session (6.1): the log of messages and the built lines. It is a pure value, so a fork shares both.
  */
final case class Memory(log: Vector[MemoryMessage], built: Map[NodeId, MemoryLine]) derives CanEqual

object Memory:
  val empty: Memory = Memory(Vector.empty, Map.empty)

  /** The memory with `message` at the end of its log. */
  def append(m: Memory, message: MemoryMessage): Memory = m.copy(log = m.log :+ message)

  /** The nodes that can build now, in the order of their spans: each message with no line, and each node not built
    * whose span is in the log and whose two halves are built.
    */
  def ready(m: Memory): Vector[NodeId] =
    val messages = m.log.indices.iterator.map(i => NodeId(0, i.toLong)).filterNot(m.built.contains)
    val parents  = m.built.keysIterator.filter(_.level < NodeId.MaxLevel).map(_.parent).filter(isReady(m, _))
    (messages ++ parents).toVector.distinct.sortBy(id => (id.span._2, id.level))

  /** True when `id` is not built, its span is in the log, and it is a message or its two halves are built. */
  private def isReady(m: Memory, id: NodeId): Boolean =
    id.valid && !m.built.contains(id) && id.span._2 <= m.log.size &&
      id.halves.forall((a, b) => m.built.contains(a) && m.built.contains(b))

  /** The memory with `line` for the node `id`. A node that is not ready gives the same memory, so a built node never
    * changes, and a node builds only after its halves (the laws Immutability and Order).
    */
  def buildNode(m: Memory, id: NodeId, line: MemoryLine): Memory =
    if isReady(m, id) then m.copy(built = m.built.updated(id, line)) else m

  /** One node for each set bit of `n`, the highest level first. Their spans cover `[0, n)` in order (the law Roots).
    */
  def roots(n: Long): Vector[NodeId] = (NodeId.MaxLevel to 0 by -1)
    .foldLeft((Vector.empty[NodeId], 0L)) { case ((nodes, start), level) =>
      if (n & (1L << level)) != 0 then (nodes :+ NodeId(level, start >> level), start + (1L << level))
      else (nodes, start)
    }
    ._1

  /** What the node `id` holds: its message at level 0, or the lines of its two halves. None for a node outside the log
    * or a node whose halves are not built.
    */
  def zoom(m: Memory, id: NodeId): Option[Either[MemoryMessage, (MemoryLine, MemoryLine)]] =
    if !id.valid || id.span._2 > m.log.size then None
    else
      id.halves match
        case None         => Some(Left(m.log(id.index.toInt)))
        case Some((a, b)) =>
          for
            older <- m.built.get(a)
            newer <- m.built.get(b)
          yield Right((older, newer))

  /** The memory of the first `n` messages, with each built node whose span ends at `n` or before. */
  def fork(m: Memory, n: Long): Memory =
    Memory(m.log.take(n.max(0L).min(Int.MaxValue.toLong).toInt), m.built.filter((id, _) => id.span._2 <= n))

  /** The key of the line of `id` in a store: the node and a SHA-256 of the texts of its messages. A store line with
    * another digest belongs to other messages, so the strategy does not use it. None for a node outside the log.
    */
  def key(m: Memory, id: NodeId): Option[LineKey] =
    Option.when(id.valid && id.span._2 <= m.log.size) {
      val digest = MessageDigest.getInstance("SHA-256")
      // Each part has its length first, so two other texts never give the same bytes.
      m.log.slice(id.span._1.toInt, id.span._2.toInt).foreach { message =>
        Vector(message.kind.toString, message.text).foreach { part =>
          val bytes = part.getBytes(UTF_8)
          digest.update(s"${bytes.length}:".getBytes(UTF_8))
          digest.update(bytes)
        }
      }
      LineKey(id, digest.digest().map(b => f"$b%02x").mkString)
    }

/** The key of a stored line (7.1): the node, and the SHA-256 in hex of the texts of the messages that it covers. */
final case class LineKey(id: NodeId, digest: String) derives CanEqual

/** A cut through the tree (6.1): nodes whose spans follow each other and cover `[0, upTo)`, oldest first. */
final case class View(parts: Vector[NodeId]) derives CanEqual:

  /** The message after the last one of the view: 0 for an empty view. */
  def upTo: Long = parts.lastOption.fold(0L)(_.span._2)

object View:
  val empty: View = View(Vector.empty)

  /** The view with one node of level 0 for each message from `v.upTo` to `until`, at its end. A view that already
    * covers `until` stays the same.
    */
  def extend(v: View, until: Long): View =
    if until <= v.upTo then v else View(v.parts ++ (v.upTo until until).map(NodeId(0, _)))

  /** The view with the two halves of `id` in place of `id`: a zoom into the view. A view with no part `id`, or a node
    * of level 0, gives the same view.
    */
  def split(v: View, id: NodeId): View =
    (v.parts.indexOf(id), id.halves) match
      case (at, Some((a, b))) if at >= 0 => View(v.parts.patch(at, Vector(a, b), 1))
      case _                             => v

  /** The view with `id` in place of its two halves, when they are adjacent parts of the view and `id` is built in `m`.
    * Else it gives the same view. For a built part `p`, `join(split(v, p), m, p) == v` (the law Zoom).
    */
  def join(v: View, m: Memory, id: NodeId): View =
    id.halves match
      case Some((a, b)) if m.built.contains(id) =>
        val at = v.parts.indexOf(a)
        if at >= 0 && v.parts.lift(at + 1).contains(b) then View(v.parts.patch(at, Vector(id), 2)) else v
      case _ => v

  /** The view with at most `high` parts, when the parents that it needs are built. A view of `high` parts or fewer
    * stays the same. A longer view joins two adjacent halves of a built node, the oldest pair first, until it has at
    * most `high / 2` parts or no pair can join.
    */
  def fit(v: View, m: Memory, high: Int): View =
    // One pass with a stack, the newest part on top. The parts under the top have no pair that can join, so the
    // pair of the top two is always the oldest pair that can join.
    @tailrec
    def go(stack: List[NodeId], rest: List[NodeId], size: Int): Vector[NodeId] =
      if size <= high / 2 then (stack.reverse ++ rest).toVector
      else
        stack match
          case newer :: older :: below if joins(older, newer, m) => go(older.parent :: below, rest, size - 1)
          case _                                                 => rest match
              case part :: more => go(part :: stack, more, size)
              case Nil          => stack.reverse.toVector
    if v.parts.size <= high then v else View(go(Nil, v.parts.toList, v.parts.size))

  /** True when `older` and `newer` are the two halves of a node that `m` has built. */
  private def joins(older: NodeId, newer: NodeId, m: Memory): Boolean =
    older.level == newer.level && older.index % 2 == 0 && newer.index == older.index + 1 &&
      m.built.contains(older.parent)

  /** The view after one `extend` and one `fit` for each message from `v.upTo + 1` to `until`. The result of `fit`
    * depends on the earlier view, so the view of a session is `advance(View.empty, m, high, n)`, and it depends only on
    * `n`: `advance(advance(v, m, high, k), m, high, n) == advance(v, m, high, n)`. The strategy keeps the view of the
    * last update, and it advances that view to the new messages.
    */
  def advance(v: View, m: Memory, high: Int, until: Long): View = (v.upTo + 1 to until).foldLeft(v)((view, count) =>
    fit(extend(view, count), m, high)
  )

/** The memory log from a journal (7.1): one message for each step. */
object MemoryLog:

  /** The messages of `journal`, oldest first:
    *
    *   - each `Input`, as `Heard` with its source, at the place of the `Input`;
    *   - each `Acted` with code and the `Observed` after it, as one `Cell` message with the text, the code and the
    *     output, at the place of the `Observed`. So an `Input` that comes during the cell, for example the report of a
    *     job, comes before the `Cell` message. A cell that did not end, also after a crash, gives no message;
    *   - each `TurnEnded`, as `Reply` with the text of `Replied`, or the text of the report of another outcome
    *     (`TurnOutcome.report`). So the model reads the outcome in words, and not as a Scala value.
    *
    * The control entries give no message. For a journal `j` and more entries `k`, `messages(j ++ k)` starts with
    * `messages(j)`. The store keys a line by the texts of its messages, so a change of this function does not give old
    * lines to other messages. A new text of a message gives new keys, so the memory of an existing session builds the
    * lines of the nodes that cover that message again, one time, with new calls of the model.
    */
  def messages(journal: Vector[Logged]): Vector[MemoryMessage] = walk(journal).messages

  /** The number of messages up to and with the `Reply` of the last `TurnEnded`, or 0 with no `TurnEnded`. The view of a
    * step covers these messages, so it stays the same from the first `Input` of a turn to its end. An `Input` that
    * comes before the last `TurnEnded` is in the view, and the window part can show it too.
    */
  def beforeOpenTurn(journal: Vector[Logged]): Long = walk(journal).replied.toLong

  /** The fold of a journal: the messages, the cell that waits for its output, and the number of messages up to the last
    * reply.
    */
  private final case class Walk(messages: Vector[MemoryMessage], cell: Option[(String, String)], replied: Int)

  private def walk(journal: Vector[Logged]): Walk =
    journal.foldLeft(Walk(Vector.empty, None, 0)) { (w, logged) =>
      val at = logged.meta.atMillis
      logged.entry match
        case Entry.Input(_, text, _) =>
          w.copy(messages = w.messages :+ MemoryMessage(MessageKind.Heard(logged.meta.source), text, at))
        case Entry.Acted(_, text, Some(code)) => w.copy(cell = Some((text, code)))
        case Entry.Observed(output, _, _)     => w.cell.fold(w) { (text, code) =>
            val body = Vector(text, s"```scala\n$code\n```", output).filter(_.nonEmpty).mkString("\n")
            w.copy(messages = w.messages :+ MemoryMessage(MessageKind.Cell, body, at), cell = None)
          }
        case Entry.TurnEnded(outcome) =>
          val reply = outcome match
            case TurnOutcome.Replied(text) => text
            case other                     => other.report
          val messages = w.messages :+ MemoryMessage(MessageKind.Reply, reply, at)
          Walk(messages, None, messages.size)
        case _ => w
    }
