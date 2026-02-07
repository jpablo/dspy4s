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

import scala.util.control.NonFatal

object CallbackDispatcher:
  def emit(event: CallbackEvent): Unit =
    RuntimeEnvironment.emit(event)

  def withModule[A](moduleName: String, inputs: Map[String, Any])(thunk: => Either[DspyError, A]): Either[DspyError, A] =
    emit(ModuleStartEvent(moduleName = moduleName, inputs = inputs))
    runWithEnd(thunk) { output =>
      emit(ModuleEndEvent(moduleName = moduleName, output = output))
    }

  def withLm[A](modelId: String, request: Map[String, Any])(thunk: => Either[DspyError, A]): Either[DspyError, A] =
    emit(LmStartEvent(modelId = modelId, request = request))
    runWithEnd(thunk) { output =>
      emit(LmEndEvent(modelId = modelId, response = output))
    }

  def withAdapter[A](
      adapterName: String,
      inputs: Map[String, Any]
  )(thunk: => Either[DspyError, A]): Either[DspyError, A] =
    emit(AdapterStartEvent(adapterName = adapterName, inputs = inputs))
    runWithEnd(thunk) { output =>
      emit(AdapterEndEvent(adapterName = adapterName, output = output))
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
