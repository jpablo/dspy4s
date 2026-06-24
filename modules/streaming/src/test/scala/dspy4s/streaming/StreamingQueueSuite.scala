package dspy4s.streaming

import munit.FunSuite

import java.util.concurrent.atomic.AtomicBoolean

class StreamingQueueSuite extends FunSuite:

  test("queue delivers items in offer order") {
    val queue = StreamingQueue[Int](8)
    val producer = new Thread(() => {
      val _ = queue.offer(1)
      val _ = queue.offer(2)
      val _ = queue.offer(3)
      queue.close()
    })
    producer.start()

    val received = queue.asIterator.toList
    assertEquals(received, List(1, 2, 3))
    producer.join(1000)
  }

  test("iterator blocks until items arrive and completes on close") {
    val queue = StreamingQueue[String](8)
    val received = new java.util.concurrent.CopyOnWriteArrayList[String]()
    val done = new AtomicBoolean(false)

    val consumer = new Thread(() => {
      val iter = queue.asIterator
      while iter.hasNext do { val _ = received.add(iter.next()) }
      done.set(true)
    })
    consumer.start()

    Thread.sleep(100)
    assertEquals(done.get(), false)
    assertEquals(received.size(), 0)

    val _ = queue.offer("a")
    val _ = queue.offer("b")
    Thread.sleep(100)
    assertEquals(received.size(), 2)

    queue.close()
    consumer.join(1000)
    assertEquals(done.get(), true)
    assertEquals(received.size(), 2)
  }

  test("empty queue closed before consumer reads signals empty iteration") {
    val queue = StreamingQueue[Int](8)
    queue.close()
    val iter = queue.asIterator
    assertEquals(iter.hasNext, false)
  }

  test("closing iterator stops consumption") {
    val queue = StreamingQueue[Int](8)
    val _ = queue.offer(1)
    val _ = queue.offer(2)
    val iter = queue.asIterator
    assertEquals(iter.hasNext, true)
    assertEquals(iter.next(), 1)
    iter.close()
    assertEquals(iter.hasNext, false)
  }

  test("close is idempotent") {
    val queue = StreamingQueue[Int](8)
    val _ = queue.offer(1)
    queue.close()
    queue.close()
    val iter = queue.asIterator
    assertEquals(iter.next(), 1)
    assertEquals(iter.hasNext, false)
  }

  test("offer after close returns false without enqueuing") {
    val queue = StreamingQueue[Int](8)
    queue.close()
    assertEquals(queue.offer(99), false)
    val iter = queue.asIterator
    assertEquals(iter.hasNext, false)
  }

  test("a producer parked on a full buffer is released when the consumer abandons the stream") {
    // Regression: blocking put() + a consumer close() that didn't drain left the producer parked forever.
    val queue   = StreamingQueue[Int](2) // tiny buffer so the producer blocks quickly
    val stopped = new AtomicBoolean(false)
    val producer = new Thread(() => {
      var i = 0
      while queue.offer(i) do i += 1 // keep producing until offer reports the consumer is gone
      stopped.set(true)
    })
    producer.start()

    val iter = queue.asIterator
    assertEquals(iter.next(), 0) // read one, then abandon while the producer is blocked on the full buffer
    iter.close()

    producer.join(2000)
    assert(!producer.isAlive, "producer thread must terminate after the consumer abandons the stream")
    assert(stopped.get(), "offer must eventually return false once the consumer has abandoned the stream")
  }
