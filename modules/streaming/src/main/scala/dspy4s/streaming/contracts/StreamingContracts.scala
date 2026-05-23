package dspy4s.streaming.contracts

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Module
import dspy4s.core.contracts.Prediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.contracts.ProgramCall

import java.time.Instant

sealed trait StreamEvent extends Product with Serializable:
  def timestamp: Instant

final case class TokenEvent(
    predictName: String,
    fieldName: String,
    chunk: String,
    isLastChunk: Boolean = false,
    timestamp: Instant = Instant.now()
) extends StreamEvent

final case class StatusEvent(message: String, timestamp: Instant = Instant.now()) extends StreamEvent

final case class PredictionEvent(prediction: Prediction, timestamp: Instant = Instant.now()) extends StreamEvent

final case class ErrorEvent(error: DspyError, timestamp: Instant = Instant.now()) extends StreamEvent

/** Subscribes a stream consumer to a specific output field of a predictor.
  *
  *   - `signatureFieldName` selects which output field to receive
  *     [[TokenEvent]]s for.
  *   - `predictName` optionally narrows the subscription to a specific
  *     predictor when a program contains more than one; `None` matches any
  *     predictor.
  */
final case class StreamListener(
    signatureFieldName: String,
    predictName: Option[String] = None
):
  def matches(predict: String, field: String): Boolean =
    field == signatureFieldName && predictName.forall(_ == predict)

trait Streamifier:
  def streamify(
      program: Module[ProgramCall, Prediction],
      listeners: Vector[StreamListener] = Vector.empty
  ): Map[String, Any] => ClosableIterator[StreamEvent]
