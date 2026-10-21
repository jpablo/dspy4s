package dspy4s.core.runtime

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.CompileEndEvent
import dspy4s.core.contracts.CompileStartEvent
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.EvaluateEndEvent
import dspy4s.core.contracts.EvaluateStartEvent
import dspy4s.core.contracts.InterpreterEndEvent
import dspy4s.core.contracts.InterpreterOperation
import dspy4s.core.contracts.InterpreterStartEvent
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ValidationError
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer

/** The evaluate, compile, and interpreter scopes of the dispatcher (upstream `on_evaluate_*`, `on_compile_*`, and
  * `on_interpreter_*` in `dspy/utils/callback.py`; PORT_GAPS G-40, upgrade item I9).
  */
class LifecycleCallbackSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private final class Recorder extends CallbackHandler:
    val events: ArrayBuffer[CallbackEvent]                                 = ArrayBuffer.empty
    override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = events += event

  private def recorded(body: => Unit): Vector[CallbackEvent] =
    val recorder = Recorder()
    RuntimeEnvironment.withCallbacks(Vector(recorder))(body)
    recorder.events.toVector

  private val inputs = DynamicValues.record("devset_size" := 3, "num_threads" := 1)

  test("an evaluate scope emits a start and an end with one call id") {
    val events = recorded {
      val _ = CallbackDispatcher.withEvaluate("Evaluate", inputs)(Right(42): Either[DspyError, Int])
    }
    events match
      case Vector(start: EvaluateStartEvent, end: EvaluateEndEvent) =>
        assertEquals(start.evaluatorName, "Evaluate")
        assertEquals(start.inputs, inputs)
        assert(start.callId.startsWith("evaluate-"), start.callId)
        assertEquals(end.callId, start.callId)
        assertEquals(end.output, Right(42))
      case other => fail(s"unexpected events: $other")
  }

  test("a compile scope of the same optimizer inside its own scope opens no second scope") {
    val optimizer = new Object
    val events    = recorded {
      val _ = CallbackDispatcher.withCompile(optimizer, "Outer", DynamicValues.record("trainset_size" := 2)) {
        CallbackDispatcher.withCompile(optimizer, "Outer", DynamicValues.record("trainset_size" := 2))(Right("inner"))
      }
    }
    assertEquals(events.map(_.getClass.getSimpleName), Vector("CompileStartEvent", "CompileEndEvent"))
  }

  test("a compile scope of another optimizer nests, and a later compile of the same optimizer opens a new scope") {
    val outer  = new Object
    val inner  = new Object
    val events = recorded {
      val _ = CallbackDispatcher.withCompile(outer, "Outer", DynamicValues.record()) {
        CallbackDispatcher.withCompile(inner, "Inner", DynamicValues.record())(Right("inner"))
      }
      val _ = CallbackDispatcher.withCompile(outer, "Outer", DynamicValues.record())(Right("again"))
    }
    val starts = events.collect { case start: CompileStartEvent => start }
    assertEquals(starts.map(_.optimizerName), Vector("Outer", "Inner", "Outer"))
    assertEquals(starts(1).parentCallId, Some(starts(0).callId))
    assertEquals(starts(2).parentCallId, None)
    assert(starts.forall(_.callId.startsWith("compile-")), starts.toString)
    val ends = events.collect { case end: CompileEndEvent => end }
    assertEquals(ends.map(_.callId), Vector(starts(1).callId, starts(0).callId, starts(2).callId))
  }

  test("a compile scope reports a failure and keeps the outermost rule after it") {
    val optimizer = new Object
    val events    = recorded {
      val _ = CallbackDispatcher.withCompile(optimizer, "Failing", DynamicValues.record())(
        Left(ValidationError("no data")): Either[DspyError, Int]
      )
      val _ = CallbackDispatcher.withCompile(optimizer, "Failing", DynamicValues.record())(Right(1))
    }
    val ends = events.collect { case end: CompileEndEvent => end.output }
    assertEquals(ends, Vector(Left(ValidationError("no data")), Right(1)))
  }

  test("an interpreter scope carries its operation, and a scope inside it has it as parent") {
    val events = recorded {
      val _ = CallbackDispatcher.withInterpreter(
        "TestInterpreter",
        InterpreterOperation.Execute,
        DynamicValues.record("code" := "print(1)")
      ) {
        CallbackDispatcher.withInterpreter("TestInterpreter", InterpreterOperation.Startup, DynamicValues.record())(
          Right(())
        )
      }
    }
    val starts = events.collect { case start: InterpreterStartEvent => start }
    assertEquals(starts.map(_.operation), Vector(InterpreterOperation.Execute, InterpreterOperation.Startup))
    assertEquals(starts(1).parentCallId, Some(starts(0).callId))
    assert(starts.forall(_.callId.startsWith("interpreter-")), starts.toString)
    val ends = events.collect { case end: InterpreterEndEvent => end }
    assertEquals(ends.map(_.operation), Vector(InterpreterOperation.Startup, InterpreterOperation.Execute))
    assertEquals(ends.map(_.interpreterName).distinct, Vector("TestInterpreter"))
  }
