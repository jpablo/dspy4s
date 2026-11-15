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

  test("Job: after JobEnded the record of the job holds its end, and a later stop mark keeps the end") {
    // A later host gives the end as the status of the job, and not Lost (10.3).
    val job     = EffectId(cell, 0)
    val spawned = state(journal((cellEntries() :+ Entry.EffectStarted(job, "spawn", args))*))
    assertEquals(spawned.jobs.get(job).map(_.end), Some(None))
    val ended = commit(spawned, Vector(Entry.JobEnded(job, JobEnd.Exited(3))))
    assertEquals(ended.jobs.get(job), Some(JobRecord(spawned.lastTurn, stopRequested = false, Some(JobEnd.Exited(3)))))
    val marked = commit(ended, Vector(Entry.Ended(SessionOutcome.Parked, "done")))
    assertEquals(marked.jobs.get(job), Some(JobRecord(spawned.lastTurn, stopRequested = true, Some(JobEnd.Exited(3)))))
  }

  property("Job: a JobEnded changes only the end of its job, and nothing for a job that the state does not have") {
    val end = JobEnd.Exited(7)
    forAll(schedules) { run =>
      for s <- everyState(run); job <- s.jobs.keys.toVector.take(1) :+ EffectId(-1L, 0) do
        val after = commit(s, Vector(Entry.JobEnded(job, end)))
        assertEquals(after.jobs.keySet, s.jobs.keySet)
        for (id, record) <- s.jobs do
          assertEquals(after.jobs(id), if id == job then record.copy(end = Some(end)) else record, s"the job $id")
        assertEquals(after.copy(jobs = s.jobs), s.copy(nextSeq = s.nextSeq + 1))
    }
  }

  property("Job: a journal has at most one JobEnded for each job, and it comes after the spawn of the job") {
    forAll(schedules) { run =>
      val entries = run.journal.map(_.entry)
      val spawned = entries.scanLeft(Set.empty[EffectId]) {
        case (ids, Entry.EffectStarted(id, "spawn", _)) => ids + id
        case (ids, _)                                   => ids
      }
      for (entry, ids) <- entries.zip(spawned) do
        entry match
          case Entry.JobEnded(job, _) => assert(ids(job), s"the end of $job comes before its spawn")
          case _                      => ()
      val ends = entries.collect { case Entry.JobEnded(job, _) => job }
      assertEquals(ends.distinct, ends)
    }
  }

  test("Job: the random runs have ends of jobs, with an exit code and after a stop") {
    // So the laws of the random runs also see JobEnded.
    val ends = Iterator.continually(schedules.sample).flatten.take(300).flatMap(_.journal).collect {
      case Logged(_, _, Entry.JobEnded(_, end)) => end
    }.toSet
    assert(ends.exists { case JobEnd.Exited(_) => true; case _ => false }, ends.toString)
    assert(ends(JobEnd.Stopped), ends.toString)
  }

  property("Job: a journal fork starts with no jobs") {
    forAll(schedules)(run => assert(forked(run.journal).jobs.isEmpty))
  }
