package dspy4s.harness

import dspy4s.core.contracts.{DynamicValues, RuntimeContext}
import dspy4s.harness.kernel.*
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*

/** The laws of the memory tree strategy (CODE_HARNESS_DESIGN.md, 7 and 7.1). A fake model gives each summary as a fixed
  * function of its request, so two runs give the same lines.
  */
class MemoryTreeLaws extends munit.FunSuite:

  private val request = RequestId("FAKE-REQUEST")

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
      val tree = MemoryTree.withLeaves(LineStore.inMemory(), high = 40)
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
      val tree = MemoryTree.withLeaves(LineStore.inMemory(), high = 40)
      val d    = updated(tree, chat(4, output = 900), tree.empty)
      assertEquals(calls(lm, MemoryTree.compressInstructions), d.memory.log.count(_.text.getBytes(UTF_8).length > 512))
      assertEquals(calls(lm, MemoryTree.mergeInstructions), d.memory.built.keys.count(_.level > 0))
      assert(Memory.ready(d.memory).isEmpty, "a node that can build stays")
    }
  }

  test("Memory tree: two updates give the same memory and view as one update on the full journal") {
    Models.withModel(summaries()) {
      val tree    = MemoryTree.withLeaves(LineStore.inMemory(), high = 3)
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
      val first = MemoryTree.withLeaves(store, high = 6)
      updated(first, chat(5, output = 700), first.empty): Unit
    }
    val again = summaries()
    Models.withModel(again) {
      val tree = MemoryTree.withLeaves(store, high = 6)
      updated(tree, chat(5, output = 700), tree.empty): Unit
    }
    assertEquals(again.requests.size, 0)
  }

  test("Memory tree: a store line of other messages is not used, because its key has another digest") {
    val store = LineStore.inMemory()
    store.putAll(Vector(LineKey(NodeId(0, 1), "FAKE-OTHER-DIGEST") -> MemoryLine.of("FAKE-WRONG-LINE")))
    Models.withModel(summaries()) {
      val tree = MemoryTree.withLeaves(store, high = 6)
      val d    = updated(tree, chat(2, output = 700), tree.empty)
      assert(!d.memory.built.values.exists(_.text == "FAKE-WRONG-LINE"), d.memory.built.toString)
    }
  }

  test("Memory tree: a failed leaf call gives the line of the source, and the store keeps it") {
    val store = LineStore.inMemory()
    val lm    = RequestLm(_ => Left("FAKE-FAILURE"))
    Models.withModel(lm) {
      val tree = MemoryTree.withLeaves(store, high = 6)
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
      val tree = MemoryTree.withLeaves(store, high = 6, summaries = () => Budget(1))
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
      val tree = MemoryTree.withLeaves(store, high = 6, leafRunner = stopped)
      val d    = updated(tree, chat(2, output = 700), tree.empty)
      d.memory.log.zipWithIndex.foreach { (message, i) =>
        val id = NodeId(0, i.toLong)
        assertEquals(d.memory.built.get(id), Some(MemoryLine.of(message.text)))
        assertEquals(Memory.key(d.memory, id).flatMap(store.get), None)
      }
      assert(d.memory.built.keys.filter(_.level > 0).forall(id => Memory.key(d.memory, id).flatMap(store.get).isEmpty))
    }
  }

  test("Memory tree: the view has at most high parts, and it covers the messages up to the last reply") {
    Models.withModel(summaries()) {
      val tree    = MemoryTree.withLeaves(LineStore.inMemory(), high = 6)
      val journal = withOpen(chat(12, output = 700), turn(12, 700), 5)
      val d       = updated(tree, journal, tree.empty)
      assert(d.view.parts.size <= 6, d.view.toString)
      assertEquals(d.view.upTo, MemoryLog.beforeOpenTurn(journal))
    }
  }

  test("Memory tree: the view is the first message of the history, and it stays the same during a turn") {
    Models.withModel(summaries()) {
      val tree  = MemoryTree.withLeaves(LineStore.inMemory(), high = 6)
      val base  = chat(8, output = 700)
      val views = (1 to 5).map { k =>
        val journal = withOpen(base, turn(8, 700), k)
        val d       = updated(tree, journal, tree.empty)
        val first   = strings(tree.project(journal, d, budget = 20_000).history.messages.head)
        assert(first.exists(_.contains(MemoryTree.render(d.memory, d.view))), first.toString)
        first
      }
      assertEquals(views.distinct.size, 1)
    }
  }

  test("Memory tree: the view takes its room of the budget first, in full, also in a long turn") {
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
      val d    = updated(tree, journal, tree.empty)
      val p    = tree.project(journal, d, budget = 40_000)
      val view = MemoryTree.render(d.memory, d.view)
      assert(view.length <= MemoryTree.room(MemoryTree.DefaultHigh), s"${view.length}")
      assert(strings(p.history.messages.head).exists(_.contains(view)))
      assert(p.size <= 40_000, s"${p.size}")
    }
  }

  test("Memory tree: render gives one line for each part, also for a line with a newline") {
    Models.withModel(summaries(after = "\nwith a second line")) {
      val tree = MemoryTree.withLeaves(LineStore.inMemory(), high = 6)
      val d    = updated(tree, chat(8, output = 700), tree.empty)
      val text = MemoryTree.render(d.memory, d.view)
      assertEquals(text.linesIterator.size, d.view.parts.size)
      d.view.parts.foreach(p => assert(text.contains(s"[${p.ref}]"), text))
    }
  }

  test("Memory tree: zoom gives the message of 0.i and the two lines under a summary line, else an error") {
    Models.withModel(summaries()) {
      val tree  = MemoryTree.withLeaves(LineStore.inMemory(), high = 6)
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

  test("Memory tree: the window has no memory, so its zoom gives an error") {
    assert(Window.zoom((), "0.0").isLeft)
  }
