package dspy4s.gepa

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.updated
import dspy4s.core.data.Example
import dspy4s.core.data.RawPrediction
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.gepa.contracts.FeedbackMetric
import dspy4s.gepa.contracts.ScoreWithFeedback
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.programs.ProgramRunner
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.optimization.OptimizableId
import dspy4s.programs.optimization.OptimizableStructure
import dspy4s.programs.strategies.DynamicPredict
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

/** Calls `stepper` one time for each question of [[RetryPipeline.questions]] and goes on after a call that failed. Then
  * `finisher` runs. A call that failed can thus be in the trace of a run that passed. Top-level so
  * `OptimizableStructure.derived` sees its Mirror field labels.
  */
final case class RetryPipeline(stepper: DynamicPredict, finisher: DynamicPredict)

object RetryPipeline:
  val questions: Vector[String] = Vector("first try", "second try", "third try")

  given ProgramRunner[RetryPipeline] with
    def run(program: RetryPipeline, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, RawPrediction] =
      val notes = questions.flatMap { question =>
        val inputs = DynamicValues.mergeRecords(call.input, DynamicValues.record("question" := question))
        program.stepper(call.mapInput(_ => inputs)).toOption.flatMap(p => DynamicValues.recordGet(p.output, "note"))
      }
      val note = notes.lastOption.getOrElse(DynamicValue.Null)
      program.finisher(call.mapInput(_ => call.input.updated("note", note))).map(_.raw)

/** The failure rules of the reflective dataset against upstream `make_reflective_dataset`
  * (`dspy/teleprompt/gepa/gepa_utils.py`) and the option `add_format_failure_as_feedback` (PORT_GAPS G-44, item
  * "failure feedback"). The suite also covers the seeded selection of one call of a leaf that ran more than one time,
  * and the iteration that has no reflective record (PORT_GAPS G-44, item "empty reflective data").
  */
class GepaFailureFeedbackSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val badReply = "I do not know the format."

  /** Answers by the output field that the last user message asks for.
    *
    * @param failFinisher
    *   the reply to the finisher has no field marker, so the parse of that call fails
    * @param failQuestions
    *   the reply to a stepper call with one of these questions has no field marker
    * @param crashQuestions
    *   a stepper call with one of these questions gets a `Left` and no response
    */
  private final class FormatLm(
      failFinisher  : Boolean     = false,
      failQuestions : Set[String] = Set.empty,
      crashQuestions: Set[String] = Set.empty
  ) extends LanguageModel:
    val calls: AtomicInteger                                                                   = new AtomicInteger(0)
    override val id: String                                                                    = "format"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _        = calls.incrementAndGet()
      val ask      = request.messages.lastOption.flatMap(_.text).getOrElse("")
      val finisher = ask.contains("[[ ## answer ## ]]")
      if !finisher && crashQuestions.exists(ask.contains) then Left(RuntimeError("format_lm", "the model is down"))
      else
        val text =
          if finisher then
            if failFinisher then badReply
            else "[[ ## answer ## ]]\nParis\n\n[[ ## source ## ]]\natlas\n\n[[ ## completed ## ]]"
          else if failQuestions.exists(ask.contains) then badReply
          else "[[ ## note ## ]]\nstep note\n\n[[ ## tag ## ]]\ngeo\n\n[[ ## completed ## ]]"
        Right(LmResponse(outputs = Vector(LmOutput(text = text))))

  /** Records the prompts that the reflection LM gets. With `fail`, each call returns a `Left`. */
  private final class RecordingReflectionLm(fail: Boolean = false) extends LanguageModel:
    val prompts: ArrayBuffer[String]                                                           = ArrayBuffer.empty
    override val id: String                                                                    = "reflect"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _ = prompts.synchronized(prompts += request.messages.flatMap(_.text).mkString("\n"))
      if fail then Left(RuntimeError("reflect_lm", "the reflection model is down"))
      else Right(LmResponse(outputs = Vector(LmOutput(text = ReflectionFixtures.reply("A new instruction.")))))

  /** Records what the adapter gives to the metric for each component. The score is never perfect. */
  private final class RecordingMetric extends FeedbackMetric:
    val componentCalls: ArrayBuffer[(String, RawPrediction, Vector[TraceEntry])] = ArrayBuffer.empty
    override def name: String                                                    = "recording"
    override def feedback(
        example       : Example,
        prediction    : RawPrediction,
        trace         : Vector[TraceEntry],
        component     : Option[String],
        componentTrace: Vector[TraceEntry]
    )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
      component.foreach(name => componentCalls.synchronized(componentCalls += ((name, prediction, componentTrace))))
      Right(ScoreWithFeedback(0.5, s"feedback for ${component.getOrElse("program")}"))

  private def predict(layout: String, instruction: String): DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse(layout).toOption.get.withInstructions(Some(instruction)))

  // Two output fields for each leaf: a reply with no field marker then fails the parse.
  private val stepper  = predict("question -> note, tag", "Take a note.")
  private val finisher = predict("question, note -> answer, source", "Answer with the note.")

  /** `stepper` runs two times ("Capital of France?", then "second pass"), then `finisher` runs one time. */
  private val loop = LoopPipeline(stepper, finisher)

  private val retry = RetryPipeline(stepper, finisher)

  private val stepperId  = OptimizableId(0)
  private val finisherId = OptimizableId(1)

  // The engine starts an iteration while its worst cost fits in the budget: 2 minibatch evaluations of 2 examples
  // and 1 validation of 2 examples. The seed validation costs 2 calls, and each program run calls the task LM 3 times.
  private val roundRobin =
    GepaConfig(budget = GepaBudget.MetricCalls(MetricCallCount(20)), reflectionMinibatchSize = MinibatchSize(2))

  private def examples(size: Int): Vector[Example] =
    Vector.tabulate(size)(index =>
      Example(
        values = DynamicValues.record("question" := s"Capital of France ($index)?", "answer" := "Paris"),
        inputKeys = Set("question")
      )
    )

  private def withTaskLm[A](lm: LanguageModel)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      body(using RuntimeEnvironment.current)
    }

  /** The reflective dataset of the two leaves of `program` for one evaluation of `dataset`. */
  private def reflect[P](
      program             : P,
      lm                  : LanguageModel,
      metric              : FeedbackMetric,
      addFailureAsFeedback: Boolean         = false,
      seed                : Long            = 0L,
      dataset             : Vector[Example] = examples(1)
  )(using OptimizableStructure[P], ProgramRunner[P]): Map[OptimizableId, Vector[ReflectiveRecord]] =
    withTaskLm(lm) {
      val adapter   = new GepaAdapter(program, metric, addFormatFailureAsFeedback = addFailureAsFeedback, seed = seed)
      val candidate = Candidate.seed(program)
      val batch     = adapter.evaluate(dataset, candidate, captureTraces = true)
      adapter
        .makeReflectiveDataset(candidate, batch, Vector(stepperId, finisherId))
        .fold(error => fail(error.message), identity)
    }

  test("the option is off by default, as upstream") {
    assertEquals(GepaConfig(budget = GepaBudget.MetricCalls(MetricCallCount(10))).addFormatFailureAsFeedback, false)
  }

  test("by default a run that failed gives no record, and the metric gets no call for a component") {
    val metric  = new RecordingMetric
    val records = reflect(loop, new FormatLm(failFinisher = true), metric)
    assertEquals(records(stepperId), Vector.empty)
    assertEquals(records(finisherId), Vector.empty)
    assertEquals(metric.componentCalls.toVector, Vector.empty)
  }

  test("with the option on, the leaf that failed gets a record from its failed call") {
    val metric  = new RecordingMetric
    val records = reflect(loop, new FormatLm(failFinisher = true), metric, addFailureAsFeedback = true)

    assertEquals(records(finisherId).size, 1)
    val record = records(finisherId).head
    // The inputs are those of the predictor call, with the note of the stepper.
    assert(ReflectionFixtures.text(record.inputs).contains("step note"), record.inputs)
    assertEquals(
      record.generatedOutputs,
      // The text ends with the fence and two line ends, as upstream.
      ReflectionFixtures.string(
        "Couldn't parse the output as per the expected output format. The model's raw response was:\n" +
          s"```\n$badReply\n```\n\n"
      )
    )
    assert(record.feedback.startsWith("Your output failed to parse. Follow this structure:\nsystem: "), record.feedback)
    assert(record.feedback.contains("[[ ## answer ## ]]"), record.feedback)
    assert(record.feedback.contains("[[ ## source ## ]]"), record.feedback)
    assert(record.feedback.contains("Answer with the note."), record.feedback)
    assert(record.feedback.contains("\nuser: Respond with the corresponding output fields"), record.feedback)
  }

  test("with the option on, a leaf that passed in a run that failed gets no record, and the metric gets no call") {
    val metric  = new RecordingMetric
    val records = reflect(loop, new FormatLm(failFinisher = true), metric, addFailureAsFeedback = true)
    assertEquals(records(stepperId), Vector.empty)
    assertEquals(metric.componentCalls.toVector, Vector.empty)
  }

  test("the structure in the feedback has the instruction of the candidate") {
    val records = withTaskLm(new FormatLm(failFinisher = true)) {
      val adapter   = new GepaAdapter(loop, new RecordingMetric, addFormatFailureAsFeedback = true)
      val candidate = Candidate.seed(loop).updated(finisherId, Some("Answer in one word."))
      val batch     = adapter.evaluate(examples(1), candidate, captureTraces = true)
      adapter
        .makeReflectiveDataset(candidate, batch, Vector(finisherId))
        .fold(error => fail(error.message), identity)
    }
    val feedback = records(finisherId).head.feedback
    assert(feedback.contains("Answer in one word."), feedback)
    assert(!feedback.contains("Answer with the note."), feedback)
  }

  test("with the option on, a failed call wins over the calls of the same leaf that passed") {
    val lm      = new FormatLm(failQuestions = Set("second pass"))
    val records = reflect(loop, lm, new RecordingMetric, addFailureAsFeedback = true)
    assertEquals(records(stepperId).size, 1)
    assert(
      ReflectionFixtures.text(records(stepperId).head.inputs).contains("second pass"),
      records(stepperId).head.inputs
    )
    assert(
      ReflectionFixtures.text(records(stepperId).head.generatedOutputs).contains(badReply),
      records(stepperId).head.generatedOutputs
    )
    // The finisher did not run.
    assertEquals(records(finisherId), Vector.empty)
  }

  test("with the option off, the calls that passed before a failed call give no record") {
    val lm      = new FormatLm(failQuestions = Set("second pass"))
    val records = reflect(loop, lm, new RecordingMetric)
    assertEquals(records(stepperId), Vector.empty)
    assertEquals(records(finisherId), Vector.empty)
  }

  test("with the option on, the first of two failed calls gives the record, also in a run that passed") {
    val metric  = new RecordingMetric
    val lm      = new FormatLm(failQuestions = Set("first try", "second try"))
    val records = reflect(retry, lm, metric, addFailureAsFeedback = true)

    assertEquals(records(stepperId).size, 1)
    assert(
      ReflectionFixtures.text(records(stepperId).head.inputs).contains("first try"),
      records(stepperId).head.inputs
    )
    assert(
      records(stepperId).head.feedback.startsWith("Your output failed to parse."),
      records(stepperId).head.feedback
    )
    // The run passed, so the finisher has a record with the feedback of the metric.
    assertEquals(records(finisherId).map(_.feedback), Vector("feedback for finisher"))
    // The metric gets a call for the finisher only, with the prediction of the run.
    assertEquals(metric.componentCalls.toVector.map(_._1), Vector("finisher"))
    assert(metric.componentCalls.toVector.forall(_._2.get("answer").isDefined), metric.componentCalls.toString)
  }

  test("with the option off, the failed calls of a run that passed do not count") {
    val metric  = new RecordingMetric
    val lm      = new FormatLm(failQuestions = Set("first try", "second try"))
    val records = reflect(retry, lm, metric)

    assertEquals(records(stepperId).size, 1)
    assert(
      ReflectionFixtures.text(records(stepperId).head.inputs).contains("third try"),
      records(stepperId).head.inputs
    )
    assertEquals(records(stepperId).head.feedback, "feedback for stepper")
    val stepperTraces = metric.componentCalls.toVector.collect { case ("stepper", _, componentTrace) => componentTrace }
    assertEquals(stepperTraces.map(_.map(_.failure)), Vector(Vector(None)))
  }

  test("with the option on, a failed call with no model response gives its error as the feedback") {
    val metric  = new RecordingMetric
    val lm      = new FormatLm(crashQuestions = Set("second pass"))
    val records = reflect(loop, lm, metric, addFailureAsFeedback = true)

    assertEquals(records(stepperId).size, 1)
    val record = records(stepperId).head
    assert(ReflectionFixtures.text(record.inputs).contains("second pass"), record.inputs)
    assertEquals(record.generatedOutputs, ReflectionFixtures.string("(no output)"))
    assert(record.feedback.startsWith("The call failed with this error: "), record.feedback)
    assert(record.feedback.contains("the model is down"), record.feedback)
    assertEquals(metric.componentCalls.toVector, Vector.empty)
  }

  /** For each example, `true` when the record of the stepper comes from its second call. */
  private def secondCallSelected(seed: Long): Vector[Boolean] =
    val records = reflect(loop, new FormatLm, new RecordingMetric, seed = seed, dataset = examples(16))
    assertEquals(records(stepperId).size, 16)
    records(stepperId).map(record => ReflectionFixtures.text(record.inputs).contains("second pass"))

  test("a leaf that ran two times: the seeded source selects among the two calls") {
    val selection = secondCallSelected(seed = 3L)
    assert(selection.contains(true) && selection.contains(false), selection.toString)
  }

  test("the same seed gives the same selection") {
    assertEquals(secondCallSelected(seed = 3L), secondCallSelected(seed = 3L))
  }

  test("the seeds 0 to 15 do not all make the same first choice") {
    val firstChoice = (0L until 16L).toVector.map { seed =>
      val records = reflect(loop, new FormatLm, new RecordingMetric, seed = seed)
      ReflectionFixtures.text(records(stepperId).head.inputs).contains("second pass")
    }
    assert(firstChoice.contains(true) && firstChoice.contains(false), firstChoice.toString)
  }

  private def config(addFailureAsFeedback: Boolean, seed: Long = 0L, budget: Int = 20, minibatch: Int = 2) =
    GepaConfig(
      budget = GepaBudget.MetricCalls(MetricCallCount.applyUnsafe(budget)),
      reflectionMinibatchSize = MinibatchSize.applyUnsafe(minibatch),
      componentSelector = ComponentSelector.All,
      addFormatFailureAsFeedback = addFailureAsFeedback,
      seed = seed
    )

  /** The prompts that the reflection LM gets in one `Gepa.compile` of the loop pipeline. */
  private def reflectionPrompts(lm: LanguageModel, config: GepaConfig, dataset: Vector[Example]): Vector[String] =
    val reflectionLm = new RecordingReflectionLm
    val result       = withTaskLm(lm) {
      new Gepa[LoopPipeline](new RecordingMetric, reflectionLm, config).compile(loop, dataset, dataset)
    }
    assert(result.isRight, result.left.toOption.map(_.message).getOrElse(""))
    reflectionLm.prompts.toVector

  test("Gepa.compile passes the option to the adapter") {
    val off = reflectionPrompts(new FormatLm(failFinisher = true), config(addFailureAsFeedback = false), examples(2))
    val on  = reflectionPrompts(new FormatLm(failFinisher = true), config(addFailureAsFeedback = true), examples(2))
    // With the option off, a run that failed gives no record, so the reflection LM gets no call.
    assertEquals(off, Vector.empty)
    assert(on.exists(_.contains("Your output failed to parse")), on.mkString("\n---\n"))
  }

  test("Gepa.compile passes the seed to the adapter") {
    // One example and a minibatch of one: the sampler and the candidate selector have one choice only, so the prompts
    // for the stepper depend on the seed through the random source of the adapter only. Each iteration gives one
    // prompt for the stepper.
    val selections = (0L until 4L).toVector.map { seed =>
      val prompts =
        reflectionPrompts(new FormatLm, config(addFailureAsFeedback = false, seed, minibatch = 1), examples(1))
      prompts.filter(_.contains("Take a note.")).map(_.contains("second pass"))
    }
    assert(selections.forall(_.sizeIs > 4), selections.toString)
    assert(selections.distinct.sizeIs > 1, selections.toString)
  }

  test("the reflection LM gets no call for a component with zero records") {
    // The finisher fails in each run. The stepper thus has no record, and the finisher has the record of its failed
    // call. The selector gives the two components to each iteration.
    val prompts = reflectionPrompts(new FormatLm(failFinisher = true), config(addFailureAsFeedback = true), examples(2))
    assert(prompts.nonEmpty)
    assert(prompts.forall(_.contains("Answer with the note.")), prompts.mkString("\n---\n"))
    assert(!prompts.exists(_.contains("Take a note.")), prompts.mkString("\n---\n"))
  }

  test("a run in which every example fails ends, returns the seed candidate, and makes zero reflection LM calls") {
    val taskLm       = new FormatLm(failFinisher = true)
    val reflectionLm = new RecordingReflectionLm
    val result       = withTaskLm(taskLm) {
      new Gepa[LoopPipeline](new RecordingMetric, reflectionLm, roundRobin).compile(loop, examples(2), examples(2))
    }.fold(error => fail(error.message), identity)

    assertEquals(result.bestCandidate, Candidate.seed(loop))
    assertEquals(result.numCandidates, 1)
    assertEquals(reflectionLm.prompts.toVector, Vector.empty)
    // An iteration with no proposal costs its minibatch evaluation only (2 calls), so 7 iterations run: 2 + 7 * 2.
    assertEquals(result.totalMetricCalls, 16)
    // No acceptance evaluation ran: the task LM served the seed validation and the 7 minibatch evaluations only.
    assertEquals(taskLm.calls.get(), 3 * (2 + 7 * 2))
  }

  test("when each reflection call fails, the iteration makes no acceptance evaluation") {
    val taskLm       = new FormatLm
    val reflectionLm = new RecordingReflectionLm(fail = true)
    val result       = withTaskLm(taskLm) {
      new Gepa[LoopPipeline](new RecordingMetric, reflectionLm, roundRobin).compile(loop, examples(2), examples(2))
    }.fold(error => fail(error.message), identity)

    assertEquals(result.bestCandidate, Candidate.seed(loop))
    // Each of the 7 iterations asks the reflection LM two times and gets no new text: the engine calls the proposer
    // one more time after a failure (gepa `_propose_texts_batch_safe`).
    assertEquals(reflectionLm.prompts.size, 14)
    assertEquals(result.totalMetricCalls, 16)
    assertEquals(taskLm.calls.get(), 3 * (2 + 7 * 2))
  }
