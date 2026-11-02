package dspy4s.streaming.contracts

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction

import java.time.Instant

sealed trait StreamEvent extends Product with Serializable:
  def timestamp: Instant

final case class TokenEvent(
    predictName: String,
    fieldName  : String,
    chunk      : String,
    isLastChunk: Boolean = false,
    timestamp  : Instant = Instant.now()
) extends StreamEvent

final case class StatusEvent(message: String, timestamp: Instant = Instant.now()) extends StreamEvent

final case class PredictionEvent(prediction: RawPrediction, timestamp: Instant = Instant.now()) extends StreamEvent

final case class ErrorEvent(error: DspyError, timestamp: Instant = Instant.now()) extends StreamEvent

/** Subscribes a stream consumer to a specific output field of a predictor.
  *
  *   - `signatureFieldName` selects which output field to receive [[TokenEvent]]s for.
  *   - `predictName` optionally narrows the subscription to a specific predictor when a program contains more than one;
  *     `None` matches any predictor.
  *   - `allowReuse` controls whether the listener keeps firing across multiple LM calls in the same `streamify`
  *     invocation. `false` (the default, as upstream `allow_reuse=False`) emits for the first field cycle only, up to
  *     and including the chunk with `isLastChunk = true`. The listener then goes silent. `true` emits on every matching
  *     call. A loop, for example `ReAct`, needs `true` to stream each step.
  */
final case class StreamListener(
    signatureFieldName: String,
    predictName       : Option[String] = None,
    allowReuse        : Boolean        = false
):
  def matches(predict: String, field: String): Boolean =
    field == signatureFieldName && predictName.forall(_ == predict)
