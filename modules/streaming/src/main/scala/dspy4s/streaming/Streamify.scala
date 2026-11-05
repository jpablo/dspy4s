package dspy4s.streaming

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.runtime.ContextPropagation
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.runtime.SettingsProgramRuntime
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
    * The stream is in the context, not in the model. Each invocation puts a [[StreamSession]] in the context of the
    * program ([[dspy4s.core.contracts.RuntimeContext.streamSink]], the analog of upstream `settings.send_stream`), and
    * [[dspy4s.lm.contracts.LanguageModel.callAndRecordUsage]] wraps each model that it calls with that session. Thus
    * each model call of the program streams: the model of the context, the own model of a predictor, the model of a
    * scope in the program, the extraction model of `TwoStepAdapter`, and the sub-LM of `RLM`. A program with no model
    * in the context streams too. The model of the context stays the real model. A direct `call` or `stream` of a model
    * does not stream.
    *
    * The wrappers of all the models of one invocation share the session. A `streamify` inside a program replaces the
    * session in the context of its own program, so the tokens of that program go to the inner stream only (as
    * upstream).
    *
    * Per-LM-call signature routing happens inside [[StreamingLanguageModelWrapper]]: it consults
    * [[dspy4s.core.runtime.ActivePredictContext]] at the start of each `call()` to pick the active predictor's
    * signature and name, then builds a fresh adapter state machine for that signature. As a result, programs that
    * internally invoke multiple `DynamicPredict`s with different signatures stream per-field tokens correctly under
    * each predictor's own framing.
    *
    * The producer thread runs the program in the context that `streamify` gets (UPGRADE-3.2.1-to-3.4.0, decision D8:
    * the given context wins), also when the caller did not install that context. Each field that the given context does
    * not set comes from the context that is installed when the returned function runs, also the adapter of the stream.
    * Each invocation of the returned function takes the dynamic scope and the carriers of the thread that invokes it.
    *
    * A caller that stops before the end of the stream must close the iterator. A consumer that stops and does not close
    * leaves the producer thread parked on the full queue. Upstream has the same need. A close removes the events in the
    * queue, makes the queue refuse new events, and interrupts the producer thread. A program that waits gets an
    * `InterruptedException`, or a `Left` when the HTTP transport of a model has the wait. A program in a model call
    * stops at its next token, because [[StreamingLanguageModelWrapper]] closes the model stream and throws that
    * exception. After a close, the iterator has no more events. An interrupt that has another source gives an
    * [[ErrorEvent]].
    *
    * With `includeFinalPrediction = false`, the stream still has the prediction when there are no listeners, when a
    * model call was a cache hit, or when no listener got a token (upstream `streamify.py`). A model that does not
    * stream gives no token, so the prediction stays.
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
    // is subscribed to a field name that no DynamicPredict in the program emits,
    // or to a field of more than one DynamicPredict with no predictor name.
    // For composite programs whose internals we can't introspect (a user-
    // defined `Module`), validation is silently skipped.
    validateListeners(streamable.knownSignatures(program), streamListeners, warningSink)

    inputs =>
      val queue = StreamingQueue[StreamEvent](queueCapacity)
      // The producer thread runs in the effective context of `streamify`: the given context, filled from the installed
      // one (decision D8: the given context wins). The adapter of the stream comes from that context too.
      val (captured, effective) =
        RuntimeEnvironment.withInstalled(outerContext)(installed ?=> (ContextPropagation.captureAll, installed))

      val provider = statusMessageProvider.getOrElse(StatusMessageProvider.default)
      val callback = new StatusStreamingCallback(provider, queue)

      // The stream rules come from the configured adapter. With no configured adapter, they are the chat rules
      // (upstream `StreamListener.receive` and `flush` in `dspy/streaming/streaming_listener.py`).
      val adapter = SettingsProgramRuntime.adapterOrChat(effective)

      val session = StreamSession(queue, Some(adapter), streamListeners)

      // Upstream `streamify.py`: a stream with no token of a listener still has the prediction.
      def keepsPrediction: Boolean =
        includeFinalPrediction || streamListeners.isEmpty || session.sawCacheHit || !session.gaveToken

      val producer = new Thread(
        new Runnable:
          override def run(): Unit =
            // close() must run no matter how the producer dies: a throwable that escapes the inner catch
            // (StackOverflowError) or a throw in the context-setup calls would otherwise never enqueue the
            // end-of-stream sentinel and leave the consumer parked in take() forever.
            try
              val _ = captured.run {
                RuntimeEnvironment.withSettings(RuntimeContext(streamSink = Some(session))) {
                  val existingCallbacks = RuntimeEnvironment.current.callbacks
                  RuntimeEnvironment.withCallbacks(existingCallbacks :+ callback) {
                    given RuntimeContext = RuntimeEnvironment.current
                    try
                      streamable.run(program, inputs) match
                        case Right(prediction) => if keepsPrediction then
                            val _ = queue.offer(PredictionEvent(prediction))
                        case Left(error) => queue.offer(ErrorEvent(error))
                    catch
                      // An interrupt comes from a close of the consumer, or from another source. In the first case
                      // the queue refuses this event, and in the second case the consumer gets it.
                      case error @ (NonFatal(_) | _: InterruptedException) =>
                        // Clear the interrupt status, so that the offer does not fail for a consumer that still reads.
                        val _ = Thread.interrupted()
                        queue.offer(ErrorEvent(RuntimeError("streamify", DspyError.messageOf(error))))
                  }
                }
              }
            catch
              // A close of the consumer during the offer of the error event. The event has no reader.
              case _: InterruptedException => ()
            finally queue.close()
      )
      producer.setDaemon(true)
      producer.setName("dspy4s-streamify-producer")
      producer.start()

      val events = queue.asIterator
      new ClosableIterator[StreamEvent]:
        override def hasNext: Boolean    = events.hasNext
        override def next(): StreamEvent = events.next()
        override def close(): Unit       =
          events.close()
          producer.interrupt()

  /** Walk the program tree, collect every DynamicPredict-derived signature we can statically see, and warn for each
    * listener whose `signatureFieldName` does not appear in any of them, whose `predictName` does not exist, or that
    * has no `predictName` for a field of more than one signature. Composite programs that aren't one of the well-known
    * shapes contribute nothing. For those, validation is skipped (matches Python's behavior of "look as far as you
    * can").
    */
  private def validateListeners(
      knownSignatures: Vector[(String, SignatureLayout)],
      listeners      : Vector[StreamListener],
      warningSink    : String => Unit
  ): Unit =
    if knownSignatures.nonEmpty then
      val knownFields: Set[String]       = knownSignatures.flatMap(_._2.outputFields.map(_.name)).toSet
      val knownPredictNames: Set[String] = knownSignatures.map(_._1).toSet
      listeners.foreach { listener =>
        val field = listener.signatureFieldName
        if !knownFields.contains(field) then
          warningSink(
            s"StreamListener(signatureFieldName=$field" +
              listener.predictName.fold("")(n => s", predictName=$n") +
              s") will never fire: no DynamicPredict in the program emits that output field. " +
              s"Known output fields: ${knownFields.toSeq.sorted.mkString(", ")}."
          )
        else
          listener.predictName match
            case Some(name) => if !knownPredictNames.contains(name) then
                warningSink(
                  s"StreamListener(signatureFieldName=$field, " +
                    s"predictName=$name) will never fire: no DynamicPredict in the program is named '$name'. " +
                    s"Known predict names: ${knownPredictNames.toSeq.sorted.mkString(", ")}."
                )
            case None => if knownSignatures.count(_._2.outputFields.exists(_.name == field)) > 1 then
                warningSink(
                  s"Signature field $field is not unique in the program, cannot automatically determine which " +
                    "predictor to use for streaming. Please specify the predictor to listen to."
                )
      }
