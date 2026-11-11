package dspy4s.harness.host

import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.{HarnessLimits, LiveSupport, MemoryTask, MemoryTasks, Models, Place, Secrets}
import dspy4s.harness.guest.StrategyName
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*
import dspy4s.harness.sandbox.{Backends, HostList}
import munit.FunSuite

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import scala.concurrent.duration.*

/** A live run of the memory metric with a real model (CODE_HARNESS_DESIGN.md, 7.1). It runs only with
  * `HARNESS_LIVE=true` (see `LiveSupport`).
  *
  * For each strategy, a work session gets the journal of the tasks of `MemoryTasks` in its file, and each question is
  * the next turn of that session. So the memory tree builds its lines once, in the first question. A question comes
  * after the earlier questions, so its distance is its distance in the task and the number of the earlier questions.
  * The run writes each answer to `memory/answers.tsv`, and the scores to `memory/scores.tsv`, in the output folder. A
  * wrong answer does not fail the test, because the scores are the result.
  */
class MemoryLiveRun extends FunSuite:

  override val munitTimeout: Duration = 120.minutes

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** The limits of the run: enough summary calls to build the memory of the whole session in its first turn. */
  private val limits = HarnessLimits(steps = 6, summaryCalls = 2_000)

  test("Memory live: the scores of the window and of the memory tree on the planted facts") {
    assume(LiveSupport.live, "set HARNESS_LIVE=true for a live run")
    val tasks   = MemoryTasks.generate(8L)
    val results = Vector(StrategyName.Window, StrategyName.MemoryTree).flatMap(strategy =>
      answers(strategy, tasks).map((task, answer) => (strategy, task, answer))
    )
    val folder = LiveSupport.out("memory")
    val rows   = results.zipWithIndex.map { case ((strategy, task, answer), i) =>
      Vector(
        strategy.toString,
        (task.distance + i % tasks.size).toString,
        task.expected,
        MemoryTasks.correct(task, answer).toString,
        task.name,
        answer.replace('\n', ' ').replace('\t', ' ')
      ).mkString("\t")
    }
    Files.writeString(
      folder.resolve("answers.tsv"),
      ("strategy\tdistance\texpected\tright\tname\tanswer" +: rows).mkString("", "\n", "\n"),
      UTF_8
    ): Unit
    val scores = results.groupBy(_._1).toVector.sortBy(_._1.toString).flatMap { (strategy, done) =>
      val pairs = done.map((_, task, answer) => (task, answer))
      s"$strategy\tall\t${MemoryTasks.score(pairs)}" +:
        MemoryTasks.byDistance(pairs).map((distance, score) => s"$strategy\t$distance\t$score")
    }
    Files.writeString(
      folder.resolve("scores.tsv"),
      ("strategy\tdistance\tscore" +: scores).mkString("", "\n", "\n"),
      UTF_8
    ): Unit
  }

  /** The answers of one work session with `strategy` to the questions of `tasks`, after the journal of the tasks. */
  private def answers(strategy: StrategyName, tasks: Vector[MemoryTask]): Vector[(MemoryTask, String)] =
    val place = Place()
    try withRoot { root =>
        val config = WorkRuntime.config(place.root, sys.env.getOrElse("DSPY_MODEL", "none"), limits)
          .copy(strategy = strategy)
        // The journal of the tasks, with the configuration of a work session with the strategy.
        val rows = tasks.head.journal.map { logged =>
          logged.entry match
            case Entry.Activated(versions, _) => logged.copy(entry = Entry.Activated(versions, config))
            case _                            => logged
        }
        val file = SessionFile.open(SessionFile.pathOf(root, id(1)), id(1)).fold(e => fail(e), identity)
        try assertEquals(file.append(rows, flush = true), Right(()))
        finally file.close()
        val env     = WorkEnv(place.home, HostList.empty, Backends.detect(), Secrets.known(sys.env, Map.empty), root)
        val runtime = Models.withModel(LiveSupport.model())(WorkRuntime.forWork(env, limits))
        withHost(root, runtime) { host =>
          val handle = opened(host, id(1), None)
          tasks.zipWithIndex.map { (task, i) =>
            val before = outcomes(handle).size
            assertEquals(handle.send(message(s"FAKE-QUESTION-$i", task.question)), Right(()))
            assert(eventually(1_800)(outcomes(handle).size > before), entries(handle).takeRight(5).toString)
            val answer = outcomes(handle).last match
              case TurnOutcome.Replied(text) => text
              case other                     => other.toString
            (task, answer)
          }
        }
      }
    finally place.close()
