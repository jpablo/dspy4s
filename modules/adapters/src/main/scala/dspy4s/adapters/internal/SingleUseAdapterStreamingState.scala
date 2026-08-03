package dspy4s.adapters.internal

import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FieldChunk

/** Shared single-use lifecycle for adapter stream parsers.
  *
  * Deltas after completion are ignored and `finish` is idempotent. Concrete parsers only implement behavior while the
  * stream is open.
  */
private[adapters] abstract class SingleUseAdapterStreamingState extends AdapterStreamingState:
  private var finished: Boolean = false

  final override def receive(textDelta: String): Vector[FieldChunk] =
    if finished || textDelta.isEmpty then Vector.empty
    else receiveOpen(textDelta)

  final override def finish(): Vector[FieldChunk] =
    if finished then Vector.empty
    else
      finished = true
      finishOpen()

  protected def receiveOpen(textDelta: String): Vector[FieldChunk]
  protected def finishOpen(): Vector[FieldChunk]
