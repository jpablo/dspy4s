package dspy4s.harness.host

import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.{HarnessLimits, LiveSupport, MemoryTask, MemoryTasks, Models, Place, Secrets}
import dspy4s.harness.guest.StrategyName
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*
import dspy4s.harness.sandbox.{Backends, HostList}
import munit.FunSuite

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.concurrent.duration.*

/** A live run of the memory metric with a real model (CODE_HARNESS_DESIGN.md, 7.1). It runs only with
  * `HARNESS_LIVE=true` (see `LiveSupport`). The summary requests get the option bag of `DSPY_SUMMARY_OPTIONS`
  * (`WorkEnv.summaryOptions`).
  *
  * For each strategy, a work session gets the journal of the tasks of `MemoryTasks` in its file, and each question is
  * the next turn of that session. So the memory tree builds its lines once, in the first question. A question comes
  * after the earlier questions, so its distance is its distance in the task and the number of the earlier questions.
  *
  * The run adds each answer to `memory/answers.tsv` as soon as it comes, with the seconds of the turn, and it writes
  * `memory/scores.tsv` after each strategy. A question with no answer after `Wait` is a wrong answer, and the session
  * asks no more questions. A wrong answer does not fail the test, because the scores are the result.
  */
class MemoryLiveRun extends FunSuite:

  override val munitTimeout: Duration = 180.minutes

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** The limits of the run: enough summary calls to build the memory of the whole session in its first turn. */
  private val limits = HarnessLimits(steps = 6, summaryCalls = 2_000)

  /** The longest wait for the answer to one question, in seconds. */
  private val Wait = 1_800

  test("Memory live: the scores of the window and of the memory tree on the planted facts") {
    assume(LiveSupport.live, "set HARNESS_LIVE=true for a live run")
    val options = WorkEnv.summaryOptions(sys.env).fold(e => fail(e), identity)
    val tasks   = MemoryTasks.generate(8L)
    val folder  = LiveSupport.out("memory")
    val answers = folder.resolve("answers.tsv")
    Files.writeString(answers, "strategy\tdistance\texpected\tright\tseconds\tname\tanswer\n", UTF_8): Unit
    val results =
      Vector(StrategyName.Window, StrategyName.MemoryTree).foldLeft(Vector.empty[Result]) { (done, strategy) =>
        val all = done ++ ask(strategy, tasks, options, answers)
        writeScores(folder.resolve("scores.tsv"), all)
        all
      }
    assert(results.nonEmpty, "no question got an answer")
  }

  /** One answer: the strategy, the task, its distance at the question, the answer, and the seconds of the turn. */
  private final case class Result(
      strategy: StrategyName,
      task    : MemoryTask,
      distance: Int,
      answer  : String,
      seconds : Long
  )

  /** The answers of one work session with `strategy` to the questions of `tasks`, after the journal of the tasks. */
  private def ask(
      strategy: StrategyName,
      tasks   : Vector[MemoryTask],
      options : zio.blocks.schema.DynamicValue.Record,
      answers : Path
  ): Vector[Result] =
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
        val env =
          WorkEnv(place.home, HostList.empty, Backends.detect(), Secrets.known(sys.env, Map.empty), root, options)
        val runtime = Models.withModel(LiveSupport.model())(WorkRuntime.forWork(env, limits))
        withHost(root, runtime) { host =>
          val handle = opened(host, id(1), None)
          tasks.zipWithIndex.foldLeft((Vector.empty[Result], true)) { case ((done, going), (task, i)) =>
            if !going then (done, false)
            else
              val before = outcomes(handle).size
              val start  = System.nanoTime()
              assertEquals(handle.send(message(s"FAKE-QUESTION-$i", task.question)), Right(()))
              val ended   = eventually(Wait)(outcomes(handle).size > before)
              val seconds = (System.nanoTime() - start) / 1_000_000_000L
              val answer  =
                if !ended then s"no answer after $Wait s"
                else
                  outcomes(handle).last match
                    case TurnOutcome.Replied(text) => text
                    case other                     => other.toString
              val result = Result(strategy, task, task.distance + i, answer, seconds)
              val line   = Vector(
                strategy.toString,
                result.distance.toString,
                task.expected,
                MemoryTasks.correct(task, answer).toString,
                seconds.toString,
                task.name,
                answer.replace('\n', ' ').replace('\t', ' ')
              ).mkString("", "\t", "\n")
              Files.writeString(answers, line, UTF_8, StandardOpenOption.APPEND): Unit
              (done :+ result, ended)
          }._1
        }
      }
    finally place.close()

  /** The scores of each strategy, all and for each distance. */
  private def writeScores(path: Path, results: Vector[Result]): Unit =
    val lines = results.groupBy(_.strategy).toVector.sortBy(_._1.toString).flatMap { (strategy, done) =>
      val pairs = done.map(r => (r.task.copy(distance = r.distance), r.answer))
      s"$strategy\tall\t${MemoryTasks.score(pairs)}" +:
        MemoryTasks.byDistance(pairs).map((distance, score) => s"$strategy\t$distance\t$score")
    }
    Files.writeString(path, ("strategy\tdistance\tscore" +: lines).mkString("", "\n", "\n"), UTF_8): Unit
