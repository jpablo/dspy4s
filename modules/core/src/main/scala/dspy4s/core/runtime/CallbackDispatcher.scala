package dspy4s.core.runtime

import dspy4s.core.contracts.AdapterEndEvent
import dspy4s.core.contracts.AdapterStartEvent
import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.LmEndEvent
import dspy4s.core.contracts.LmStartEvent
import dspy4s.core.contracts.ModuleEndEvent
import dspy4s.core.contracts.ModuleStartEvent
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.ToolEndEvent
import dspy4s.core.contracts.ToolStartEvent

import scala.util.control.NonFatal

/** Opens and closes callback scopes around the units of work a program runs -- module executions, LM calls,
  * adapter format/parse passes, and tool invocations. Each `with*` helper emits a `*StartEvent` before the
  * wrapped thunk and the matching `*EndEvent` after, correlated by a shared `callId`, so handlers observe a
  * properly nested call tree (see [[dspy4s.core.contracts.CallbackEvent]] for the correlation model).
  *
  * Two guarantees hold for every scope:
  *
  *   - The end event ALWAYS fires -- on success, on a `Left(DspyError)`, and even when the thunk throws. A
  *     thrown exception is reported as a `Left(RuntimeError("callback_dispatch", ...))` end event and then
  *     rethrown, so observability never swallows a failure.
  *   - The scope's `callId` is installed as the active call for the duration of the thunk, so any scope opened
  *     inside it inherits that id as its `parentCallId`.
  *
  * Events reach the registered handlers through [[RuntimeEnvironment.emit]].
  */
object CallbackDispatcher:

  /** Deliver one event to every callback handler registered on the active runtime context. */
  def emit(event: CallbackEvent): Unit =
    RuntimeEnvironment.emit(event)

  /** Wrap a module execution in a `ModuleStartEvent` / `ModuleEndEvent` pair. */
  def withModule[A](moduleName: String, inputs: Map[String, Any])(thunk: => Either[DspyError, A]): Either[DspyError, A] =
    withCallScope(prefix = "module") { (callId, parentCallId) =>
      emit(
        ModuleStartEvent(
          moduleName = moduleName,
          inputs = inputs,
          callId = callId,
          parentCallId = parentCallId
        )
      )
      runWithEnd(thunk) { output =>
        emit(
          ModuleEndEvent(
            moduleName = moduleName,
            output = output,
            callId = callId,
            parentCallId = parentCallId
          )
        )
      }
    }

  /** Wrap a language-model call in an `LmStartEvent` / `LmEndEvent` pair. */
  def withLm[A](modelId: String, request: Map[String, Any])(thunk: => Either[DspyError, A]): Either[DspyError, A] =
    withCallScope(prefix = "lm") { (callId, parentCallId) =>
      emit(
        LmStartEvent(
          modelId = modelId,
          request = request,
          callId = callId,
          parentCallId = parentCallId
        )
      )
      runWithEnd(thunk) { output =>
        emit(
          LmEndEvent(
            modelId = modelId,
            response = output,
            callId = callId,
            parentCallId = parentCallId
          )
        )
      }
    }

  /** Wrap an adapter pass (format or parse) in an `AdapterStartEvent` / `AdapterEndEvent` pair. */
  def withAdapter[A](
      adapterName: String,
      inputs: Map[String, Any]
  )(thunk: => Either[DspyError, A]): Either[DspyError, A] =
    withCallScope(prefix = "adapter") { (callId, parentCallId) =>
      emit(
        AdapterStartEvent(
          adapterName = adapterName,
          inputs = inputs,
          callId = callId,
          parentCallId = parentCallId
        )
      )
      runWithEnd(thunk) { output =>
        emit(
          AdapterEndEvent(
            adapterName = adapterName,
            output = output,
            callId = callId,
            parentCallId = parentCallId
          )
        )
      }
    }

  /** Wrap a tool invocation in a `ToolStartEvent` / `ToolEndEvent` pair. */
  def withTool[A](
      toolName: String,
      args: Map[String, Any]
  )(thunk: => Either[DspyError, A]): Either[DspyError, A] =
    withCallScope(prefix = "tool") { (callId, parentCallId) =>
      emit(
        ToolStartEvent(
          toolName = toolName,
          args = args,
          callId = callId,
          parentCallId = parentCallId
        )
      )
      runWithEnd(thunk) { output =>
        emit(
          ToolEndEvent(
            toolName = toolName,
            output = output,
            callId = callId,
            parentCallId = parentCallId
          )
        )
      }
    }

  /** Allocate a fresh `prefix`-tagged `callId`, capture the enclosing scope's id as the parent, and run `thunk`
    * with the new id installed as the active call so any nested scope nests under it. The thunk receives
    * `(callId, parentCallId)` to stamp onto its start/end events. */
  private def withCallScope[A](
      prefix: String
  )(thunk: (String, Option[String]) => Either[DspyError, A]): Either[DspyError, A] =
    val parentCallId = RuntimeEnvironment.activeCallId
    val callId = RuntimeEnvironment.nextCallId(prefix)
    RuntimeEnvironment.withActiveCall(callId) {
      thunk(callId, parentCallId)
    }

  /** Run `thunk` and ensure `emitEnd` fires exactly once with the outcome before returning: the thunk's own
    * `Right`/`Left`, or -- if it throws a non-fatal exception -- a `Left(RuntimeError("callback_dispatch", ...))`,
    * after which the original exception is rethrown so it still propagates to the caller. */
  private def runWithEnd[A](
      thunk: => Either[DspyError, A]
  )(emitEnd: Either[DspyError, Any] => Unit): Either[DspyError, A] =
    try
      val result = thunk
      val lifted: Either[DspyError, Any] = result match
        case Left(error)  => Left(error)
        case Right(value) => Right(value)
      emitEnd(lifted)
      result
    catch
      case NonFatal(error) =>
        val runtimeError: Either[DspyError, Any] = Left(
          RuntimeError("callback_dispatch", Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
        )
        emitEnd(runtimeError)
        throw error
