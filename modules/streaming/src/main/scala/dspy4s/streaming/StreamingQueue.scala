package dspy4s.streaming

import dspy4s.core.contracts.ClosableIterator

import java.util.concurrent.LinkedBlockingQueue

final class StreamingQueue[A](capacity: Int):
  private val queue = new LinkedBlockingQueue[Option[A]](capacity)
  @volatile private var closed = false
  // Set when the consumer abandons the stream (`asIterator.close()`), so the producer stops and a `put`
  // parked on a full buffer is released (the consumer's close also `clear()`s the buffer to wake it).
  @volatile private var consumerAbandoned = false

  def isClosed: Boolean = closed

  /** Producer side. Returns false once the queue is closed OR the consumer has abandoned the stream, so a
    * producer that checks the return value stops pushing. `put` still applies back-pressure on a full buffer,
    * but a consumer abandoning via `asIterator.close()` clears the buffer, unblocking a parked `put`. */
  def offer(item: A): Boolean =
    if closed || consumerAbandoned then false
    else
      queue.put(Some(item))
      !consumerAbandoned

  def close(): Unit =
    if !closed then
      closed = true
      if !consumerAbandoned then queue.put(None) // deliver end-of-stream only to a live consumer
    else ()

  def asIterator: ClosableIterator[A] = new ClosableIterator[A]:
    private var pending: Option[A] | Null = null
    private var done: Boolean = false
    private var consumerClosed: Boolean = false

    private def advance(): Unit =
      if !done && pending == null then
        queue.take() match
          case Some(item) => pending = Some(item)
          case None =>
            done = true
            pending = null

    override def hasNext: Boolean =
      if consumerClosed then false
      else if done then false
      else if pending != null then true
      else
        advance()
        !done

    override def next(): A =
      if consumerClosed then throw new NoSuchElementException("Iterator closed")
      if pending == null && !done then advance()
      if done || pending == null then throw new NoSuchElementException("Queue exhausted")
      val result = pending.get
      pending = null
      result

    override def close(): Unit =
      consumerClosed = true
      consumerAbandoned = true // signal the producer to stop
      pending = null
      queue.clear()            // release a producer parked in put() on a full buffer

object StreamingQueue:
  def apply[A](capacity: Int = 64): StreamingQueue[A] = new StreamingQueue[A](capacity)
