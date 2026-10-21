package dspy4s.evaluate

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.EvaluateEndEvent
import dspy4s.core.contracts.EvaluateStartEvent
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.data.Example
import dspy4s.core.data.RawPrediction
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.evaluate.contracts.EvaluationResult
import dspy4s.evaluate.metrics.ExactMatch
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer

/** One run of `Evaluate` emits an evaluate scope (upstream `on_evaluate_start` and `on_evaluate_end`). */
class EvaluateCallbackSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private final class Recorder extends CallbackHandler:
    val events: ArrayBuffer[CallbackEvent]                                 = ArrayBuffer.empty
    override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = events += event

  test("a run of Evaluate emits one evaluate scope with the size of the dev set and the result") {
    val devset =
      Vector(Example("question" := "q1", "answer" := "Paris"), Example("question" := "q2", "answer" := "Rome"))
    val evaluator = Evaluate(devset = devset, metric = new ExactMatch())
    val recorder  = Recorder()
    val result    = RuntimeEnvironment.withCallbacks(Vector(recorder)) {
      given RuntimeContext = RuntimeEnvironment.current
      evaluator()((_: Example) => Right(RawPrediction(DynamicValues.record("answer" := "Paris"))))
    }
    assert(result.isRight, result.toString)
    val starts = recorder.events.collect { case start: EvaluateStartEvent => start }.toVector
    val ends   = recorder.events.collect { case end: EvaluateEndEvent => end }.toVector
    assertEquals(starts.size, 1)
    assertEquals(ends.map(_.callId), starts.map(_.callId))
    assertEquals(starts.head.evaluatorName, "Evaluate")
    assertEquals(DynamicValues.recordGet(starts.head.inputs, "devset_size"), Some(DynamicValues.fromAny(2)))
    assert(DynamicValues.recordGet(starts.head.inputs, "num_threads").isDefined, starts.head.inputs.toString)
    ends.head.output match
      case Right(evaluation: EvaluationResult) => assertEquals(evaluation.results.size, 2)
      case other                               => fail(s"the end event has no result: $other")
  }
