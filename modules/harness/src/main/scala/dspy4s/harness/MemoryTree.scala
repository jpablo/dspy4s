package dspy4s.harness

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.harness.guest.StrategyName
import dspy4s.harness.kernel.Logged
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.Signature
import zio.blocks.schema.Schema

import java.util.concurrent.ConcurrentHashMap
import scala.annotation.nowarn

/** The input of the leaf `compress` (CODE_HARNESS.md, 6.1): one message, longer than a line. The strategy cuts it to
  * `MemoryTree.MaxSource` characters first, so a very long message does not fill the context of the model.
  */
final case class CompressInput(message: String) derives Schema

/** The input of the leaf `merge` (6.1): the lines of two adjacent nodes, oldest first. */
final case class MergeInput(older: String, newer: String) derives Schema

/** The output of `compress` and `merge` (6.1): one summary line. `MemoryLine.of` cuts it to 512 bytes. */
final case class Summary(line: String) derives Schema

/** The built lines of one session (CODE_HARNESS_DESIGN.md, 7 and 7.1): derived data with a journal of its own. The host
  * keeps them in the session file, so a reopen makes no new LM call. A program call keeps them in memory.
  *
  * A key holds the digest of the texts under the node, so a line of other messages is never used. A line never changes
  * (the law Immutability): `putAll` keeps the line of a key that has one.
  */
trait LineStore:
  def get(key: LineKey): Option[MemoryLine]

  /** Adds the lines of one update, in one transaction when the store has a file. */
  def putAll(lines: Vector[(LineKey, MemoryLine)]): Unit

object LineStore:

  /** A store in memory, for a program call and for the laws. */
  def inMemory(): LineStore =
    new LineStore:
      private val lines                                      = ConcurrentHashMap[LineKey, MemoryLine]()
      def get(key: LineKey): Option[MemoryLine]              = Option(lines.get(key))
      def putAll(added: Vector[(LineKey, MemoryLine)]): Unit =
        added.foreach((key, line) => lines.putIfAbsent(key, line): Unit)

/** The derived data of the memory tree (7.1): the memory, and the view of the messages before the open turn. */
final case class MemoryState(memory: Memory, view: View) derives CanEqual

/** What `zoom` of a cell reads (7.1): the derived data of the strategy after the last policy call. The effects set a
  * new reader after each update of the worker, and a cell runs only after that update, so a volatile reader is enough.
  * With no reader, `zoom` gives the error of a session with no memory tree.
  */
final class ZoomSource:

  /** The text of the part `ref` of the memory, or an error with a plain message. */
  def zoom(ref: String): Either[String, String] = ???

  /** Sets the reader of the derived data after an update. */
  def setReader(read: String => Either[String, String]): Unit = ???

/** The memory tree (CODE_HARNESS.md, 6; CODE_HARNESS_DESIGN.md, 7.1): the strategy of a long work session.
  *
  *   - `update` appends the new messages of the journal (`MemoryLog`), builds each ready node, and advances the view to
  *     `MemoryLog.beforeOpenTurn` with `View.advance` and `high`. So two updates give the same state as one update on
  *     the full journal. A node gets the line of the store for its key, or a new line: a message of 512 bytes or less
  *     is its own line, `compress` makes the line of a longer message, and `merge` makes the line of a node from the
  *     lines of its halves. The new lines of one update go to the store with one `putAll`.
  *   - A leaf runs through `leafRunner`, so a hard stop cuts it, and each call spends one call of `summaries`, the
  *     budget of the summaries of the turn, apart from the model calls of the policy. A failed call gives the line of
  *     the source (`MemoryLine.of` of the message, or of the two lines), and that line goes to the store, so a bad
  *     message does not block the session. A spent budget or a stop gives the same line, but not to the store, so a
  *     later update builds the node again.
  *   - `project` reserves `MemoryTree.room(high)` characters of the budget for the view first, and never cuts or drops
  *     it, so the prompt cache keeps it. The view is the first message of the history, with `render`. The rest of the
  *     budget goes to the open turn, as the window strategy gives it. The view stays the same during a turn.
  *   - `zoom` gives the message of a node of level 0, or the lines of the two halves of a built node, each with its
  *     reference.
  */
@nowarn("msg=unused") // the skeleton: the proof of phase 8 uses the store, the runner, the budget and the size
final class MemoryTree(
    compress  : Predict[CompressInput, Summary],
    merge     : Predict[MergeInput, Summary],
    lines     : LineStore,
    high      : Int,
    leafRunner: PolicyRunner,
    summaries : () => Budget
) extends ContextStrategy:
  type D = MemoryState

  val name: StrategyName = StrategyName.MemoryTree

  def empty: MemoryState = MemoryState(Memory.empty, View.empty)

  def update(journal: Vector[Logged], d: MemoryState)(using RuntimeContext): Either[DspyError, MemoryState] = ???

  def project(journal: Vector[Logged], d: MemoryState, budget: Int): Projection = ???

  override def zoom(d: MemoryState, ref: String): Either[String, String] = ???

object MemoryTree:

  /** The default size of a view, in parts: about 12,600 characters of the budget of 40,000 (7.1). */
  val DefaultHigh = 24

  /** The most characters of a message that `compress` gets. */
  val MaxSource = 16_000

  /** The instructions of `compress`. */
  val compressInstructions: String =
    "Summarize the message in one line of at most 400 characters. Keep each name, number, path, decision and error."

  /** The instructions of `merge`. */
  val mergeInstructions: String =
    "Merge the two summary lines, the older one first, into one line of at most 400 characters. Keep each name, " +
      "number, path, decision and error that a later step can need."

  /** A memory tree with the two leaves of these instructions, the store `lines`, a view of at most `high` parts, the
    * leafRunner of the activation, and the budget of the summaries of the turn. A program call has
    * `PolicyRunner.Direct`.
    */
  def withLeaves(
      lines     : LineStore,
      high      : Int          = DefaultHigh,
      leafRunner: PolicyRunner = PolicyRunner.Direct,
      summaries : () => Budget = () => Budget(Int.MaxValue)
  ): MemoryTree =
    val compress = Signature.derived[CompressInput, Summary]("compress", compressInstructions)
    val merge    = Signature.derived[MergeInput, Summary]("merge", mergeInstructions)
    MemoryTree(
      Predict(compress, name = Some("compress")),
      Predict(merge, name = Some("merge")),
      lines,
      high,
      leafRunner,
      summaries
    )

  /** The characters that a view of `high` parts can take: each line of 512 bytes, and its reference. */
  def room(high: Int): Int = ???

  /** The text of a view: one line for each part, `[ref] line`, oldest first. A newline of a line becomes a space, so
    * each part is one line of the text. A part with no built line gives none.
    */
  def render(m: Memory, v: View): String = ???
