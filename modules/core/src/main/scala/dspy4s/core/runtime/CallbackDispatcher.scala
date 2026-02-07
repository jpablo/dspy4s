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

object CallbackDispatcher:
  def emit(event: CallbackEvent): Unit =
    RuntimeEnvironment.emit(event)

  def withModule[A](moduleName: String, inputs: Map[String, Any])(thunk: => Either[DspyError, A]): Either[DspyError, A] =
    withCallScope("module", prefix = "module") { (callId, parentCallId) =>
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

  def withLm[A](modelId: String, request: Map[String, Any])(thunk: => Either[DspyError, A]): Either[DspyError, A] =
    withCallScope("lm", prefix = "lm") { (callId, parentCallId) =>
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

  def withAdapter[A](
      adapterName: String,
      inputs: Map[String, Any]
  )(thunk: => Either[DspyError, A]): Either[DspyError, A] =
    withCallScope("adapter", prefix = "adapter") { (callId, parentCallId) =>
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

  def withTool[A](
      toolName: String,
      args: Map[String, Any]
  )(thunk: => Either[DspyError, A]): Either[DspyError, A] =
    withCallScope("tool", prefix = "tool") { (callId, parentCallId) =>
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

  private def withCallScope[A](
      _label: String,
      prefix: String
  )(thunk: (String, Option[String]) => Either[DspyError, A]): Either[DspyError, A] =
    val parentCallId = RuntimeEnvironment.activeCallId
    val callId = RuntimeEnvironment.nextCallId(prefix)
    RuntimeEnvironment.withActiveCall(callId) {
      thunk(callId, parentCallId)
    }

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
