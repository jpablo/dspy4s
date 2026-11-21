package dspy4s.harness

import dspy4s.core.contracts.RuntimeContext
import dspy4s.harness.kernel.*

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import scala.jdk.CollectionConverters.*

/** The laws of the builder of the derived data (CODE_HARNESS_DESIGN.md, 7.2, choices 4 to 7): the thread of the
  * builder, the gate of the policy calls, and the memory tree as a derivation of the builder.
  */
class BuilderLaws extends munit.FunSuite:

  private val request = RequestId("FAKE-REQUEST")

  /** True when `condition` holds within `seconds`. */
  private def eventually(seconds: Int = 10)(condition: => Boolean): Boolean =
    val end = System.nanoTime() + seconds * 1_000_000_000L
    @scala.annotation.tailrec
    def go(): Boolean = condition || (System.nanoTime() < end && { Thread.sleep(10); go() })
    go()

  /** A derivation that counts its builds. The first build waits for `first`. */
  private final class Counting(first: CountDownLatch) extends BuiltDerivation:
    val builds                                                     = AtomicInteger(0)
    val name: DerivationName                                       = DerivationName.Memory
    def newest: Long                                               = 0L
    def build(journal: Vector[Logged])(using RuntimeContext): Unit =
      if builds.incrementAndGet() == 1 then first.await(10, TimeUnit.SECONDS): Unit

  // ── The thread of the builder (choice 5) ──

  test("Builder: a build runs at the start, and many wakes during a build give one more build") {
    val first   = CountDownLatch(1)
    val counted = Counting(first)
    val builder = Builder(counted, () => Vector.empty, RuntimeContext(), () => (), "fake")
    try
      assert(eventually()(counted.builds.get == 1), "no build at the start")
      (1 to 5).foreach(_ => builder.wake())
      first.countDown()
      assert(eventually()(counted.builds.get == 2), s"${counted.builds.get} builds")
      Thread.sleep(200)
      assertEquals(counted.builds.get, 2, "a wake during a build gave more than one build")
      builder.wake()
      assert(eventually()(counted.builds.get == 3), s"${counted.builds.get} builds")
    finally builder.close()
  }

  test("Builder: close stops the calls that run, and no build runs after it") {
    val first   = CountDownLatch(0)
    val counted = Counting(first)
    val stopped = AtomicBoolean(false)
    val builder = Builder(counted, () => Vector.empty, RuntimeContext(), () => stopped.set(true), "fake")
    assert(eventually()(counted.builds.get == 1), "no build at the start")
    builder.close()
    assert(stopped.get, "the close did not stop the calls")
    val after = counted.builds.get
    builder.wake()
    Thread.sleep(200)
    assertEquals(counted.builds.get, after)
  }

  // ── The gate of the policy calls (choice 4) ──

  test("Gate: a summary call waits while a policy call runs, and starts after its end") {
    val gate    = PolicyGate()
    val runner  = GatedRunner(gate, PolicyRunner.Direct)
    val inside  = CountDownLatch(1)
    val release = CountDownLatch(1)
    val events  = ConcurrentLinkedQueue[String]()
    val policy  = Thread.ofPlatform().start { () =>
      gate.during {
        events.add("policy starts"): Unit
        inside.countDown()
        release.await(10, TimeUnit.SECONDS): Unit
        events.add("policy ends"): Unit
      }
    }
    assert(inside.await(10, TimeUnit.SECONDS))
    val summary = Thread.ofPlatform().start { () =>
      runner.run(() =>
        events.add("summary starts"); Right(())
      ): Unit
    }
    Thread.sleep(200)
    assertEquals(events.asScala.toVector, Vector("policy starts"), "the summary call started during the policy call")
    release.countDown()
    policy.join(10_000)
    summary.join(10_000)
    assertEquals(events.asScala.toVector, Vector("policy starts", "policy ends", "summary starts"))
  }

  test("Gate: after the close, the gate never waits, and a closed runner makes no call") {
    val gate   = PolicyGate()
    val runner = GatedRunner(gate, PolicyRunner.Direct)
    val made   = AtomicInteger(0)
    val inside = CountDownLatch(1)
    val hold   = CountDownLatch(1)
    val policy = Thread.ofPlatform().start(() =>
      gate.during {
        inside.countDown()
        hold.await(10, TimeUnit.SECONDS): Unit
      }
    )
    assert(inside.await(10, TimeUnit.SECONDS))
    runner.close()
    gate.close()
    // The policy call still runs, but the closed gate does not wait.
    assertEquals(
      runner.run(() =>
        made.incrementAndGet(); Right(())
      ),
      Left(PolicyRunner.Stopped)
    )
    assertEquals(made.get, 0)
    hold.countDown()
    policy.join(10_000)
  }

  // ── The memory tree as a derivation (choices 1, 3 and 7) ──

  private def chat(turns: Int): Vector[Logged] =
    val entries = (0 until turns).flatMap { n =>
      Vector(
        Entry.Input(request, s"message $n " + ("y" * 700), Map.empty),
        Entry.TurnStarted,
        Entry.StepStarted(0),
        Entry.Acted("", s"reply $n", None),
        Entry.TurnEnded(TurnOutcome.Replied(s"reply $n"))
      )
    }
    Support.logOf(1000L, (Entry.Activated(Harness.versions, Support.config(TurnKind.Chat)) +: entries)*)

  private def summaries: RequestLm =
    RequestLm(text => Right(s"[[ ## line ## ]]\nS${(text.hashCode & 0x7fffffff) % 100000}\n\n[[ ## completed ## ]]"))

  test("Memory builder: a build stores the lines that the view needs, and zoom reads the newest memory") {
    val store = LineStore.inMemory()
    val zooms = ZoomSource()
    val lm    = summaries
    Models.withModel(lm) {
      val journal = chat(4)
      // A view of 0 characters is the roots, so it needs the merges too.
      val builder = MemoryBuilder(MemoryTree.withLeaves(store, viewChars = 0), store, zooms)
      assertEquals(zooms.zoom("1.0").isLeft, true)
      builder.build(journal)
      assert(lm.requests.size > 0, "the build made no summary call")
      assertEquals(builder.newest, store.newest)
      assert(store.newest > 0, "the build stored no line")
      // The zoom of a part above level 0 gives the lines of its halves from the newest build.
      val lines = zooms.zoom("1.0").fold(fail(_), identity)
      assert(lines.contains("[0.0]") && lines.contains("[0.1]") && lines.contains("] S"), lines)
      // A second build with the same journal makes no new call, and adds no build number.
      val before = (lm.requests.size, store.newest)
      builder.build(journal)
      assertEquals((lm.requests.size, store.newest), before)
    }
  }

  test("Memory builder: a tree that only reads makes no LM call, and it reads the lines that the builder stored") {
    val store   = LineStore.inMemory()
    val journal = chat(3)
    val none    = RequestLm(_ => Left("FAKE-NO-CALL"))
    val reader  = MemoryTree.withLeaves(store, viewChars = 0, readOnly = true)
    Models.withModel(none) {
      val before = reader.update(journal, reader.empty).fold(e => fail(e.message), identity)
      assertEquals(none.requests.size, 0)
      assert(before.memory.standIns.nonEmpty, "a node with no line is not a stand-in")
      assertEquals(reader.reads, Vector(DerivationName.Memory -> 0L))
      Models.withModel(summaries)(MemoryBuilder(
        MemoryTree.withLeaves(store, viewChars = 0),
        store,
        ZoomSource()
      ).build(journal))
      // The next update takes the stand-ins out, and reads the stored lines up to the newest build number.
      val after = reader.update(journal, before).fold(e => fail(e.message), identity)
      assertEquals(none.requests.size, 0)
      assertEquals(after.memory.standIns, Set.empty[NodeId])
      assertEquals(reader.reads, Vector(DerivationName.Memory -> store.newest))
    }
  }
