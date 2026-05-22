package dspy4s.streaming

import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.Module
import dspy4s.core.contracts.Prediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SettingKeys
import dspy4s.core.contracts.SettingsData
import dspy4s.core.runtime.ContextPropagation
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.programs.contracts.ProgramCall
import dspy4s.streaming.contracts.ErrorEvent
import dspy4s.streaming.contracts.PredictionEvent
import dspy4s.streaming.contracts.StreamEvent
import dspy4s.streaming.contracts.StreamListener

import scala.util.control.NonFatal

object Streamify:

  def streamify(
      program: Module[ProgramCall, Prediction],
      statusMessageProvider: Option[StatusMessageProvider] = None,
      streamListeners: Vector[StreamListener] = Vector.empty,
      includeFinalPrediction: Boolean = true,
      queueCapacity: Int = 64
  )(using outerContext: RuntimeContext): Map[String, Any] => ClosableIterator[StreamEvent] =
    inputs =>
      val queue = StreamingQueue[StreamEvent](queueCapacity)
      val captured = ContextPropagation.capture

      val provider = statusMessageProvider.getOrElse(StatusMessageProvider.default)
      val callback = new StatusStreamingCallback(provider, queue)

      val currentLm = outerContext.settings.entries.get(SettingKeys.languageModel.name)
      val wrappedLm = currentLm.collect { case slm: StreamingLanguageModel =>
        StreamingLanguageModelWrapper(slm, queue)
      }

      val extraSettings: Map[String, Any] = wrappedLm match
        case Some(wrapper) => Map(SettingKeys.languageModel.name -> wrapper)
        case None          => Map.empty

      val producer = new Thread(
        new Runnable:
          override def run(): Unit =
            ContextPropagation.inContext(captured) {
              RuntimeEnvironment.withSettings(SettingsData(extraSettings)) {
                val existingCallbacks = RuntimeEnvironment.current.settings
                  .get(SettingKeys.callbacks)
                  .getOrElse(Vector.empty)
                RuntimeEnvironment.withCallbacks(existingCallbacks :+ callback) {
                  given RuntimeContext = RuntimeEnvironment.current
                  try
                    val call = ProgramCall(inputs = inputs)
                    program.run(call) match
                      case Right(prediction) =>
                        if includeFinalPrediction then queue.offer(PredictionEvent(prediction))
                      case Left(error) =>
                        queue.offer(ErrorEvent(error))
                  catch
                    case NonFatal(error) =>
                      val runtimeError = dspy4s.core.contracts.RuntimeError(
                        "streamify",
                        Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
                      )
                      queue.offer(ErrorEvent(runtimeError))
                }
              }
            }
            queue.close()
      )
      producer.setDaemon(true)
      producer.setName("dspy4s-streamify-producer")
      producer.start()

      queue.asIterator
