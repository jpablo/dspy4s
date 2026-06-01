package dspy4s.streaming

import munit.FunSuite

import java.util.concurrent.atomic.AtomicBoolean

class StreamingQueueSuite extends FunSuite:

  test("queue delivers items in offer order") {
    val queue = StreamingQueue[Int](8)
    val producer = new Thread(() => {
      queue.offer(1)
      queue.offer(2)
      queue.offer(3)
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

    queue.offer("a")
    queue.offer("b")
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
    queue.offer(1)
    queue.offer(2)
    val iter = queue.asIterator
    assertEquals(iter.hasNext, true)
    assertEquals(iter.next(), 1)
    iter.close()
    assertEquals(iter.hasNext, false)
  }

  test("close is idempotent") {
    val queue = StreamingQueue[Int](8)
    queue.offer(1)
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
