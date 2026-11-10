package dspy4s.harness

import dspy4s.harness.kernel.{state as foldOf, *}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import zio.blocks.schema.json.Json

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

import Support.*

/** The laws of the journal in memory (CODE_HARNESS_DESIGN.md, 4.7 and 9): it is the writer of one call. */
class JournalLaws extends ScalaCheckSuite:

  private val sources: Gen[Source] = Gen.oneOf(Source.User, Source.Harness, Source.Policy)

  /** Commands in any order, so some of them are refused. */
  private val commands: Gen[Command] = Gen.oneOf(
    Gen.const(Command.Activate(versions, config())),
    Gen.oneOf("r1", "r2", "r3").map(id => Command.Send(RequestId(id), s"message $id", Map.empty)),
    Gen.choose(0, 2).map(i => Command.Commit(Vector(Entry.StepStarted(i)))),
    Gen.const(Command.Commit(Vector(Entry.Acted("", "an answer", None)))),
    Gen.const(Command.Commit(Vector(Entry.TurnEnded(TurnOutcome.Exhausted)))),
    Gen.const(Command.Abort("stop")),
    Gen.const(Command.Close(SessionOutcome.Parked, "done"))
  )

  // No shrink: a list of commands can have a large search for a smaller failure.
  property("Journal: the state is the fold of the log") {
    forAllNoShrink(Gen.listOf(Gen.zip(commands, sources))) { steps =>
      val j = journal()
      steps.foreach((command, sender) => j.run(command, sender): Unit)
      assertEquals(j.state, foldOf(j.log))
    }
  }

  property("Journal: run commits the entries of decide with the next seq, and a refused command adds nothing") {
    forAllNoShrink(Gen.listOf(Gen.zip(commands, sources)), commands, sources) { (before, command, sender) =>
      val j = journal()
      before.foreach((c, s) => j.run(c, s): Unit)
      val (state, log) = (j.state, j.log)
      val result       = j.run(command, sender)
      decide(command, state) match
        case Left(rejection) =>
          assertEquals(result, Left(rejection))
          assertEquals(j.log, log)
        case Right(entries) =>
          assertEquals(result, Right(j.state))
          val added = j.log.drop(log.size)
          assertEquals(added.map(_.entry), entries)
          assertEquals(added.map(_.seq), entries.indices.toVector.map(state.nextSeq + _))
    }
  }

  test("Journal: each entry has the time of the clock and the source of its command") {
    val j = MemoryJournal(() => 4242L, Secrets.none)
    j.run(Command.Activate(versions, config()), Source.Harness): Unit
    j.run(Command.Send(RequestId("r"), "hello", Map.empty), Source.User): Unit
    j.commit(Entry.StepStarted(0)): Unit
    j.run(Command.Commit(Vector(Entry.Acted("", "hi", None))), Source.Policy): Unit
    assert(j.log.forall(_.meta.atMillis == 4242L))
    val sources = j.log.map(logged => (logged.entry, logged.meta.source))
    assert(sources.contains((Entry.Input(RequestId("r"), "hello", Map.empty), Source.User)))
    assert(sources.contains((Entry.StepStarted(0), Source.Harness)))
    assert(sources.contains((Entry.Acted("", "hi", None), Source.Policy)))
  }

  test("Journal: the mask hides each known secret before the commit") {
    val secret = "fake-secret-value-1"
    val j      = journal(Secrets(Map("FAKE_KEY" -> secret)))
    j.run(Command.Activate(versions, config()), Source.Harness): Unit
    j.run(Command.Send(RequestId("r"), s"use $secret now", Map("k" -> Json.String(secret))), Source.User): Unit
    val inputs = j.log.collect { case Logged(_, _, input: Entry.Input) => input }
    assertEquals(
      inputs,
      Vector(Entry.Input(RequestId("r"), "use «secret:FAKE_KEY» now", Map("k" -> Json.String("«secret:FAKE_KEY»"))))
    )
    assert(!j.log.toString.contains(secret))
  }

  test("Journal: commands from several threads each commit once, with distinct seq") {
    val j = journal()
    j.run(Command.Activate(versions, config()), Source.Harness): Unit
    val pool  = Executors.newFixedThreadPool(8)
    val start = CountDownLatch(1)
    try
      (0 until 200).foreach { i =>
        val send: Runnable = () =>
          start.await()
          j.run(Command.Send(RequestId(s"r$i"), s"m$i", Map.empty), Source.User): Unit
        pool.submit(send): Unit
      }
      start.countDown()
    finally
      pool.shutdown()
      pool.awaitTermination(30, TimeUnit.SECONDS): Unit
    val inputs = j.log.collect { case Logged(_, _, Entry.Input(id, _, _)) => id }
    assertEquals(inputs.distinct.size, 200)
    assertEquals(j.log.map(_.seq), j.log.indices.toVector.map(_.toLong))
    assertEquals(j.state, foldOf(j.log))
  }
