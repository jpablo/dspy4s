package dspy4s.streaming

import dspy4s.core.contracts.ClosableIterator

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.annotation.tailrec

final class StreamingQueue[A](capacity: Int):
  private val queue  = new LinkedBlockingQueue[Option[A]](capacity)
  private val closed = AtomicBoolean(false)
  // Set when the consumer abandons the stream (`asIterator.close()`), so the producer stops and a `put`
  // parked on a full buffer is released (the consumer's close also `clear()`s the buffer to wake it).
  @volatile private var abandoned = false

  /** Producer side. Returns false once the queue is closed OR the consumer has abandoned the stream, so a producer that
    * checks the return value stops pushing. `put` still applies back-pressure on a full buffer, but a consumer
    * abandoning via `asIterator.close()` clears the buffer, unblocking a parked `put`.
    */
  def offer(item: A): Boolean =
    if closed.get() || abandoned then false
    else
      queue.put(Some(item))
      !abandoned

  /** Producer side. The first call gives the end marker to a consumer that did not abandon the stream. A later call
    * does nothing.
    */
  def close(): Unit =
    if closed.compareAndSet(false, true) then putEndMarker()

  /** Put the end marker, and wait for a place in a full buffer. An interrupt does not stop the wait, because a consumer
    * with no end marker waits forever. The wait stops when the consumer takes an item, or abandons the stream.
    */
  @tailrec private def putEndMarker(): Unit =
    if !abandoned then
      val put =
        try
          queue.put(None)
          true
        catch case _: InterruptedException => false
      if !put then putEndMarker()

  def asIterator: ClosableIterator[A] =
    new ClosableIterator[A]:
      private var pending: Option[A] = None
      @volatile private var ended    = false

      override def hasNext: Boolean =
        if pending.isEmpty && !ended then
          queue.take() match
            case Some(item) => pending = Some(item)
            case None       => ended = true
        pending.isDefined && !ended

      override def next(): A =
        if !hasNext then throw new NoSuchElementException("The queue has no more items")
        val item = pending.get
        pending = None
        item

      /** Abandon the stream. The clear releases a producer parked on a full buffer. The end marker wakes a consumer
        * thread parked in `take`, when another thread closes. A full buffer refuses the marker, but then a `take` does
        * not park.
        */
      override def close(): Unit =
        ended = true
        abandoned = true
        queue.clear()
        val _ = queue.offer(None)

object StreamingQueue:
  def apply[A](capacity: Int = 64): StreamingQueue[A] = new StreamingQueue[A](capacity)
