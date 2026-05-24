package dspy4s.streaming

import dspy4s.adapters.contracts.Adapter
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

  /** Wrap a program in a streaming envelope: each invocation of the returned
    * function runs the program on a daemon producer thread and yields a
    * [[ClosableIterator]] of [[StreamEvent]]s.
    *
    * Per-LM-call signature routing happens inside the wrapped
    * [[StreamingLanguageModel]]: it consults [[dspy4s.core.runtime.ActivePredictContext]]
    * at the start of each `call()` to pick the active predictor's signature
    * and name, then builds a fresh adapter state machine for that signature.
    * As a result, programs that internally invoke multiple `Predict`s with
    * different signatures stream per-field tokens correctly under each
    * predictor's own framing.
    */
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
      val adapter = outerContext.settings.entries
        .get(SettingKeys.adapter.name)
        .collect { case a: Adapter => a }

      val wrappedLm = currentLm.collect { case slm: StreamingLanguageModel =>
        StreamingLanguageModelWrapper(
          delegate = slm,
          queue = queue,
          adapter = adapter,
          listeners = streamListeners
        )
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
