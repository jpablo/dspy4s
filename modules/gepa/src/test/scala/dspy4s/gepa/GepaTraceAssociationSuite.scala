package dspy4s.gepa

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
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
import dspy4s.programs.optimization.OptimizableLeaf
import dspy4s.programs.strategies.DynamicPredict
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer

/** Two leaves, three predictor calls: `stepper` runs two times, then `finisher` runs one time. Top-level so
  * `OptimizableStructure.derived` sees its Mirror field labels.
  */
final case class LoopPipeline(stepper: DynamicPredict, finisher: DynamicPredict)

object LoopPipeline:
  given ProgramRunner[LoopPipeline] with
    def run(program: LoopPipeline, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, RawPrediction] =
      for
        _      <- program.stepper(call)
        second <- program.stepper(call.mapInput(_.updated("question", DynamicValues.fromAny("second pass"))))
        note    = DynamicValues.recordGet(second.output, "note").getOrElse(DynamicValue.Null)
        result <- program.finisher(call.mapInput(_ => call.input.updated("note", note)))
      yield result.raw

/** The GEPA adapter finds the calls of a leaf by the predictor key of each trace entry, not by the position of the
  * entry (PORT_GAPS G-44; UPGRADE-3.2.1-to-3.4.0, decision D2).
  */
class GepaTraceAssociationSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** Answers by the output field that the prompt asks for. */
  private final class LoopLm extends LanguageModel:
    override val id: String                                                                    = "loop"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val prompt = request.messages.flatMap(_.text).mkString("\n")
      val text   =
        if prompt.contains("## answer ##") then "[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]"
        else "[[ ## note ## ]]\nstep note\n\n[[ ## completed ## ]]"
      Right(LmResponse(outputs = Vector(LmOutput(text = text))))

  /** Records what the adapter gives to the metric for each component. */
  private final class RecordingMetric extends FeedbackMetric:
    val componentCalls: ArrayBuffer[(String, Vector[TraceEntry])] = ArrayBuffer.empty
    override def name: String                                     = "recording"
    override def feedback(
        example       : Example,
        prediction    : RawPrediction,
        trace         : Vector[TraceEntry],
        component     : Option[String],
        componentTrace: Vector[TraceEntry]
    )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
      component.foreach(name => componentCalls.synchronized(componentCalls += (name -> componentTrace)))
      Right(ScoreWithFeedback(1.0, s"feedback for ${component.getOrElse("program")}"))

  private def predict(layout: String, instruction: String): DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse(layout).toOption.get.withInstructions(Some(instruction)))

  private val pipeline = LoopPipeline(
    stepper = predict("question -> note", "Take a note."),
    finisher = predict("question, note -> answer", "Answer with the note.")
  )

  private val stepperKey  = summon[OptimizableLeaf[DynamicPredict]].metadata(pipeline.stepper).key
  private val finisherKey = summon[OptimizableLeaf[DynamicPredict]].metadata(pipeline.finisher).key

  private val dataset: Vector[Example] = Vector(
    Example(
      values = DynamicValues.record("question" := "Capital of France?", "answer" := "Paris"),
      inputKeys = Set("question")
    )
  )

  private def reflect(metric: FeedbackMetric): Map[OptimizableId, Vector[ReflectiveRecord]] =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new LoopLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val adapter          = new GepaAdapter(pipeline, metric)
      val seed             = Candidate.seed(pipeline)
      val batch            = adapter.evaluate(dataset, seed, captureTraces = true)
      adapter
        .makeReflectiveDataset(seed, batch, Vector(OptimizableId(0), OptimizableId(1)))
        .fold(error => fail(error.message), identity)
    }

  test("the run records three predictor entries for two leaves") {
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new LoopLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val adapter          = new GepaAdapter(pipeline, new RecordingMetric)
      val batch            = adapter.evaluate(dataset, Candidate.seed(pipeline), captureTraces = true)
      val keys             = batch.trajectories.toVector.flatten.flatMap(_.trace).flatMap(_.predictor)
      assertEquals(keys, Vector(stepperKey, stepperKey, finisherKey))
    }
  }

  test("the record of the second leaf comes from its own call, not from trace position 1") {
    val finisher = reflect(new RecordingMetric)(OptimizableId(1))
    assertEquals(finisher.size, 1)
    assert(finisher.head.generatedOutputs.contains("Paris"), finisher.head.generatedOutputs)
    assert(!finisher.head.generatedOutputs.contains("step note"), finisher.head.generatedOutputs)
    assert(finisher.head.inputs.contains("step note"), finisher.head.inputs)
  }

  test("a leaf that ran two times in one example gives one record, from one of its calls") {
    val stepper = reflect(new RecordingMetric)(OptimizableId(0))
    assertEquals(stepper.size, 1)
    assert(stepper.head.generatedOutputs.contains("step note"), stepper.head.generatedOutputs)
    assert(!stepper.head.generatedOutputs.contains("Paris"), stepper.head.generatedOutputs)
  }

  test("the metric gets an entry of the same leaf as the component trace") {
    val metric    = new RecordingMetric
    val _         = reflect(metric)
    val byName    = metric.componentCalls.toVector.groupMap(_._1)(_._2)
    val forFinish = byName.getOrElse("finisher", Vector.empty).flatten
    val forStep   = byName.getOrElse("stepper", Vector.empty).flatten
    assert(forFinish.nonEmpty && forStep.nonEmpty, byName.keySet.toString)
    assertEquals(forFinish.map(_.predictor).distinct, Vector(Some(finisherKey)))
    assertEquals(forStep.map(_.predictor).distinct, Vector(Some(stepperKey)))
  }

  test("the trace entries of the metric do not reach the trajectory or the context of the caller") {
    val leakyMetric: FeedbackMetric = new FeedbackMetric:
      override def name: String = "leaky"
      override def feedback(
          example       : Example,
          prediction    : RawPrediction,
          trace         : Vector[TraceEntry],
          component     : Option[String],
          componentTrace: Vector[TraceEntry]
      )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
        // An LM-judged metric runs a predictor, and that predictor appends to the current trace.
        RuntimeEnvironment.appendTrace(TraceEntry("judge", DynamicValues.record(), DynamicValues.record()))
        Right(ScoreWithFeedback(1.0, "ok"))

    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new LoopLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val adapter          = new GepaAdapter(pipeline, leakyMetric)
      val batch            = adapter.evaluate(dataset, Candidate.seed(pipeline), captureTraces = true)
      val components       = batch.trajectories.toVector.flatten.flatMap(_.trace).map(_.component)
      assert(!components.contains("judge"), components.toString)
      assertEquals(RuntimeEnvironment.current.trace.map(_.component), Vector.empty)
    }
  }
