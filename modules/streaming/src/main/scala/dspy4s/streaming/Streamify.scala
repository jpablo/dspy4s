package dspy4s.streaming

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.contracts.Adapter
import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.runtime.ContextPropagation
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.streaming.contracts.ErrorEvent
import dspy4s.streaming.contracts.PredictionEvent
import dspy4s.streaming.contracts.StreamEvent
import dspy4s.streaming.contracts.StreamListener
import zio.blocks.schema.DynamicValue

import scala.util.control.NonFatal

object Streamify:

  /** Wrap a program in a streaming envelope: each invocation of the returned function runs the program on a daemon
    * producer thread and yields a [[ClosableIterator]] of [[StreamEvent]]s.
    *
    * Per-LM-call signature routing happens inside the wrapped [[StreamingLanguageModel]]: it consults
    * [[dspy4s.core.runtime.ActivePredictContext]] at the start of each `call()` to pick the active predictor's
    * signature and name, then builds a fresh adapter state machine for that signature. As a result, programs that
    * internally invoke multiple `DynamicPredict`s with different signatures stream per-field tokens correctly under
    * each predictor's own framing.
    *
    * The producer thread runs the program in the context that `streamify` gets (UPGRADE-3.2.1-to-3.4.0, decision D8:
    * the given context wins), also when the caller did not install that context. Each field that the given context does
    * not set comes from the context that is installed when the returned function runs, also the model and the adapter
    * of the stream. Each invocation of the returned function takes the dynamic scope and the carriers of the thread
    * that invokes it.
    */
  def streamify[P](
      program               : P,
      statusMessageProvider : Option[StatusMessageProvider] = None,
      streamListeners       : Vector[StreamListener]        = Vector.empty,
      includeFinalPrediction: Boolean                       = true,
      queueCapacity         : Int                           = 64,
      warningSink           : String => Unit                = msg => System.err.println(s"[dspy4s.streamify] $msg")
  )(using
      outerContext: RuntimeContext,
      streamable  : Streamable[P]
  ): DynamicValue.Record => ClosableIterator[StreamEvent] =
    // Validate listener field names against the program structure as far as
    // we can statically see it. This is dspy4s's equivalent of Python's
    // `find_predictor_for_stream_listeners`: warn (don't fail) if a listener
    // is subscribed to a field name that no DynamicPredict in the program emits.
    // For composite programs whose internals we can't introspect (a user-
    // defined `Module`), validation is silently skipped.
    validateListeners(streamable.knownSignatures(program), streamListeners, warningSink)

    inputs =>
      val queue = StreamingQueue[StreamEvent](queueCapacity)
      // The producer thread runs in the effective context of `streamify`: the given context, filled from the installed
      // one (decision D8: the given context wins). The model and the adapter of the stream come from that context too.
      val (captured, effective) =
        RuntimeEnvironment.withInstalled(outerContext)(installed ?=> (ContextPropagation.captureAll, installed))

      val provider = statusMessageProvider.getOrElse(StatusMessageProvider.default)
      val callback = new StatusStreamingCallback(provider, queue)

      val currentLm = effective.lm
      // The stream rules come from the configured adapter. With no configured adapter, they are the chat rules
      // (upstream `StreamListener.receive` and `flush` in `dspy/streaming/streaming_listener.py`).
      val adapter = effective.adapter match
        case Some(configured: Adapter) => Some(configured)
        case Some(_)                   => None
        case None                      => Some(ChatAdapter())

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
            // close() must run no matter how the producer dies — a throwable that escapes the inner NonFatal
            // catch (InterruptedException, StackOverflowError) or a throw in the context-setup calls would
            // otherwise never enqueue the end-of-stream sentinel and leave the consumer parked in take() forever.
            try
              val _ = captured.run {
                RuntimeEnvironment.withSettings(lmOverride) {
                  val existingCallbacks = RuntimeEnvironment.current.callbacks
                  RuntimeEnvironment.withCallbacks(existingCallbacks :+ callback) {
                    given RuntimeContext = RuntimeEnvironment.current
                    try
                      streamable.run(program, inputs) match
                        case Right(prediction) => if includeFinalPrediction then
                            val _ = queue.offer(PredictionEvent(prediction))
                        case Left(error) => queue.offer(ErrorEvent(error))
                    catch
                      case NonFatal(error) =>
                        val runtimeError = dspy4s.core.contracts.RuntimeError(
                          "streamify",
                          DspyError.messageOf(error)
                        )
                        queue.offer(ErrorEvent(runtimeError))
                  }
                }
              }
            finally queue.close()
      )
      producer.setDaemon(true)
      producer.setName("dspy4s-streamify-producer")
      producer.start()

      queue.asIterator

  /** Walk the program tree, collect every DynamicPredict-derived signature we can statically see, and warn for each
    * listener whose `signatureFieldName` does not appear in any of them. Composite programs that aren't one of the
    * well-known shapes contribute nothing — for those, validation is skipped (matches Python's behavior of "look as far
    * as you can").
    */
  private def validateListeners(
      knownSignatures: Vector[(String, SignatureLayout)],
      listeners      : Vector[StreamListener],
      warningSink    : String => Unit
  ): Unit =
    if listeners.isEmpty then return
    if knownSignatures.isEmpty then return // opaque program; skip
    val knownFields: Set[String]       = knownSignatures.flatMap(_._2.outputFields.map(_.name)).toSet
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
