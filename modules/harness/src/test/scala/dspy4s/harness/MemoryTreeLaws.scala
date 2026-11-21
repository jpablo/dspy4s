package dspy4s.harness

import dspy4s.core.contracts.{DynamicValues, RuntimeContext}
import dspy4s.harness.kernel.*
import dspy4s.lm.contracts.LmRequest
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.jdk.CollectionConverters.*

/** The laws of the memory tree strategy (CODE_HARNESS_DESIGN.md, 7 and 7.1). A fake model gives each summary as a fixed
  * function of its request, so two runs give the same lines.
  */
class MemoryTreeLaws extends munit.FunSuite:

  private val request = RequestId("FAKE-REQUEST")

  /** The limit of a small view, in characters. The size of a short message is about 40 characters, and the size of a
    * long cell, or of a part above level 0, is about 520 (`ViewLimit.lineChars`). So a chat of 12 turns with long cells
    * joins its oldest parts, and keeps its newest turns as parts of level 0.
    */
  private val SmallView = 6_000

  /** A view limit of 0 characters: each view is the roots of the tree, so each node of the tree is needed (7.2, choice
    * 9). The laws of the lines, the store and the counts use it, so they see each merge, as before choice 9.
    */
  private val AllMerges = 0

  /** A summary model: the line is a fixed function of the request, after `before` and with `after` around it. */
  private def summaries(before: String = "S", after: String = ""): RequestLm =
    RequestLm(text =>
      Right(s"[[ ## line ## ]]\n$before${(text.hashCode & 0x7fffffff) % 100000}$after\n\n[[ ## completed ## ]]")
    )

  /** A chat turn: a message, a cell with an output of `output` characters, and a reply. */
  private def turn(n: Int, output: Int): Vector[Entry] =
    Vector(
      Entry.Input(request, s"message $n", Map.empty),
      Entry.TurnStarted,
      Entry.StepStarted(0),
      Entry.Acted("", s"look $n", Some(s"println($n)")),
      Entry.Observed(s"out-$n " + ("x" * output), CellOutcome.Ok, Map.empty),
      Entry.StepStarted(1),
      Entry.Acted("", s"reply $n", None),
      Entry.TurnEnded(TurnOutcome.Replied(s"reply $n"))
    )

  private def chat(turns: Int, output: Int): Vector[Logged] =
    Support.logOf(
      1000L,
      (Entry.Activated(Harness.versions, Support.config(TurnKind.Chat)) +: (0 until turns).flatMap(turn(_, output)))*
    )

  /** `journal` and the first `k` entries of `extra`, with `seq` after the journal. */
  private def withOpen(journal: Vector[Logged], extra: Vector[Entry], k: Int): Vector[Logged] =
    journal ++ Support.logOf(5000L, extra.take(k)*).map(l => l.copy(seq = l.seq + journal.size))

  private def calls(lm: RequestLm, instructions: String): Int =
    lm.requests.asScala.count(_.messages.flatMap(_.text).exists(_.contains(instructions)))

  private def updated(tree: MemoryTree, journal: Vector[Logged], from: MemoryState)(using RuntimeContext): MemoryState =
    tree.update(journal, from).fold(e => fail(e.message), identity)

  /** A leaf runner that runs the first `allowed` calls, and then cuts each call while `stopping` is true. The runner of
    * a work session does the same after a stop: it cuts each later call of the update at once.
    */
  private final class StopAfter(allowed: Int) extends PolicyRunner:
    private val started    = AtomicInteger(0)
    @volatile var stopping = true

    def run[A](call: () => Either[String, A]): Either[String, A] =
      if stopping && started.getAndIncrement() >= allowed then Left(PolicyRunner.Stopped) else call()

  /** The strings of a message of the history, as the adapter gives them to the model. */
  private def strings(value: DynamicValue): Vector[String] =
    value match
      case DynamicValue.Primitive(PrimitiveValue.String(s)) => Vector(s)
      case record: DynamicValue.Record                      => DynamicValues.recordEntries(record).flatMap((_, v) => strings(v))
      case sequence: DynamicValue.Sequence                  => sequence.elements.toVector.flatMap(strings)
      case _                                                => Vector.empty

  test("Memory tree: a message of 512 bytes or less is its own line, with no compress call") {
    val lm = summaries()
    Models.withModel(lm) {
      val tree = MemoryTree.withLeaves(LineStore.inMemory())
      val d    = updated(tree, chat(3, output = 10), tree.empty)
      assertEquals(calls(lm, MemoryTree.compressInstructions), 0)
      d.memory.log.zipWithIndex.foreach { (message, i) =>
        assertEquals(d.memory.built.get(NodeId(0, i.toLong)), Some(MemoryLine.of(message.text)))
      }
    }
  }

  test("Memory tree: compress makes the line of each longer message, and merge makes the line of each parent") {
    val lm = summaries()
    Models.withModel(lm) {
      val tree = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = AllMerges)
      val d    = updated(tree, chat(4, output = 900), tree.empty)
      assertEquals(calls(lm, MemoryTree.compressInstructions), d.memory.log.count(_.text.getBytes(UTF_8).length > 512))
      assertEquals(calls(lm, MemoryTree.mergeInstructions), d.memory.built.keys.count(_.level > 0))
      assert(Memory.ready(d.memory).isEmpty, "a node that can build stays")
    }
  }

  test("Memory tree: two updates give the same memory and view as one update on the full journal") {
    Models.withModel(summaries()) {
      val tree    = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = SmallView / 2)
      val journal = withOpen(chat(6, output = 700), turn(6, 700), 5)
      val once    = updated(tree, journal, tree.empty)
      (0 to journal.size).foreach { k =>
        val twice = updated(tree, journal, updated(tree, journal.take(k), tree.empty))
        assertEquals(twice, once, s"split after $k entries")
      }
    }
  }

  test("Memory tree: a second tree with the same store makes no LM call for the lines that the store has") {
    val store = LineStore.inMemory()
    Models.withModel(summaries()) {
      val first = MemoryTree.withLeaves(store, viewChars = AllMerges)
      updated(first, chat(5, output = 700), first.empty): Unit
    }
    val again = summaries()
    Models.withModel(again) {
      val tree = MemoryTree.withLeaves(store, viewChars = AllMerges)
      updated(tree, chat(5, output = 700), tree.empty): Unit
    }
    assertEquals(again.requests.size, 0)
  }

  test("Memory tree: a store line of other messages is not used, because its key has another digest") {
    val store = LineStore.inMemory()
    store.putAll(Vector(LineKey(NodeId(0, 1), "FAKE-OTHER-DIGEST") -> MemoryLine.of("FAKE-WRONG-LINE")))
    Models.withModel(summaries()) {
      val tree = MemoryTree.withLeaves(store, viewChars = AllMerges)
      val d    = updated(tree, chat(2, output = 700), tree.empty)
      assert(!d.memory.built.values.exists(_.text == "FAKE-WRONG-LINE"), d.memory.built.toString)
    }
  }

  test("Memory tree: a failed leaf call gives the line of the source, and the store keeps it") {
    val store = LineStore.inMemory()
    val lm    = RequestLm(_ => Left("FAKE-FAILURE"))
    Models.withModel(lm) {
      val tree = MemoryTree.withLeaves(store, viewChars = AllMerges)
      val d    = updated(tree, chat(2, output = 700), tree.empty)
      assert(calls(lm, MemoryTree.compressInstructions) > 0, "no compress call failed")
      d.memory.log.zipWithIndex.foreach { (message, i) =>
        val id = NodeId(0, i.toLong)
        assertEquals(d.memory.built.get(id), Some(MemoryLine.of(message.text)))
        if message.text.getBytes(UTF_8).length > 512 then
          assertEquals(Memory.key(d.memory, id).flatMap(store.get), Some(MemoryLine.of(message.text)))
      }
    }
  }

  test("Memory tree: a spent summary budget gives the line of the source, and the store does not keep it") {
    val store = LineStore.inMemory()
    val lm    = summaries()
    Models.withModel(lm) {
      val tree = MemoryTree.withLeaves(store, viewChars = AllMerges, summaries = () => Budget(1))
      val d    = updated(tree, chat(3, output = 700), tree.empty)
      assertEquals(lm.requests.size, 1)
      val sourced = d.memory.log.zipWithIndex.filter((message, i) =>
        d.memory.built.get(NodeId(0, i.toLong)).contains(MemoryLine.of(message.text)) && message.text.length > 512
      )
      assert(sourced.nonEmpty, d.memory.built.toString)
      sourced.foreach((_, i) => assertEquals(Memory.key(d.memory, NodeId(0, i.toLong)).flatMap(store.get), None))
    }
  }

  test("Memory tree: a leaf call that a stop cuts gives the line of the source, and the store does not keep it") {
    val store   = LineStore.inMemory()
    val stopped = new PolicyRunner:
      def run[A](call: () => Either[String, A]): Either[String, A] = Left(PolicyRunner.Stopped)
    Models.withModel(summaries()) {
      val tree = MemoryTree.withLeaves(store, viewChars = AllMerges, leafRunner = stopped)
      val d    = updated(tree, chat(2, output = 700), tree.empty)
      d.memory.log.zipWithIndex.foreach { (message, i) =>
        val id = NodeId(0, i.toLong)
        assertEquals(d.memory.built.get(id), Some(MemoryLine.of(message.text)))
        assertEquals(Memory.key(d.memory, id).flatMap(store.get), None)
      }
      assert(d.memory.built.keys.filter(_.level > 0).forall(id => Memory.key(d.memory, id).flatMap(store.get).isEmpty))
    }
  }

  // In the live session 00429ec8, a stop during the summaries gave lines of the source to the nodes 1.1 and 2.3. The
  // effects keep the state of each update, so these lines stayed for the rest of the activation, and no later update
  // made the summaries. The doc of `leafLine` says that a later update tries again.
  test("Memory tree: after an update that a stop cut, the next update gives the lines of an update with no stop") {
    Models.withModel(summaries()) {
      val journal  = chat(4, output = 700)
      val fresh    = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = AllMerges)
      val expected = updated(fresh, journal, fresh.empty)
      // The 9 messages of 3 turns need 3 compress calls and 7 merge calls: a stop at the start, after the compress
      // calls, and among the merge calls.
      Vector(0, 3, 6).foreach { allowed =>
        val runner = StopAfter(allowed)
        val tree   = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = AllMerges, leafRunner = runner)
        val cut    = updated(tree, chat(3, output = 700), tree.empty)
        // The stop ends the turn. The steps of the next turn have no stop.
        runner.stopping = false
        val next = updated(tree, journal, cut)
        assertEquals(next.memory.built, expected.memory.built, s"a stop after $allowed leaf calls")
        assertEquals(next, expected, s"a stop after $allowed leaf calls")
      }
    }
  }

  test("Memory tree: after an update with a spent summary budget, an update with a new budget makes the lines") {
    Models.withModel(summaries()) {
      val journal  = chat(4, output = 700)
      val fresh    = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = AllMerges)
      val expected = updated(fresh, journal, fresh.empty)
      val budget   = AtomicReference(Budget(1))
      val tree     = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = AllMerges, summaries = () => budget.get)
      val spent    = updated(tree, chat(3, output = 700), tree.empty)
      // Each turn gets a new budget (`TurnBudget` of the host).
      budget.set(Budget(Int.MaxValue))
      val next = updated(tree, journal, spent)
      assertEquals(next.memory.built, expected.memory.built)
      assertEquals(next, expected)
    }
  }

  test(
    "Memory tree: while the summary budget of the turn is spent, a later update keeps the stand-ins with no report"
  ) {
    val reports = Reports()
    Models.withModel(summaries()) {
      val tree = MemoryTree.withLeaves(
        LineStore.inMemory(),
        viewChars = AllMerges,
        summaries = () => Budget(0),
        progress = reports
      )
      val spent = updated(tree, chat(3, output = 700), tree.empty)
      assert(spent.memory.standIns.nonEmpty, "the spent budget gave no stand-in")
      val first = reports.all
      // The next step of the same turn: the budget is still spent, so the stand-ins can only build as stand-ins again.
      assertEquals(updated(tree, chat(3, output = 700), spent), spent)
      assertEquals(reports.all, first)
    }
  }

  test("Memory tree: a node with a stand-in half is a stand-in with no leaf call, and the store does not keep it") {
    val store = LineStore.inMemory()
    val lm    = summaries()
    val made  = AtomicInteger(0)
    // The runner cuts only the first call, so the later calls run, as after a stop that comes late for some calls.
    val cutFirst = new PolicyRunner:
      def run[A](call: () => Either[String, A]): Either[String, A] =
        if made.getAndIncrement() == 0 then Left(PolicyRunner.Stopped) else call()
    Models.withModel(lm) {
      val tree = MemoryTree.withLeaves(store, viewChars = AllMerges, leafRunner = cutFirst, width = 1)
      val d    = updated(tree, chat(2, output = 700), tree.empty)
      // The cut call is the compress of 0.1. The nodes 1.0 and 2.0 are above it, and only 1.1 and 1.2 merge.
      assertEquals(d.memory.standIns, Set(NodeId(0, 1), NodeId(1, 0), NodeId(2, 0)))
      assertEquals(calls(lm, MemoryTree.mergeInstructions), 2)
      d.memory.standIns.foreach(id => assertEquals(Memory.key(d.memory, id).flatMap(store.get), None, id.ref))
    }
  }

  test("Memory tree: the view takes at most viewChars characters, and it covers the messages up to the last reply") {
    Models.withModel(summaries()) {
      val tree    = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = SmallView)
      val journal = withOpen(chat(12, output = 700), turn(12, 700), 5)
      val d       = updated(tree, journal, tree.empty)
      val view    = MemoryTree.render(d.memory, d.view)
      assert(view.length <= SmallView, view)
      assert(d.view.parts.exists(_.level > 0), s"the law has no join: $view")
      assertEquals(d.view.upTo, MemoryLog.beforeOpenTurn(journal))
      val memory = tree.project(journal, d, budget = 20_000).memory
      assert(memory.length <= MemoryTree.room(SmallView), memory)
    }
  }

  // ── The large view (7.2, choice 10) ──

  // In the 50-turn live comparison of 2026-11-19, a view of 24 parts showed at most 1 of 10 facts. In the large-view
  // test of 2026-11-20, a view of 200 parts held each message as its own line, and the model answered each question.

  test("Memory tree: with the default limit, a session of the shape of the 50-turn task keeps each message as a part") {
    val lm = summaries()
    Models.withModel(lm) {
      val tree = MemoryTree.withLeaves(LineStore.inMemory())
      // A cell output of 300 characters gives a line of about 370 characters, so the view has more than 20,000.
      val d = updated(tree, chat(50, output = 300), tree.empty)
      assert(d.memory.log.size >= 100, s"the law has ${d.memory.log.size} messages")
      assert(MemoryTree.render(d.memory, d.view).length > 20_000, "the view of the law is too small")
      assertEquals(d.view.parts, (0 until d.memory.log.size).map(i => NodeId(0, i.toLong)).toVector)
      // The view needs no merge (7.2, choice 9).
      assertEquals(calls(lm, MemoryTree.mergeInstructions), 0)
      assert(!d.memory.built.keys.exists(_.level > 0), "a node above level 0 has a line")
    }
  }

  // ── The merges that a view needs (7.2, choice 9) ──

  // In the 15-turn live sessions of 2026-11-15, each session made 46 to 49 merges, but its view needed about 26.

  test("Memory tree: update makes a merge only for a part of the newest view or a node under one") {
    val lm = summaries()
    Models.withModel(lm) {
      val tree   = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = SmallView)
      val d      = updated(tree, chat(15, output = 700), tree.empty)
      val n      = d.memory.log.size.toLong
      val merged = d.memory.built.keySet.filter(_.level > 0)
      // The chat has no open turn, so its view is the newest view.
      val needed = View.needed(d.view)
      assertEquals(merged, needed.filter(_.level > 0))
      assertEquals(calls(lm, MemoryTree.mergeInstructions), merged.size)
      assert(needed.forall(d.memory.built.contains), "a node that the view needs has no line")
      val tree15 = (1 to NodeId.MaxLevel).iterator.map(level => n >> level).takeWhile(_ > 0).sum
      assert(merged.nonEmpty && merged.size < tree15, s"${merged.size} merges of the $tree15 of the tree")
    }
  }

  test("Memory tree: update gives a line to each message of the open turn too, so the next turn does not wait for it") {
    val lm = summaries()
    Models.withModel(lm) {
      val tree    = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = SmallView)
      val journal = withOpen(chat(4, output = 700), turn(4, 700), 5)
      val d       = updated(tree, journal, tree.empty)
      assert(d.view.upTo < d.memory.log.size, "the law has no message in the open turn")
      d.memory.log.indices.foreach(i => assert(d.memory.built.contains(NodeId(0, i.toLong)), s"0.$i has no line"))
      assertEquals(calls(lm, MemoryTree.compressInstructions), d.memory.log.count(m => !MemoryLine.fits(m.text)))
    }
  }

  test("Memory tree: a summary does not change the view, because the size of a part reads only the messages") {
    val journal = withOpen(chat(12, output = 700), turn(12, 700), 5)
    val views   = Vector(summaries(), summaries(before = "y" * 600), RequestLm(_ => Left("FAKE-FAILURE"))).map { lm =>
      Models.withModel(lm) {
        val tree = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = SmallView)
        updated(tree, journal, tree.empty).view
      }
    }
    assertEquals(views.distinct.size, 1, views.toString)
    assert(views.head.parts.exists(_.level > 0), s"the law has no join: ${views.head}")
  }

  test("Memory tree: the open turn gets the whole budget, and the view gets its own room on top of the budget") {
    // With one budget for both parts, a large view would take the budget of the window strategy too (2026-11-20).
    // The open turn has more than the budget, so a smaller budget gives it fewer steps.
    val steps = (0 until 12).toVector.flatMap(k =>
      Vector(
        Entry.StepStarted(k),
        Entry.Acted("", s"look $k", Some(s"println($k)")),
        Entry.Observed("z" * 3000, CellOutcome.Ok, Map.empty)
      )
    )
    Models.withModel(summaries()) {
      val tree    = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = SmallView)
      val journal =
        withOpen(chat(12, output = 700), Entry.Input(request, "long", Map.empty) +: Entry.TurnStarted +: steps, 38)
      val d = updated(tree, journal, tree.empty)
      val p = tree.project(journal, d, budget = 20_000)
      assert(Window.openTurn(journal, 40_000).size > 20_000, "the open turn of the law fits the budget")
      assertEquals(p.history, Window.openTurn(journal, 20_000).history, "the open turn has a smaller budget")
      assertEquals(tree.maxSize(20_000), 20_000 + MemoryTree.room(SmallView))
      assert(p.size <= tree.maxSize(20_000), s"${p.size}")
    }
    assertEquals(Window.maxSize(20_000), 20_000)
    assertEquals(HarnessLimits().viewChars, MemoryTree.DefaultViewChars)
  }

  test("Memory tree: the view is in the field memory, and it stays the same during a turn") {
    Models.withModel(summaries()) {
      val tree  = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = SmallView)
      val base  = chat(8, output = 700)
      val views = (1 to 5).map { k =>
        val journal = withOpen(base, turn(8, 700), k)
        val d       = updated(tree, journal, tree.empty)
        val memory  = tree.project(journal, d, budget = 20_000).memory
        assert(memory.contains(MemoryTree.render(d.memory, d.view)), memory)
        memory
      }
      assertEquals(views.distinct.size, 1)
    }
  }

  test("Memory tree: the view is the field memory, and the history has only the steps of the open turn") {
    // The view came as a first step of the history with an empty reply and the code None, which can teach the model an
    // empty reply (2026-11-12).
    Models.withModel(summaries()) {
      val tree    = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = SmallView)
      val journal = withOpen(chat(8, output = 700), turn(8, 700), 4)
      val d       = updated(tree, journal, tree.empty)
      val p       = tree.project(journal, d, budget = 20_000)
      assertEquals(p.memory, s"${MemoryTree.Header}\n${MemoryTree.render(d.memory, d.view)}")
      val open = Window.openTurn(journal, 20_000)
      assertEquals(p.history, open.history, "the history is the open turn")
      assert(!p.history.messages.flatMap(strings).exists(_.contains(MemoryTree.Header)), p.history.toString)
    }
  }

  test("Memory tree: the summary instructions keep who wrote each part") {
    assert(MemoryTree.compressInstructions.contains("who wrote"), MemoryTree.compressInstructions)
    assert(MemoryTree.mergeInstructions.contains("who wrote"), MemoryTree.mergeInstructions)
    assert(MemoryTree.shortenInstructions.contains("who wrote"), MemoryTree.shortenInstructions)
  }

  test("Memory tree: the view takes its own room in full, also in a long turn") {
    Models.withModel(summaries(before = "y" * 600)) {
      val tree  = MemoryTree.withLeaves(LineStore.inMemory())
      val steps = (0 until 30).toVector.flatMap(k =>
        Vector(
          Entry.StepStarted(k),
          Entry.Acted("", s"look $k", Some(s"println($k)")),
          Entry.Observed("z" * 3000, CellOutcome.Ok, Map.empty)
        )
      )
      val journal =
        withOpen(chat(60, output = 700), Entry.Input(request, "long", Map.empty) +: Entry.TurnStarted +: steps, 92)
      val d      = updated(tree, journal, tree.empty)
      val budget = HarnessLimits().budget
      val p      = tree.project(journal, d, budget)
      val view   = MemoryTree.render(d.memory, d.view)
      assert(p.memory.length <= MemoryTree.room(MemoryTree.DefaultViewChars), s"${p.memory.length}")
      assert(p.memory.contains(view), p.memory)
      assert(p.size <= tree.maxSize(budget), s"${p.size}")
      assert(p.size - p.memory.length <= budget, s"${p.size - p.memory.length}")
    }
  }

  test("Memory tree: render gives one line for each part, also for a line with a newline") {
    Models.withModel(summaries(after = "\nwith a second line")) {
      val tree = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = SmallView)
      val d    = updated(tree, chat(8, output = 700), tree.empty)
      val text = MemoryTree.render(d.memory, d.view)
      assertEquals(text.linesIterator.size, d.view.parts.size)
      d.view.parts.foreach(p => assert(text.contains(s"[${p.ref}]"), text))
    }
  }

  test("Memory tree: zoom gives the message of 0.i and the two lines under a summary line, else an error") {
    Models.withModel(summaries()) {
      val tree  = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = AllMerges)
      val d     = updated(tree, chat(8, output = 700), tree.empty)
      val first = tree.zoom(d, "0.0")
      assert(first.exists(_.contains("message 0")), first.toString)
      val parent = d.memory.built.keys.filter(_.level > 0).head
      val (a, b) = parent.halves.get
      val lines  = tree.zoom(d, parent.ref).fold(fail(_), identity)
      assert(lines.contains(a.ref) && lines.contains(b.ref), lines)
      assert(lines.contains(d.memory.built(a).text) && lines.contains(d.memory.built(b).text), lines)
      assert(tree.zoom(d, "30.0").isLeft)
      assert(tree.zoom(d, "63.0").isLeft)
      assert(tree.zoom(d, "not a reference").isLeft)
    }
  }

  /** The parts that the error of a zoom must name for `d`: each part of a view of at most 12 parts, or the first and
    * the last part of a longer view, and the range of the messages.
    */
  private def named(d: MemoryState): Vector[String] =
    val shown = d.view.parts.map(_.ref)
    val parts = if shown.size <= 12 then shown else Vector(s"from ${shown.head} to ${shown.last}")
    parts :+ s"0.0 to 0.${d.memory.log.size - 1}"

  // With only the merges that a view needs (7.2, choice 9), a root of the tree can have no line. An error that named
  // the roots would send the model to a part that does not zoom.
  test("Memory tree: the zoom of a part that does not exist names the parts that zoom, and each of them zooms") {
    Vector(SmallView, AllMerges).foreach { limit =>
      Models.withModel(summaries()) {
        val tree = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = limit)
        val d    = updated(tree, chat(8, output = 700), tree.empty)
        tree.zoom(d, "30.0") match
          case Left(error) =>
            named(d).foreach(part => assert(error.contains(part), s"$limit: $error"))
            d.view.parts.foreach(p => assert(tree.zoom(d, p.ref).isRight, s"$limit: ${p.ref}"))
            assert(tree.zoom(d, s"0.${d.memory.log.size - 1}").isRight, s"$limit: the last message")
          case Right(text) => fail(text)
      }
    }
    Models.withModel(summaries()) {
      val tree = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = SmallView)
      val d    = updated(tree, chat(8, output = 700), tree.empty)
      // The view of the 24 messages has only parts of level 0, so the roots have no line.
      Memory.roots(d.memory.log.size.toLong).foreach(root => assert(tree.zoom(d, root.ref).isLeft, root.ref))
      assert(tree.zoom(tree.empty, "0.0").left.exists(_.contains("empty")), tree.zoom(tree.empty, "0.0").toString)
    }
  }

  // In a live run of 2026-11-16, the controller passed a journal seq ("40") as a reference. The error said only what a
  // reference looks like, so the model could not find a part that exists.
  test("Memory tree: the zoom of a text that is not a reference names the form and the parts that zoom") {
    Models.withModel(summaries()) {
      val tree = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = SmallView)
      val d    = updated(tree, chat(8, output = 700), tree.empty)
      tree.zoom(d, "40") match
        case Left(error) =>
          assert(error.contains("level.index"), error)
          named(d).foreach(part => assert(error.contains(part), error))
        case Right(text) => fail(text)
    }
  }

  test("Memory tree: the window has no memory, so its zoom gives an error") {
    assert(Window.zoom((), "0.0").isLeft)
  }

  // ── The speed of the summaries (the live run of 7.1) ──

  test("Memory tree: each leaf request has the option bag of the summaries, so a model can skip its reasoning") {
    val options = DynamicValues.record(
      "chat_template_kwargs" ->
        DynamicValues.record("enable_thinking" -> DynamicValue.Primitive(PrimitiveValue.Boolean(false)))
    )
    val lm = summaries()
    Models.withModel(lm) {
      val tree = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = AllMerges, options = options)
      updated(tree, chat(2, output = 700), tree.empty): Unit
    }
    assert(!lm.requests.isEmpty, "the update made no leaf call")
    lm.requests.asScala.foreach(request =>
      assert(request.options.fields.exists(_._1 == "chat_template_kwargs"), request.options.toString)
    )
  }

  test("Memory tree: the store gets the lines of each round, so an update that breaks keeps the rounds before it") {
    val store    = LineStore.inMemory()
    val made     = AtomicInteger(0)
    val breaking = new PolicyRunner:
      // The first round has the 3 compress calls of the 3 long cells. The first merge of the next round breaks.
      def run[A](call: () => Either[String, A]): Either[String, A] =
        if made.incrementAndGet() > 3 then throw IllegalStateException("FAKE-BREAK") else call()
    val journal = chat(3, output = 700)
    Models.withModel(summaries()) {
      val tree = MemoryTree.withLeaves(store, viewChars = AllMerges, leafRunner = breaking, width = 1)
      intercept[IllegalStateException](tree.update(journal, tree.empty)): Unit
    }
    val memory = MemoryLog.messages(journal).foldLeft(Memory.empty)(Memory.append)
    val long   = memory.log.indices.filter(i => memory.log(i).text.getBytes(UTF_8).length > 512)
    assertEquals(long.size, 3)
    long.foreach(i => assert(Memory.key(memory, NodeId(0, i.toLong)).flatMap(store.get).nonEmpty, s"no line for 0.$i"))
  }

  test("Memory tree: the leaf calls of one round run at the same time, at most width at a time") {
    val lm = RequestLm(
      text => Right(s"[[ ## line ## ]]\nS${(text.hashCode & 0x7fffffff) % 100000}\n\n[[ ## completed ## ]]"),
      _ => 200L
    )
    Models.withModel(lm) {
      val tree = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = AllMerges, width = 3)
      updated(tree, chat(4, output = 700), tree.empty): Unit
    }
    assertEquals(lm.most.get, 3)
  }

  // ── The limit of a line (7.2, choice 8) ──

  // In the 15-turn live sessions of 2026-11-15, the merge replies had a median of 555 to 591 bytes. The cut of
  // `MemoryLine.of` removed the end of nearly each line of level 2 and above, so a high line lost its newest messages.

  /** The text of a request, as `RequestLm` gives it to its reply function. */
  private def textOf(request: LmRequest): String = request.messages.flatMap(_.text).mkString("\n")

  /** True for a request of `compress` or `merge`. Each other request of these laws is a request of `shorten`. */
  private def leafRequest(text: String): Boolean =
    text.contains(MemoryTree.compressInstructions) || text.contains(MemoryTree.mergeInstructions)

  /** The line of a compress or a merge request: more than 512 bytes, and a fixed function of the request. */
  private def longLine(text: String): String = s"L${(text.hashCode & 0x7fffffff) % 100000} " + ("l" * 600)

  /** A summary model with a line of more than 512 bytes for each compress and merge request (`longLine`). `shorter`
    * gives the line of each other request, or a failure.
    */
  private def tooLong(shorter: String => Either[String, String]): RequestLm =
    RequestLm(text =>
      (if leafRequest(text) then Right(longLine(text)) else shorter(text))
        .map(line => s"[[ ## line ## ]]\n$line\n\n[[ ## completed ## ]]")
    )

  /** The number of the requests that are not a compress or a merge request: the shorten calls. */
  private def shortenCalls(lm: RequestLm): Int = lm.requests.asScala.count(r => !leafRequest(textOf(r)))

  /** The nodes of `d` with the line of a leaf: each node above level 0, and each message of more than 512 bytes. */
  private def summarized(d: MemoryState): Vector[NodeId] =
    d.memory.built.keys.toVector.filter(id =>
      id.level > 0 || d.memory.log(id.index.toInt).text.getBytes(UTF_8).length > MemoryLine.MaxBytes
    )

  test("Memory tree: a compress or merge reply of more than 512 bytes gets one shorten call, for the line and store") {
    val store   = LineStore.inMemory()
    val reports = Reports()
    val lm      = tooLong(text => Right(s"short-${(text.hashCode & 0x7fffffff) % 100000}"))
    Models.withModel(lm) {
      val tree = MemoryTree.withLeaves(store, viewChars = AllMerges, progress = reports)
      val d    = updated(tree, chat(3, output = 900), tree.empty)
      // The 3 long cells give 3 compress calls, and the 9 messages give 7 merge calls.
      val leaves = calls(lm, MemoryTree.compressInstructions) + calls(lm, MemoryTree.mergeInstructions)
      assertEquals(leaves, 10)
      assertEquals(shortenCalls(lm), leaves, "one shorten call for each long reply")
      assertEquals(summarized(d).size, leaves)
      summarized(d).foreach { id =>
        val line = d.memory.built(id)
        assert(line.text.startsWith("short-"), s"${id.ref}: ${line.text}")
        assertEquals(Memory.key(d.memory, id).flatMap(store.get), Some(line), id.ref)
      }
      // A summary with its shorten call is one summary for the client.
      assertEquals(reports.all, counted(10))
    }
  }

  test("Memory tree: a shorten reply of more than 512 bytes gets the cut of MemoryLine.of, with no second call") {
    val store = LineStore.inMemory()
    val long  = "t" * 700
    val lm    = tooLong(_ => Right(long))
    Models.withModel(lm) {
      val tree = MemoryTree.withLeaves(store, viewChars = AllMerges)
      val d    = updated(tree, chat(3, output = 900), tree.empty)
      assertEquals(shortenCalls(lm), 10)
      assertEquals(summarized(d).size, 10)
      summarized(d).foreach { id =>
        assertEquals(d.memory.built.get(id), Some(MemoryLine.of(long)), id.ref)
        assertEquals(Memory.key(d.memory, id).flatMap(store.get), Some(MemoryLine.of(long)), id.ref)
      }
    }
  }

  test("Memory tree: a shorten call spends one call of the summary budget, and with none left the node is a stand-in") {
    val store   = LineStore.inMemory()
    val lm      = tooLong(_ => Right("short"))
    val budget  = AtomicReference(Budget(1))
    val journal = chat(1, output = 900)
    val cell    = NodeId(0, 1)
    Models.withModel(lm) {
      val tree  = MemoryTree.withLeaves(store, viewChars = AllMerges, summaries = () => budget.get, width = 1)
      val spent = updated(tree, journal, tree.empty)
      // The compress call of the long cell 0.1 spends the only call of the budget, so no shorten call comes.
      assertEquals(lm.requests.size, 1)
      assert(spent.memory.standIns.contains(cell), spent.memory.standIns.toString)
      assertEquals(spent.memory.built.get(cell), Some(MemoryLine.of(spent.memory.log(1).text)))
      assertEquals(Memory.key(spent.memory, cell).flatMap(store.get), None)
      // The next turn has a new budget, so the compress call comes again, and then its shorten call.
      budget.set(Budget(Int.MaxValue))
      val next = updated(tree, journal, spent)
      assertEquals(next.memory.built.get(cell), Some(MemoryLine.of("short")))
      assertEquals(Memory.key(next.memory, cell).flatMap(store.get), Some(MemoryLine.of("short")))
    }
    // With 2 calls, the compress call and its shorten call spend the budget, so the merge of 1.0 makes no call.
    val two = tooLong(_ => Right("short"))
    Models.withModel(two) {
      val tree =
        MemoryTree.withLeaves(LineStore.inMemory(), viewChars = AllMerges, summaries = () => Budget(2), width = 1)
      val d = updated(tree, journal, tree.empty)
      assertEquals(two.requests.size, 2)
      assertEquals(d.memory.built.get(cell), Some(MemoryLine.of("short")))
      assert(d.memory.standIns.contains(NodeId(1, 0)), d.memory.standIns.toString)
    }
  }

  test("Memory tree: a failed shorten call gives the cut of the first reply, for the store, and a stop a stand-in") {
    val cell  = NodeId(0, 1)
    val store = LineStore.inMemory()
    val lm    = tooLong(_ => Left("FAKE-FAILURE"))
    Models.withModel(lm) {
      val tree     = MemoryTree.withLeaves(store, viewChars = AllMerges, width = 1)
      val d        = updated(tree, chat(1, output = 900), tree.empty)
      val expected = lm.requests.asScala.map(textOf).find(_.contains(MemoryTree.compressInstructions))
        .map(text => MemoryLine.of(longLine(text)))
      assert(expected.nonEmpty, "no compress call")
      assertEquals(shortenCalls(lm), 2)
      assertEquals(d.memory.built.get(cell), expected)
      assertEquals(Memory.key(d.memory, cell).flatMap(store.get), expected)
      assert(!d.memory.standIns.contains(cell), d.memory.standIns.toString)
    }
    // The runner runs the compress call of 0.1 and cuts the next call, its shorten call, as a stop does.
    val made      = AtomicInteger(0)
    val cutSecond = new PolicyRunner:
      def run[A](call: () => Either[String, A]): Either[String, A] =
        if made.incrementAndGet() == 2 then Left(PolicyRunner.Stopped) else call()
    val other = LineStore.inMemory()
    Models.withModel(tooLong(_ => Right("short"))) {
      val tree = MemoryTree.withLeaves(other, viewChars = AllMerges, leafRunner = cutSecond, width = 1)
      val d    = updated(tree, chat(1, output = 900), tree.empty)
      assertEquals(made.get, 2)
      assert(d.memory.standIns.contains(cell), d.memory.standIns.toString)
      assertEquals(d.memory.built.get(cell), Some(MemoryLine.of(d.memory.log(1).text)))
      assertEquals(Memory.key(d.memory, cell).flatMap(other.get), None)
    }
  }

  test("Memory tree: each shorten request has the option bag of the summaries too") {
    val options = DynamicValues.record(
      "chat_template_kwargs" ->
        DynamicValues.record("enable_thinking" -> DynamicValue.Primitive(PrimitiveValue.Boolean(false)))
    )
    val lm = tooLong(_ => Right("short"))
    Models.withModel(lm) {
      val tree = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = AllMerges, options = options)
      updated(tree, chat(1, output = 900), tree.empty): Unit
    }
    assert(shortenCalls(lm) > 0, "the update made no shorten call")
    lm.requests.asScala.foreach(request =>
      assert(request.options.fields.exists(_._1 == "chat_template_kwargs"), request.options.toString)
    )
  }

  // ── The progress of the summaries for a client (10.6) ──

  /** A progress that keeps each report of the summaries in order: `done/total`, and `end` for the end. */
  private final class Reports extends MemoryProgress:
    private val seen = ConcurrentLinkedQueue[String]()

    override def summaries(done: Int, total: Int): Unit = seen.add(s"$done/$total"): Unit
    override def summariesEnded(): Unit                 = seen.add("end"): Unit

    def all: Vector[String] = seen.asScala.toVector

  /** The reports of an update with `n` summaries: `0/n` first, each end in order, and then the end. */
  private def counted(n: Int): Vector[String] = (0 to n).map(done => s"$done/$n").toVector :+ "end"

  test("Memory tree: an update with N leaf calls reports 0 of N first, each end in order, and then the end") {
    // A live session waited 63 s at one step for the summaries, and the screen gave no reason (2026-11-11).
    val lm      = summaries()
    val reports = Reports()
    Models.withModel(lm) {
      val tree = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = AllMerges, width = 3, progress = reports)
      updated(tree, chat(4, output = 700), tree.empty): Unit
    }
    // The 4 long cells give 4 compress calls, and the 12 messages give 10 merge calls.
    assertEquals(lm.requests.size, 14)
    assertEquals(reports.all, counted(14))
    // With a small view, the count has only the merges that the view needs (7.2, choice 9).
    val few     = summaries()
    val partial = Reports()
    Models.withModel(few) {
      val tree = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = SmallView, width = 3, progress = partial)
      updated(tree, chat(15, output = 700), tree.empty): Unit
    }
    assert(few.requests.size < 15 + 41, s"${few.requests.size} leaf calls: the law has no merge that the view skips")
    assertEquals(partial.all, counted(few.requests.size))
  }

  test("Memory tree: an update with no leaf call reports no summaries") {
    val store   = LineStore.inMemory()
    val reports = Reports()
    Models.withModel(summaries()) {
      // One short message is its own line, and it has no parent.
      val short = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = AllMerges, progress = reports)
      updated(short, withOpen(chat(0, output = 10), turn(0, 10), 1), short.empty): Unit
      val first = MemoryTree.withLeaves(store, viewChars = AllMerges)
      updated(first, chat(3, output = 700), first.empty): Unit
      // The store has each line, so this update makes no leaf call.
      val again = MemoryTree.withLeaves(store, viewChars = AllMerges, progress = reports)
      val d     = updated(again, chat(3, output = 700), again.empty)
      updated(again, chat(3, output = 700), d): Unit
    }
    assertEquals(reports.all, Vector.empty)
  }

  test("Memory tree: an update ends its summaries also after a failed, a stopped or a broken leaf call") {
    val stopped = new PolicyRunner:
      def run[A](call: () => Either[String, A]): Either[String, A] = Left(PolicyRunner.Stopped)
    val made     = AtomicInteger(0)
    val breaking = new PolicyRunner:
      def run[A](call: () => Either[String, A]): Either[String, A] =
        if made.incrementAndGet() > 3 then throw IllegalStateException("FAKE-BREAK") else call()
    val failed = RequestLm(_ => Left("FAKE-FAILURE"))
    val first  = Reports()
    Models.withModel(failed) {
      val tree = MemoryTree.withLeaves(LineStore.inMemory(), viewChars = AllMerges, width = 3, progress = first)
      updated(tree, chat(2, output = 700), tree.empty): Unit
    }
    // The 2 long cells give 2 compress calls, and the 6 messages give 4 merge calls.
    assertEquals(first.all, counted(6))
    val second = Reports()
    Models.withModel(summaries()) {
      val tree =
        MemoryTree.withLeaves(LineStore.inMemory(), viewChars = AllMerges, leafRunner = stopped, progress = second)
      updated(tree, chat(2, output = 700), tree.empty): Unit
    }
    assertEquals(second.all, counted(6))
    val third = Reports()
    Models.withModel(summaries()) {
      val tree = MemoryTree.withLeaves(
        LineStore.inMemory(),
        viewChars = AllMerges,
        leafRunner = breaking,
        width = 1,
        progress = third
      )
      intercept[IllegalStateException](tree.update(chat(3, output = 700), tree.empty)): Unit
    }
    // The 9 messages need 3 compress calls and 7 merge calls. The 3 compress calls end, and the first merge breaks. It
    // ends too, so the count has 4 ends.
    assertEquals(third.all, (0 to 4).map(done => s"$done/10").toVector :+ "end")
  }
