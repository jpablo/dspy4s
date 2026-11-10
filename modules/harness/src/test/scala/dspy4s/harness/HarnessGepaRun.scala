package dspy4s.harness

import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeContext, TraceEntry}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.gepa.contracts.{FeedbackMetric, ScoreWithFeedback}
import dspy4s.gepa.{Candidate, Gepa, GepaBudget, GepaConfig, GepaStatePersistence, MetricCallCount, MinibatchSize}
import dspy4s.harness.kernel.{Entry, Logged}
import dspy4s.harness.sandbox.{Backends, HostList}
import munit.FunSuite
import zio.blocks.schema.json.Json

import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*

import LiveSupport.*
import Models.*

/** A live GEPA run on the task set (CODE_HARNESS_DESIGN.md, 9): the first measured change of the leaves of a harness.
  *
  * It runs only with `HARNESS_LIVE=true` and `HARNESS_GEPA=true` (see `LiveSupport`). The tasks with an even index
  * train, and the tasks with an odd index validate. The metric gives 1.0 for a correct answer in one step, 0.1 less for
  * each more step down to 0.5, and 0 for a wrong answer. The state of the run is in the folder `gepa/state` of the
  * output folder, so a later run with a larger `HARNESS_GEPA_CALLS` goes on from it. The report is `gepa/report.md`.
  */
class HarnessGepaRun extends FunSuite:

  override val munitTimeout: Duration = 30.minutes

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val enabled = live && sys.env.get("HARNESS_GEPA").contains("true")

  private val byQuestion: Map[String, HarnessTask] = HarnessTasks.all.map(t => t.question -> t).toMap

  /** The policy steps of the last call of each question, from its journal. GEPA gives a metric the trace only for a
    * reflective minibatch, and an empty trace for a validation run, as upstream gives `trace=None`. So the metric reads
    * the steps from the journal, which `HarnessEnv.journals` gets at the end of each call, before the metric.
    */
  private val stepsByQuestion = ConcurrentHashMap[String, java.lang.Integer]()

  private def keep(log: Vector[Logged]): Unit =
    val question = log.collectFirst { case Logged(_, _, Entry.Input(_, _, values)) => values.get("question") }.flatten
    question.collect { case Json.String(text) => text }.foreach { text =>
      stepsByQuestion.put(text, log.count { case Logged(_, _, Entry.StepStarted(_)) => true; case _ => false }): Unit
    }

  /** The policy steps of a call in a trace: the entries with the output field `text`. The finisher has no such field.
    */
  private def steps(trace: Vector[TraceEntry]): Int =
    trace.count(e => !e.withinPredictor && DynamicValues.recordGet(e.outputs, "text").isDefined)

  /** The score of a call, and its feedback for the reflection. */
  private val metric: FeedbackMetric = new FeedbackMetric:
    override def name: String = "correct_in_few_steps"
    override def feedback(
        example       : Example,
        prediction    : RawPrediction,
        trace         : Vector[TraceEntry],
        component     : Option[String],
        componentTrace: Vector[TraceEntry]
    )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
      val question = example.get("question").map(DynamicValues.renderText).getOrElse("")
      val got      = prediction.get("answer").map(DynamicValues.renderText).getOrElse("")
      val count    = Option(stepsByQuestion.get(question)).map(_.intValue).getOrElse(steps(trace))
      val parts    = trace.map(e => s"${e.component}:${e.withinPredictor}:${e.outputs.fields.map(_._1).mkString("/")}")
      append(
        out("gepa").resolve("metric.tsv"),
        Vector(
          question.take(40),
          got.take(30),
          count,
          steps(trace),
          trace.size,
          parts.mkString(" ").take(300)
        ).mkString("\t")
      )
      val rule = HarnessGepaRun.rule
      byQuestion.get(question) match
        case None       => Right(ScoreWithFeedback(0.0, s"The task of this question is not known. $rule"))
        case Some(task) =>
          if HarnessTasks.correct(task, got) then
            val score = HarnessGepaRun.score(correct = true, count)
            Right(ScoreWithFeedback(score, s"Correct ('$got') after $count steps, so the score is $score. $rule"))
          else
            Right(ScoreWithFeedback(
              0.0,
              s"Incorrect: the expected answer is '${task.expected}', but the program gave '$got' after $count steps. $rule"
            ))

  test("GEPA: change the leaves of a harness on the task set, and measure the change") {
    assume(enabled, "set HARNESS_LIVE=true and HARNESS_GEPA=true for a GEPA run")
    val place = Place()
    try
      HarnessTasks.writeWorkspace(place.root)
      val lm  = Recording(model())
      val env = HarnessEnv(
        Some(place.root),
        place.home,
        HostList.empty,
        Backends.detect(),
        Secrets.known(sys.env, Map.empty),
        journals = (_, log) => keep(log)
      )
      val harness  = Harness.forTask(signature, env, HarnessLimits(steps = 8))
      val examples = HarnessTasks.examples.zipWithIndex
      val train    = examples.collect { case (e, i) if i % 2 == 0 => e }
      val valset   = examples.collect { case (e, i) if i % 2 == 1 => e }
      val calls    = sys.env.get("HARNESS_GEPA_CALLS").flatMap(_.toIntOption).getOrElse(60)
      val dir      = out("gepa")
      val gepa     = new Gepa[Harness[Question, Answer]](
        metric,
        reflectionLm = lm,
        GepaConfig(
          budget = GepaBudget.MetricCalls(MetricCallCount.applyUnsafe(calls)),
          reflectionMinibatchSize = MinibatchSize.applyUnsafe(3),
          seed = 0L,
          runDir = Some(Files.createDirectories(dir.resolve("state")))
        )
      )
      val start = System.nanoTime()
      withModel(lm)(gepa.compile(harness, trainset = train, valset = valset)) match
        case Left(error)   => fail(s"GEPA stopped with an error: ${error.message}")
        case Right(result) =>
          val minutes                                          = (System.nanoTime() - start) / 60_000_000_000L
          val seed                                             = result.state.candidates.headOption.getOrElse(Map.empty)
          def shown(candidate: Map[?, Option[String]]): String =
            candidate.toVector.map((id, text) => s"### $id\n\n${text.getOrElse("(no instructions)")}").mkString("\n\n")
          val scores =
            result.state.candidates.indices.map(i => f"- candidate $i: ${result.state.aggregateScore(i)}%.3f")
          val report = Vector(
            "# A GEPA run on the task set of the harness",
            s"- metric calls: ${result.totalMetricCalls}, iterations: ${result.iterations}, stop: ${result.stopReason}",
            s"- model calls of this run: ${lm.calls.size}, $minutes minutes",
            f"- seed validation score: ${result.state.aggregateScore(0)}%.3f",
            f"- best validation score: ${result.bestScore}%.3f (candidate ${result.state.bestIndex})",
            "",
            "## The validation score of each candidate",
            scores.mkString("\n"),
            "",
            "## The seed instructions",
            shown(seed),
            "",
            "## The best instructions",
            shown(result.bestCandidate)
          ).mkString("\n")
          Files.writeString(dir.resolve("report.md"), report): Unit
          println(
            f"GEPA seed=${result.state.aggregateScore(0)}%.3f best=${result.bestScore}%.3f candidates=${result.numCandidates} " +
              s"calls=${result.totalMetricCalls} stop=${result.stopReason}"
          )
    finally place.close()
  }

  /** Runs `which` candidate of the saved GEPA state on each task, and writes a line for each task to `compare.tsv`. */
  private def compare(which: String): Unit =
    assume(enabled, "set HARNESS_LIVE=true and HARNESS_GEPA=true for a GEPA run")
    val dir   = out("gepa")
    val state = GepaStatePersistence.load(dir.resolve("state")).fold(e => fail(e), identity)
      .getOrElse(fail("no saved GEPA state: run the GEPA test first"))
    val index = if which == "seed" then 0 else state.bestIndex
    val place = Place()
    try
      HarnessTasks.writeWorkspace(place.root)
      val lm  = Recording(model())
      val env = HarnessEnv(
        Some(place.root),
        place.home,
        HostList.empty,
        Backends.detect(),
        Secrets.known(sys.env, Map.empty),
        journals = (_, log) => keep(log)
      )
      val program =
        Candidate.applyTo(Harness.forTask(signature, env, HarnessLimits(steps = 8)), state.candidates(index))
      HarnessTasks.all.foreach { task =>
        val start   = System.nanoTime()
        val answer  = withModel(lm)(program(Question(task.question))).map(_.output.answer.trim)
        val seconds = (System.nanoTime() - start) / 1_000_000_000
        val correct = answer.exists(HarnessTasks.correct(task, _))
        val count   = Option(stepsByQuestion.get(task.question)).map(_.intValue).getOrElse(0)
        val shown   = answer.fold(e => s"error: ${e.message.take(80)}", _.take(40)).replace("\t", " ").replace("\n", " ")
        append(
          dir.resolve("compare.tsv"),
          Vector(
            which,
            index,
            task.name,
            correct,
            count,
            HarnessGepaRun.score(correct, count),
            seconds,
            shown
          ).mkString("\t")
        )
      }
    finally place.close()

  test("GEPA compare: the seed candidate on each task")(compare("seed"))

  test("GEPA compare: the best candidate on each task")(compare("best"))

object HarnessGepaRun:

  /** The rule of the score, as the feedback states it. */
  val rule: String =
    "The score is 1.0 for a correct answer in one step, 0.1 less for each more step down to 0.5, and " +
      "0 for a wrong answer."

  /** The score of a call: 1.0 for a correct answer in one step, 0.1 less for each more step down to 0.5, and 0 for a
    * wrong answer.
    */
  def score(correct: Boolean, steps: Int): Double =
    if correct then math.max(0.5, 1.0 - 0.1 * (steps - 1).max(0)) else 0.0
