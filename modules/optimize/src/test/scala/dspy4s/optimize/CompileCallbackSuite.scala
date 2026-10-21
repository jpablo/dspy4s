package dspy4s.optimize

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.CompileEndEvent
import dspy4s.core.contracts.CompileStartEvent
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ErrorLimit
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.data.Example
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.evaluate.metrics.ExactMatch
import dspy4s.programs.strategies.DynamicPredict
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer

/** The `compile` of an optimizer emits a compile scope (upstream `on_compile_start` and `on_compile_end`, and
  * `_with_compile_callbacks` in `dspy/teleprompt/teleprompt.py`).
  */
class CompileCallbackSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private final class Recorder extends CallbackHandler:
    val events: ArrayBuffer[CallbackEvent]                                 = ArrayBuffer.empty
    override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = events += event

  private val signature = SignatureDsl.parse("question: str -> answer: str").toOption.get

  private val trainset = (1 to 4).map(i =>
    Example(rec("question" := s"q$i", "answer" := s"a$i"), inputKeys = Set("question"))
  ).toVector

  private def recorded[A](body: RuntimeContext ?=> A): (A, Vector[CallbackEvent]) =
    val recorder = Recorder()
    val result   = RuntimeEnvironment.withCallbacks(Vector(recorder)) {
      body(using RuntimeEnvironment.current)
    }
    (result, recorder.events.toVector)

  test("LabeledFewShot.compile emits one compile scope with the size of the trainset and the result") {
    val optimizer        = LabeledFewShot[DynamicPredict](LabeledFewShotConfig(k = DemoCount(2), seed = 1L))
    val (result, events) = recorded(optimizer.compile(DynamicPredict(layout = signature), trainset))
    assert(result.isRight, result.toString)
    val starts = events.collect { case start: CompileStartEvent => start }
    val ends   = events.collect { case end: CompileEndEvent => end }
    assertEquals(starts.map(_.optimizerName), Vector("LabeledFewShot"))
    assertEquals(DynamicValues.recordGet(starts.head.inputs, "trainset_size"), Some(DynamicValues.fromAny(4)))
    assertEquals(ends.map(_.callId), starts.map(_.callId))
    assertEquals(ends.head.output.isRight, true)
  }

  // Upstream emits a LabeledFewShot scope for the labeled candidate of the random search (random_search.py:88), and
  // one inside each BootstrapFewShot scope for a teacher with no demos (bootstrap.py:106).
  test("the BootstrapFewShot and LabeledFewShot runs of a random search are compile scopes inside its scope") {
    val optimizer = new BootstrapFewShotWithRandomSearch[DemoAwarePredictProgram](
      RandomSearchConfig(
        metric = new ExactMatch(answerField = "answer"),
        numCandidates = SearchCandidateCount(2),
        maxBootstrappedDemos = DemoCount(1),
        maxLabeledDemos = DemoCount(1),
        maxErrors = ErrorLimit(20)
      )
    )
    val teacher          = DemoAwarePredictProgram(layout = signature, answers = (1 to 4).map(i => s"q$i" -> s"a$i").toMap)
    val (result, events) =
      recorded(optimizer.compile(DemoAwarePredictProgram(signature), trainset, teacher = Some(teacher)))
    assert(result.isRight, result.toString)
    val starts = events.collect { case start: CompileStartEvent => start }
    assertEquals(starts.head.optimizerName, "BootstrapFewShotWithRandomSearch")
    assertEquals(starts.head.parentCallId, None)
    val inner = starts.tail
    assert(inner.forall(s => Set("BootstrapFewShot", "LabeledFewShot").contains(s.optimizerName)), inner.toString)
    val bootstrap = inner.filter(_.optimizerName == "BootstrapFewShot")
    assert(bootstrap.nonEmpty, s"the random search must run BootstrapFewShot: $starts")
    assert(bootstrap.forall(_.parentCallId.contains(starts.head.callId)), bootstrap.toString)
    val labeledParents = (starts.head +: bootstrap).map(_.callId).toSet
    val labeled        = inner.filter(_.optimizerName == "LabeledFewShot")
    assert(labeled.forall(_.parentCallId.exists(labeledParents.contains)), labeled.toString)
    val ends = events.collect { case end: CompileEndEvent => end }
    assertEquals(ends.map(_.callId).sorted, starts.map(_.callId).sorted)
    assertEquals(ends.last.callId, starts.head.callId)
  }
