package dspy4s.streaming

import java.util.concurrent.LinkedBlockingQueue

trait ClosableIterator[+A] extends Iterator[A] with AutoCloseable

final class StreamingQueue[A](capacity: Int):
  private val queue = new LinkedBlockingQueue[Option[A]](capacity)
  @volatile private var closed = false

  def isClosed: Boolean = closed

  def offer(item: A): Boolean =
    if closed then false
    else
      queue.put(Some(item))
      true

  def close(): Unit =
    if !closed then
      closed = true
      queue.put(None)
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
      pending = null

object StreamingQueue:
  def apply[A](capacity: Int = 64): StreamingQueue[A] = new StreamingQueue[A](capacity)
