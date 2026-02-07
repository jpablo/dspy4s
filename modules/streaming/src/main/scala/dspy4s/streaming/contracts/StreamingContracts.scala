package dspy4s.streaming.contracts

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Module
import dspy4s.core.contracts.Prediction
import dspy4s.core.contracts.RuntimeContext

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

trait StreamListener:
  def id: String
  def onEvent(event: StreamEvent)(using RuntimeContext): Option[StreamEvent]

trait Streamifier:
  def stream(
      program: Module[Map[String, Any], Prediction],
      input: Map[String, Any],
      listeners: Vector[StreamListener] = Vector.empty
  )(using RuntimeContext): LazyList[StreamEvent]
