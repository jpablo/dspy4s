package dspy4s.streaming

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.Module
import dspy4s.core.contracts.Prediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SettingKeys
import dspy4s.core.contracts.Signature
import dspy4s.core.contracts.SettingsData
import dspy4s.core.runtime.ContextPropagation
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.programs.ChainOfThought
import dspy4s.programs.Predict
import dspy4s.programs.ReAct
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
      val (signature, predictName) = signatureFor(program)
      val adapter = outerContext.settings.entries
        .get(SettingKeys.adapter.name)
        .collect { case a: Adapter => a }
      val stateFactory: () => Option[AdapterStreamingState] =
        (signature, adapter) match
          case (Some(sig), Some(a)) => () => a.streamingState(sig)
          case _                    => () => None

      val wrappedLm = currentLm.collect { case slm: StreamingLanguageModel =>
        StreamingLanguageModelWrapper(
          delegate = slm,
          queue = queue,
          predictName = predictName,
          stateFactory = stateFactory,
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

  /** Best-effort extraction of the signature and predict name from a program.
    *
    * Pattern-matches the concrete program types dspy4s knows about today.
    * Each of [[Predict]], [[ChainOfThought]], and [[ReAct]] wraps a single
    * inner signature — they don't compose distinct predictors with different
    * signatures (the way some Python DSPy programs do). When that becomes a
    * thing, this resolver will need to grow into a per-LM-call routing
    * mechanism.
    *
    * The returned `predictName` is the outermost program's `moduleName`, so
    * listeners filter against the user-visible program (`"chain_of_thought"`,
    * `"react"`, `"predict"`), not the inner Predict.
    */
  private def signatureFor(
      program: Module[ProgramCall, Prediction]
  ): (Option[Signature], String) =
    program match
      case predict: Predict       => (Some(predict.signature), predict.moduleName)
      case cot: ChainOfThought    => (cot.signature.toOption, cot.moduleName)
      case react: ReAct           =>
        val (sig, _) = signatureFor(react.module)
        (sig, react.moduleName)
      case _                      => (None, "")
