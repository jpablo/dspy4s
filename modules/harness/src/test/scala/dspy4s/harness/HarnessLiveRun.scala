package dspy4s.harness

import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.kernel.*
import dspy4s.harness.sandbox.{Backends, HostList}
import munit.FunSuite

import java.nio.file.Files
import scala.concurrent.duration.*

import LiveSupport.*
import Models.*

/** A live run of a harness with a real model (CODE_HARNESS_DESIGN.md, 9): one test for each task of `HarnessTasks`.
  *
  * It runs only with `HARNESS_LIVE=true` (see `LiveSupport`). Each test writes the transcript of its task to the folder
  * `tasks` of the output folder, and a line of `tasks/summary.tsv`. A wrong answer does not fail a test, because the
  * transcript and the summary are the result.
  */
class HarnessLiveRun extends FunSuite:

  override val munitTimeout: Duration = 15.minutes

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  HarnessTasks.all.foreach(task => test(s"Task: ${task.name}")(run(task)))

  /** Runs one task in a new place with the workspace of the task set. */
  private def run(task: HarnessTask): Unit =
    assume(live, "set HARNESS_LIVE=true for a live run")
    val place = Place()
    try
      HarnessTasks.writeWorkspace(place.root)
      var journal = Vector.empty[Logged]
      val lm      = Recording(model())
      val env     = HarnessEnv(
        Some(place.root),
        place.home,
        HostList.empty,
        Backends.detect(),
        Secrets.known(sys.env, Map.empty),
        journals = journal = _
      )
      val start    = System.nanoTime()
      val result   = withModel(lm)(Harness.forTask(signature, env, HarnessLimits(steps = 8))(Question(task.question)))
      val seconds  = (System.nanoTime() - start) / 1_000_000_000
      val answer   = result.map(_.output.answer.trim)
      val correct  = answer.exists(HarnessTasks.correct(task, _))
      val ended    = journal.collect { case Logged(_, _, Entry.TurnEnded(o)) => o.toString.takeWhile(_ != '(') }
      val restarts = journal.count {
        case Logged(_, _, Entry.Observed(output, _, _)) => output.contains("The harness started a new REPL")
        case _                                          => false
      }
      val dir    = out("tasks")
      val report = Vector(
        s"# ${task.name}",
        s"- question: ${task.question}",
        s"- expected: ${task.expected}",
        s"- result: $answer",
        s"- correct: $correct",
        s"- turn: ${ended.mkString(", ")}",
        s"- model calls: ${lm.calls.size}, $seconds s in all, restarts: $restarts",
        "",
        "# The journal",
        journal.map(l => render(l.entry)).mkString("\n\n"),
        "",
        "# The raw replies of the model",
        replies(lm)
      ).mkString("\n")
      Files.writeString(dir.resolve(s"${task.name}.md"), report): Unit
      lm.calls.lastOption.foreach((request, _, _) =>
        Files.writeString(dir.resolve(s"${task.name}.last-prompt.md"), text(request)): Unit
      )
      val shown = answer.fold(e => s"error: ${e.message.take(120)}", _.take(60)).replace("\t", " ").replace("\n", " ")
      append(
        dir.resolve("summary.tsv"),
        Vector(task.name, correct, lm.calls.size, seconds, ended.mkString(","), restarts, shown).mkString("\t")
      )
      println(
        s"TASK ${task.name}: correct=$correct calls=${lm.calls.size} seconds=$seconds restarts=$restarts answer=$shown"
      )
    finally place.close()
