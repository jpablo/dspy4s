package dspy4s.harness

import dspy4s.core.contracts.{DspyError, DynamicValues, History, RuntimeContext}
import dspy4s.harness.guest.StrategyName
import dspy4s.harness.kernel.Logged
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.Signature
import zio.blocks.schema.{DynamicValue, Schema}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentHashMap
import scala.annotation.tailrec

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
  @volatile private var reader: String => Either[String, String] = _ => Left(ContextStrategy.NoMemory)

  /** The text of the part `ref` of the memory, or an error with a plain message. */
  def zoom(ref: String): Either[String, String] = reader(ref)

  /** Sets the reader of the derived data after an update. */
  def setReader(read: String => Either[String, String]): Unit = reader = read

/** The memory tree (CODE_HARNESS.md, 6; CODE_HARNESS_DESIGN.md, 7.1): the strategy of a long work session.
  *
  *   - `update` appends the new messages of the journal (`MemoryLog`), builds each ready node, and advances the view to
  *     `MemoryLog.beforeOpenTurn` with `View.advance` and `high`. So two updates give the same state as one update on
  *     the full journal. A node gets the line of the store for its key, or a new line: a message of 512 bytes or less
  *     is its own line, `compress` makes the line of a longer message, and `merge` makes the line of a node from the
  *     lines of its halves. The nodes of one round do not need each other, so their leaf calls run at the same time, at
  *     most `width` at a time. The new lines of each round go to the store with one `putAll`, so a long update keeps
  *     the rounds that it ended.
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
final class MemoryTree(
    compress  : Predict[CompressInput, Summary],
    merge     : Predict[MergeInput, Summary],
    lines     : LineStore,
    high      : Int,
    leafRunner: PolicyRunner,
    summaries : () => Budget,
    width     : Int
) extends ContextStrategy:
  type D = MemoryState

  val name: StrategyName = StrategyName.MemoryTree

  def empty: MemoryState = MemoryState(Memory.empty, View.empty)

  def update(journal: Vector[Logged], d: MemoryState)(using RuntimeContext): Either[DspyError, MemoryState] =
    val added  = MemoryLog.messages(journal).drop(d.memory.log.size)
    val memory = settle(added.foldLeft(d.memory)(Memory.append), summaries())
    Right(MemoryState(memory, View.advance(d.view, memory, high, MemoryLog.beforeOpenTurn(journal))))

  def project(journal: Vector[Logged], d: MemoryState, budget: Int): Projection =
    val open = Window.openTurn(journal, (budget - MemoryTree.room(high)).max(0))
    val view = MemoryTree.render(d.memory, d.view)
    if view.isEmpty then open
    else
      val first = DynamicValues.record(
        "observation" -> DynamicValues.string(s"${MemoryTree.Header}\n$view"),
        "reasoning"   -> DynamicValues.string(""),
        "text"        -> DynamicValues.string("")
      )
      open.copy(history = History(first +: open.history.messages))

  /** The text of the part `ref` of the memory: the message of a part of level 0, or the lines of its two halves with
    * their references. A part that the memory does not have gives an error with the top parts (`Memory.roots`), so the
    * model can zoom one of them and go down, and does not guess a reference.
    */
  override def zoom(d: MemoryState, ref: String): Either[String, String] =
    NodeId.parse(ref) match
      case None     => Left(s"$ref is not a reference of the memory. A reference is level.index, for example 3.5.")
      case Some(id) => (Memory.zoom(d.memory, id), id.halves) match
          case (Some(Left(message)), _)                    => Right(message.text)
          case (Some(Right((older, newer))), Some((a, b))) =>
            Right(s"[${a.ref}] ${older.text}\n[${b.ref}] ${newer.text}")
          case _ => Left(MemoryTree.noPart(d.memory, ref))

  /** Builds the ready nodes in rounds until no node is ready. The nodes of one round need only the nodes of the rounds
    * before, so their lines come at the same time, at most `width` at a time, and they build in the order of `ready`.
    * The new lines of each round go to the store with one `putAll`.
    */
  @tailrec
  private def settle(m: Memory, budget: Budget)(using RuntimeContext): Memory =
    val ready = Memory.ready(m)
    if ready.isEmpty then m
    else
      val made = inBatch(ready, width)(id => lineOf(m, id, budget)).fold((_, error) => throw error, identity)
      val kept = made.flatMap(_._2)
      if kept.nonEmpty then lines.putAll(kept)
      settle(ready.zip(made).foldLeft(m) { case (acc, (id, (line, _))) => Memory.buildNode(acc, id, line) }, budget)

  /** The line of the ready node `id`, and the entry for the store, if any. The store comes first. A short message is
    * its own line. A leaf call spends one call of `budget`. A failed call gives the line of the source, for the store.
    * A spent budget or a stop gives the line of the source, but not for the store, so a later update tries again.
    */
  private def lineOf(m: Memory, id: NodeId, budget: Budget)(using
      RuntimeContext
  ): (MemoryLine, Option[(LineKey, MemoryLine)]) =
    val key    = Memory.key(m, id)
    val source = sourceText(m, id)
    key.flatMap(lines.get) match
      case Some(stored)                                                                  => (stored, None)
      case None if id.level == 0 && source.getBytes(UTF_8).length <= MemoryLine.MaxBytes =>
        (MemoryLine.of(source), None)
      case None =>
        val fallback = MemoryLine.of(source)
        if budget.spend().isLeft then (fallback, None)
        else
          summarize(m, id) match
            case Right(text) =>
              val line = MemoryLine.of(text)
              (line, key.map(_ -> line))
            case Left(PolicyRunner.Stopped) => (fallback, None)
            case Left(_)                    => (fallback, key.map(_ -> fallback))

  /** The text of a node with no leaf: its message, or the lines of its two halves. */
  private def sourceText(m: Memory, id: NodeId): String =
    Memory.zoom(m, id) match
      case Some(Left(message))         => message.text
      case Some(Right((older, newer))) => s"${older.text} ${newer.text}"
      case None                        => ""

  /** The line of a leaf for `id`: `compress` of its message, or `merge` of the lines of its halves. */
  private def summarize(m: Memory, id: NodeId)(using RuntimeContext): Either[String, String] =
    Memory.zoom(m, id) match
      case Some(Left(message)) =>
        leafRunner.run(() => compress(CompressInput(MemoryTree.compressSource(message.text))).left.map(_.message))
          .map(_.output.line)
      case Some(Right((older, newer))) =>
        leafRunner.run(() => merge(MergeInput(older.text, newer.text)).left.map(_.message)).map(_.output.line)
      case None => Left(s"the node ${id.ref} has no source")

object MemoryTree:

  /** The default size of a view, in parts: about 12,600 characters of the budget of 40,000 (7.1). */
  val DefaultHigh = 24

  /** The most characters of a message that `compress` gets. */
  val MaxSource = 16_000

  /** The text of a message for a compress call: at most `MaxSource` units, with each character whole, because the text
    * goes to a model.
    */
  private[harness] def compressSource(text: String): String = textPrefix(text, MaxSource)

  /** The instructions of `compress`. */
  val compressInstructions: String =
    "Summarize the message in one line of at most 400 characters. Keep each name, number, path, decision and error."

  /** The instructions of `merge`. */
  val mergeInstructions: String =
    "Merge the two summary lines, the older one first, into one line of at most 400 characters. Keep each name, " +
      "number, path, decision and error that a later step can need."

  /** A memory tree with the two leaves of these instructions, the store `lines`, a view of at most `high` parts, the
    * runner of the activation, the budget of the summaries of the turn, and at most `width` leaf calls at a time. A
    * program call has `PolicyRunner.Direct`. Each leaf request has the option bag `options` of the provider, for
    * example `{"chat_template_kwargs": {"enable_thinking": false}}` for a model of vLLM that reasons: a summary needs
    * no reasoning, and the reasoning made a merge 50 times slower in the live run.
    */
  def withLeaves(
      lines     : LineStore,
      high      : Int                 = DefaultHigh,
      leafRunner: PolicyRunner        = PolicyRunner.Direct,
      summaries : () => Budget        = () => Budget(Int.MaxValue),
      width     : Int                 = 4,
      options   : DynamicValue.Record = DynamicValue.Record.empty
  ): MemoryTree =
    val compress = Signature.derived[CompressInput, Summary]("compress", compressInstructions)
    val merge    = Signature.derived[MergeInput, Summary]("merge", mergeInstructions)
    MemoryTree(
      Predict(compress, name = Some("compress"), config = options),
      Predict(merge, name = Some("merge"), config = options),
      lines,
      high,
      leafRunner,
      summaries,
      width.max(1)
    )

  /** The error of a zoom into the part `ref`, which the memory `m` does not have. It names the top parts of the memory,
    * so the model can zoom one of them and go down. A memory with no message gives an error that says so.
    */
  private[harness] def noPart(m: Memory, ref: String): String =
    val tops = Memory.roots(m.log.size.toLong).map(_.ref)
    if tops.isEmpty then s"the memory has no part $ref, because the memory is empty"
    else
      val named =
        if tops.size == 1 then s"The top part of the memory is ${tops.head}"
        else s"The top parts of the memory are ${tops.mkString(", ")}"
      s"the memory has no part $ref yet. $named. A zoom of a part shows its two halves with their references, or the " +
        "message of a part of level 0. So zoom a top part, and then zoom a half of it."

  /** The characters that a view of `high` parts can take: each line of 512 bytes, and its reference. */
  def room(high: Int): Int = high * (MemoryLine.MaxBytes + 32) + Header.length

  /** The first line of the view in the history. */
  val Header: String =
    "The memory of this session before this turn, oldest first. Each line is [ref] summary. Read a part with zoom(ref)."

  /** The text of a view: one line for each part, `[ref] line`, oldest first. A newline of a line becomes a space, so
    * each part is one line of the text. A part with no built line gives none.
    */
  def render(m: Memory, v: View): String =
    v.parts
      .flatMap(id => m.built.get(id).map(line => s"[${id.ref}] ${line.text.replace('\n', ' ').replace('\r', ' ')}"))
      .mkString("\n")
