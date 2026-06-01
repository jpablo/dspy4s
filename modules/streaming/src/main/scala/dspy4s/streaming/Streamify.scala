package dspy4s.streaming

import dspy4s.adapters.contracts.Adapter
import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.Module
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.runtime.ContextPropagation
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.programs.DynamicPredict
import dspy4s.programs.ReAct
import dspy4s.programs.contracts.ProgramCall
import dspy4s.streaming.contracts.ErrorEvent
import dspy4s.streaming.contracts.PredictionEvent
import dspy4s.streaming.contracts.StreamEvent
import dspy4s.streaming.contracts.StreamListener
import zio.blocks.schema.DynamicValue

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
    * As a result, programs that internally invoke multiple `DynamicPredict`s with
    * different signatures stream per-field tokens correctly under each
    * predictor's own framing.
    */
  def streamify(
      program: Module[ProgramCall, DynamicPrediction],
      statusMessageProvider: Option[StatusMessageProvider] = None,
      streamListeners: Vector[StreamListener] = Vector.empty,
      includeFinalPrediction: Boolean = true,
      queueCapacity: Int = 64,
      warningSink: String => Unit = msg => System.err.println(s"[dspy4s.streamify] $msg")
  )(using outerContext: RuntimeContext): DynamicValue.Record => ClosableIterator[StreamEvent] =
    // Validate listener field names against the program structure as far as
    // we can statically see it. This is dspy4s's equivalent of Python's
    // `find_predictor_for_stream_listeners`: warn (don't fail) if a listener
    // is subscribed to a field name that no DynamicPredict in the program emits.
    // For composite programs whose internals we can't introspect (a user-
    // defined `PredictProgram`), validation is silently skipped.
    validateListeners(program, streamListeners, warningSink)

    inputs =>
      val queue = StreamingQueue[StreamEvent](queueCapacity)
      val captured = ContextPropagation.capture

      val provider = statusMessageProvider.getOrElse(StatusMessageProvider.default)
      val callback = new StatusStreamingCallback(provider, queue)

      val currentLm = outerContext.lm
      val adapter = outerContext.adapter.collect { case a: Adapter => a }

      val wrappedLm = currentLm.collect { case slm: StreamingLanguageModel =>
        StreamingLanguageModelWrapper(
          delegate = slm,
          queue = queue,
          adapter = adapter,
          listeners = streamListeners
        )
      }

      val lmOverride: RuntimeContext = wrappedLm match
        case Some(wrapper) => RuntimeContext(lm = Some(wrapper))
        case None          => RuntimeContext()

      val producer = new Thread(
        new Runnable:
          override def run(): Unit =
            val _ = ContextPropagation.inContext(captured) {
              RuntimeEnvironment.withSettings(lmOverride) {
                val existingCallbacks = RuntimeEnvironment.current.callbacks
                RuntimeEnvironment.withCallbacks(existingCallbacks :+ callback) {
                  given RuntimeContext = RuntimeEnvironment.current
                  try
                    val call = ProgramCall(inputs = inputs)
                    program.run(call) match
                      case Right(prediction) =>
                        if includeFinalPrediction then { val _ = queue.offer(PredictionEvent(prediction)) }
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

  /** Walk the program tree, collect every DynamicPredict-derived signature we can
    * statically see, and warn for each listener whose `signatureFieldName`
    * does not appear in any of them. Composite programs that aren't one of
    * the well-known shapes contribute nothing — for those, validation is
    * skipped (matches Python's behavior of "look as far as you can"). */
  private def validateListeners(
      program: Module[?, ?],
      listeners: Vector[StreamListener],
      warningSink: String => Unit
  ): Unit =
    if listeners.isEmpty then return
    val knownSignatures = collectKnownSignatures(program)
    if knownSignatures.isEmpty then return // opaque program; skip
    val knownFields: Set[String] = knownSignatures.flatMap(_._2.outputFields.map(_.name)).toSet
    val knownPredictNames: Set[String] = knownSignatures.map(_._1).toSet
    listeners.foreach { listener =>
      if !knownFields.contains(listener.signatureFieldName) then
        warningSink(
          s"StreamListener(signatureFieldName=${listener.signatureFieldName}" +
            listener.predictName.fold("")(n => s", predictName=$n") +
            s") will never fire: no DynamicPredict in the program emits that output field. " +
            s"Known output fields: ${knownFields.toSeq.sorted.mkString(", ")}."
        )
      else
        listener.predictName.foreach { name =>
          if !knownPredictNames.contains(name) then
            warningSink(
              s"StreamListener(signatureFieldName=${listener.signatureFieldName}, " +
                s"predictName=$name) will never fire: no DynamicPredict in the program is named '$name'. " +
                s"Known predict names: ${knownPredictNames.toSeq.sorted.mkString(", ")}."
            )
        }
    }

  /** Best-effort recursive collection of `(predictName, signature)` from
    * the well-known program types. Unknown composite types contribute
    * nothing. */
  private def collectKnownSignatures(program: Module[?, ?]): Vector[(String, SignatureLayout)] =
    program match
      case p: DynamicPredict =>
        Vector((p.moduleName, p.layout))
      case react: ReAct =>
        collectKnownSignatures(react.module)
      case _ =>
        Vector.empty
