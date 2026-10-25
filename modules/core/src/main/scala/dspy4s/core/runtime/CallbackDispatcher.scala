package dspy4s.core.runtime

import dspy4s.core.contracts.AdapterEndEvent
import dspy4s.core.contracts.AdapterStartEvent
import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CompileEndEvent
import dspy4s.core.contracts.CompileStartEvent
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.EvaluateEndEvent
import dspy4s.core.contracts.EvaluateStartEvent
import dspy4s.core.contracts.InterpreterEndEvent
import dspy4s.core.contracts.InterpreterOperation
import dspy4s.core.contracts.InterpreterStartEvent
import dspy4s.core.contracts.LmEndEvent
import dspy4s.core.contracts.LmStartEvent
import dspy4s.core.contracts.ModuleEndEvent
import dspy4s.core.contracts.ModuleStartEvent
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.ToolEndEvent
import dspy4s.core.contracts.ToolStartEvent
import zio.blocks.schema.DynamicValue

import scala.util.control.NonFatal

/** Opens and closes callback scopes around the units of work a program runs: module executions, LM calls, adapter
  * format/parse passes, tool invocations, evaluation runs, optimizer compiles, and code-interpreter operations. Each
  * `with*` helper emits a `*StartEvent` before the wrapped thunk and the matching `*EndEvent` after, correlated by a
  * shared `callId`, so handlers observe a properly nested call tree (see [[dspy4s.core.contracts.CallbackEvent]] for
  * the correlation model).
  *
  * Two guarantees hold for every scope whose start event was emitted successfully:
  *
  *   - The dispatcher attempts exactly one end emission: on success, on a `Left(DspyError)`, and even when the thunk
  *     throws. A thrown body exception is reported as a `Left(RuntimeError("callback_dispatch", ...))` end event and
  *     then rethrown. If an end observer itself throws, that end is not emitted a second time.
  *   - The scope's `callId` is installed as the active call for the duration of the thunk, so any scope opened inside
  *     it inherits that id as its `parentCallId`.
  *
  * Events reach the registered handlers through [[RuntimeEnvironment.emit]].
  */
object CallbackDispatcher:

  /** Wrap a module execution in a `ModuleStartEvent` / `ModuleEndEvent` pair. */
  def withModule[A](
      moduleName: String,
      inputs    : DynamicValue.Record
  )(thunk: => Either[DspyError, A]): Either[DspyError, A] =
    withEvents[A](prefix = "module")(
      start = (callId, parentCallId) => ModuleStartEvent(moduleName, inputs, callId, parentCallId),
      end = (output, callId, parentCallId) => ModuleEndEvent(moduleName, output, callId, parentCallId)
    )(thunk)

  /** Wrap a language-model call in an `LmStartEvent` / `LmEndEvent` pair. */
  def withLm[A](modelId: String, request: DynamicValue.Record)(thunk: => Either[DspyError, A]): Either[DspyError, A] =
    withEvents[A](prefix = "lm")(
      start = (callId, parentCallId) => LmStartEvent(modelId, request, callId, parentCallId),
      end = (output, callId, parentCallId) => LmEndEvent(modelId, output, callId, parentCallId)
    )(thunk)

  /** Wrap an adapter pass (format or parse) in an `AdapterStartEvent` / `AdapterEndEvent` pair. */
  def withAdapter[A](
      adapterName: String,
      inputs     : DynamicValue.Record
  )(thunk: => Either[DspyError, A]): Either[DspyError, A] =
    withEvents[A](prefix = "adapter")(
      start = (callId, parentCallId) => AdapterStartEvent(adapterName, inputs, callId, parentCallId),
      end = (output, callId, parentCallId) => AdapterEndEvent(adapterName, output, callId, parentCallId)
    )(thunk)

  /** Wrap a tool invocation in a `ToolStartEvent` / `ToolEndEvent` pair. Fixed to `DynamicValue` (not generic like the
    * other scopes) because tool args and results stay in the interchange format (`DynamicValue`) end to end.
    */
  def withTool(
      toolName: String,
      args    : DynamicValue.Record
  )(thunk: => Either[DspyError, DynamicValue]): Either[DspyError, DynamicValue] =
    withEvents[DynamicValue](prefix = "tool")(
      start = (callId, parentCallId) => ToolStartEvent(toolName, args, callId, parentCallId),
      end = (output, callId, parentCallId) => ToolEndEvent(toolName, output, callId, parentCallId)
    )(thunk)

  /** Wrap one evaluation run in an `EvaluateStartEvent` / `EvaluateEndEvent` pair (call ids `evaluate-N`). */
  def withEvaluate[A](
      evaluatorName: String,
      inputs       : DynamicValue.Record
  )(thunk: => Either[DspyError, A]): Either[DspyError, A] =
    withEvents[A](prefix = "evaluate")(
      start = (callId, parentCallId) => EvaluateStartEvent(evaluatorName, inputs, callId, parentCallId),
      end = (output, callId, parentCallId) => EvaluateEndEvent(evaluatorName, output, callId, parentCallId)
    )(thunk)

  /** Wrap one `compile` call of `optimizer` in a `CompileStartEvent` / `CompileEndEvent` pair (call ids `compile-N`).
    * When the thread already runs a `compile` scope of the same `optimizer` instance (by reference), the thunk runs
    * with no new scope.
    */
  def withCompile[A](
      optimizer    : AnyRef,
      optimizerName: String,
      inputs       : DynamicValue.Record
  )(thunk: => Either[DspyError, A]): Either[DspyError, A] =
    val active = activeCompiles.get
    if active.exists(_ eq optimizer) then thunk
    else
      activeCompiles.within(optimizer :: active) {
        withEvents[A](prefix = "compile")(
          start = (callId, parentCallId) => CompileStartEvent(optimizerName, inputs, callId, parentCallId),
          end = (output, callId, parentCallId) => CompileEndEvent(optimizerName, output, callId, parentCallId)
        )(thunk)
      }

  /** Wrap one interpreter operation in an `InterpreterStartEvent` / `InterpreterEndEvent` pair (call ids
    * `interpreter-N`).
    */
  def withInterpreter[A](
      interpreterName: String,
      operation      : InterpreterOperation,
      inputs         : DynamicValue.Record
  )(thunk: => Either[DspyError, A]): Either[DspyError, A] =
    withEvents[A](prefix = "interpreter")(
      start = (callId, parentCallId) => InterpreterStartEvent(interpreterName, operation, inputs, callId, parentCallId),
      end = (output, callId, parentCallId) =>
        InterpreterEndEvent(interpreterName, operation, output, callId, parentCallId)
    )(thunk)

  /** The optimizer instances that have an open `compile` scope on this thread, innermost first (upstream
    * `_ACTIVE_COMPILES`). [[withCompile]] compares them by reference and restores the list after each scope. A new
    * thread starts with no active instances, so a `compile` on another thread opens its own scope (the upstream key
    * holds the thread id).
    */
  private val activeCompiles = ScopedLocal[List[AnyRef]](Nil, travels = false)

  /** The shared scope discipline behind every `with*` helper: allocate a fresh `prefix`-tagged `callId`, capture the
    * enclosing scope's id as the parent, install the new id as the active call for the duration of `thunk` (so nested
    * scopes inherit it as their `parentCallId`), emit `start` before the thunk, and attempt `end` exactly once with its
    * outcome (see [[runWithEnd]]).
    */
  private def withEvents[A](prefix: String)(
      start: (String, Option[String]) => CallbackEvent,
      end  : (Either[DspyError, A], String, Option[String]) => CallbackEvent
  )(thunk: => Either[DspyError, A]): Either[DspyError, A] =
    val parentCallId = RuntimeEnvironment.activeCallId
    val callId       = RuntimeEnvironment.nextCallId(prefix)
    RuntimeEnvironment.withActiveCall(callId) {
      RuntimeEnvironment.emit(start(callId, parentCallId))
      runWithEnd(thunk)(output => RuntimeEnvironment.emit(end(output, callId, parentCallId)))
    }

  /** Run `thunk` and attempt `emitEnd` exactly once with its outcome. If the body throws, an end carrying a
    * callback-dispatch error is attempted before the original exception is rethrown. An observer failure while
    * reporting that body exception is attached as suppressed; an observer failure after an ordinary result propagates
    * directly.
    *
    * An `InterruptedException` is reported the same way, although `NonFatal` rejects it: the parallel executor
    * interrupts its workers, and each start event needs its end event. The other throwables that `NonFatal` rejects
    * (`VirtualMachineError`, `LinkageError`, `ControlThrowable`) still pass through without an end event. A
    * `scala.util.boundary.Break` is a `RuntimeException`, so `NonFatal` accepts it and it still gets an end event.
    */
  private def runWithEnd[A](
      thunk: => Either[DspyError, A]
  )(emitEnd: Either[DspyError, A] => Unit): Either[DspyError, A] =
    val result =
      try thunk
      catch
        case bodyError @ (NonFatal(_) | _: InterruptedException) =>
          val runtimeError: Either[DspyError, A] = Left(
            RuntimeError("callback_dispatch", DspyError.messageOf(bodyError))
          )
          try emitEnd(runtimeError)
          catch
            case NonFatal(observerError) => bodyError.addSuppressed(observerError)
          throw bodyError
    emitEnd(result)
    result
