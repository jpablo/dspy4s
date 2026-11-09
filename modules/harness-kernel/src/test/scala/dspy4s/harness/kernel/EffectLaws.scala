package dspy4s.harness.kernel

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import Fixtures.*
import Schedules.*

/** The laws Effect, Repair, Flush and Job of 4.7. */
class EffectLaws extends ScalaCheckSuite:

  private def effectIds(journal: Vector[Logged]): (Vector[EffectId], Vector[EffectId]) =
    val starts = journal.collect { case Logged(_, _, Entry.EffectStarted(id, _, _)) => id }
    val dones  = journal.collect { case Logged(_, _, Entry.EffectDone(id, _)) => id }
    (starts, dones)

  property("Effect: after a repair, each intent of a guest API call has exactly one outcome") {
    forAll(schedules) { run =>
      val (starts, dones) = effectIds(append(run.journal, repair(state(run.journal))))
      for id <- starts do assertEquals(dones.count(_ == id), 1, s"the outcomes of $id")
    }
  }

  property("Effect: after a repair, each cell has exactly one Observed") {
    forAll(schedules) { run =>
      val repaired = append(run.journal, repair(state(run.journal))).map(_.entry)
      val cells    = repaired.count { case Entry.Acted(_, _, Some(_)) => true; case _ => false }
      val observed = repaired.count { case Entry.Observed(_, _, _) => true; case _ => false }
      assertEquals(observed, cells)
    }
  }

  property("Effect: each outcome comes after its intent") {
    forAll(schedules) { run =>
      val entries = run.journal.map(_.entry)
      // The ids of the intents before each entry.
      val before = entries.scanLeft(Set.empty[EffectId]) {
        case (ids, Entry.EffectStarted(id, _, _)) => ids + id
        case (ids, _)                             => ids
      }
      for (entry, ids) <- entries.zip(before) do
        entry match
          case Entry.EffectDone(id, _) => assert(ids(id), s"the outcome of $id comes before its intent")
          case _                       => ()
    }
  }

  property("Repair: repair closes each open part") {
    forAll(states) { s =>
      val repaired = commit(s, repair(s))
      assert(repaired.open.isEmpty, s"open effects after the repair: ${repaired.open}")
      assertEquals(repaired.position, Position.NoTurn)
    }
  }

  property("Repair: repair of the state after a repair is empty") {
    forAll(states)(s => assertEquals(repair(commit(s, repair(s))), Vector.empty))
  }

  property("Flush: after a power loss, each guest API call that changed the world has its intent in the journal") {
    forAll(schedules) { run =>
      val kept = run.journal.take(run.durable).collect { case Logged(_, _, Entry.EffectStarted(id, _, _)) => id }.toSet
      for id <- run.changed do assert(kept(id), s"the intent of $id is gone")
    }
  }

  property("Flush: after a power loss, each Input for which a sender got an answer is in the journal") {
    forAll(schedules) { run =>
      val kept = run.journal.take(run.durable).collect { case Logged(_, _, Entry.Input(r, _, _)) => r }.toSet
      for request <- run.answered do assert(kept(request), s"the input of $request is gone")
    }
  }

  test("Flush: Input, Activated and an EffectStarted that is not read-only need a flush") {
    val id = EffectId(cell, 0)
    assert(needsFlush(Entry.Input(RequestId("r"), "hi", Map.empty)))
    assert(needsFlush(activated()))
    assert(needsFlush(Entry.EffectStarted(id, "write", args)))
    assert(!needsFlush(Entry.EffectStarted(id, "read", args)))
    assert(!needsFlush(Entry.EffectDone(id, EffectResult.Unknown)))
    assert(!needsFlush(Entry.TurnStarted))
  }

  property("Job: an Abort changes no job") {
    forAll(schedules) { run =>
      for s <- everyState(run) do
        decide(Command.Abort("stop"), s) match
          case Right(entries) => assertEquals(commit(s, entries).jobs, s.jobs)
          case Left(_)        => ()
    }
  }

  test("Job: a stop covers only the jobs with an intent before it") {
    val spawned = state(
      journal(
        (cellEntries() ++ Vector(
          Entry.JobsStopRequested(JobSelector.OfLastTurn, "stop"),
          Entry.EffectStarted(EffectId(cell, 0), "spawn", args)
        ))*
      )
    )
    assertEquals(spawned.jobs.get(EffectId(cell, 0)).map(_.stopRequested), Some(false))
  }

  test("Job: after Abort and then StopJobs(OfLastTurn), no job of that turn starts or runs") {
    val job     = EffectId(cell, 0)
    val running = state(journal((cellEntries() :+ Entry.EffectStarted(job, "spawn", args))*))
    val stopped = run(run(running, Command.Abort("stop")), Command.StopJobs(JobSelector.OfLastTurn, "stop all"))
    assertEquals(stopped.jobs.get(job).map(_.stopRequested), Some(true))
    val later = Entry.EffectStarted(EffectId(cell, 1), "spawn", args)
    assertEquals(decide(Command.Commit(Vector(later)), stopped), Left(Rejection.OutOfOrder(later)))
  }

  test("Job: StopJobs(OfLastTurn) leaves the jobs of an earlier turn") {
    val earlier = EffectId(cell, 0)
    val later   = EffectId(12, 0)
    val s       = state(
      journal(
        (cellEntries() ++ Vector(
          Entry.EffectStarted(earlier, "spawn", args),
          Entry.EffectDone(earlier, EffectResult.Value(args, "job")),
          Entry.Observed("", CellOutcome.Ok, Map.empty),
          Entry.TurnEnded(TurnOutcome.Replied("started")),
          Entry.Input(RequestId("r2"), "more", Map.empty),
          Entry.TurnStarted,
          Entry.StepStarted(0),
          Entry.Acted("", "", Some("spawn()")),
          Entry.EffectStarted(later, "spawn", args),
          Entry.JobsStopRequested(JobSelector.OfLastTurn, "stop")
        ))*
      )
    )
    assertEquals(s.jobs.get(earlier).map(_.stopRequested), Some(false))
    assertEquals(s.jobs.get(later).map(_.stopRequested), Some(true))
  }

  property("Job: StopJobs(OfLastTurn) marks only the jobs of the last turn") {
    forAll(schedules) { run =>
      for s <- everyState(run) do
        val stopped = commit(s, Vector(Entry.JobsStopRequested(JobSelector.OfLastTurn, "stop")))
        for (id, job) <- stopped.jobs do
          val expected = s.jobs.get(id).exists(_.stopRequested) || job.turn == s.lastTurn
          assertEquals(job.stopRequested, expected, s"the job $id of the turn ${job.turn}, the last turn ${s.lastTurn}")
    }
  }

  property("Job: Ended stops all jobs") {
    forAll(states, Gen.oneOf(SessionOutcome.Merged, SessionOutcome.Parked)) { (s, outcome) =>
      val closed = commit(s, Vector(Entry.Ended(outcome, "done")))
      assert(closed.jobs.values.forall(_.stopRequested), s"jobs after Ended: ${closed.jobs}")
    }
  }

  property("Job: a journal fork starts with no jobs") {
    forAll(schedules)(run => assert(forked(run.journal).jobs.isEmpty))
  }
