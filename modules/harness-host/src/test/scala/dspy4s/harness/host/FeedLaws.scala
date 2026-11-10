package dspy4s.harness.host

import dspy4s.harness.{Secrets, Support}
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*

import scala.collection.mutable.ArrayBuffer

/** The laws of the feeds of a session (CODE_HARNESS_DESIGN.md, 10.2). */
class FeedLaws extends munit.FunSuite:

  private def inputs(from: Int, n: Int): Vector[Logged] =
    Support.logOf(1000L, (0 until from + n).map(i => Entry.Input(RequestId(s"r$i"), "x", Map.empty))*).drop(from)

  /** Each entry that `feed` has now. */
  private def drain(feed: Feed): Vector[Either[Behind, Logged]] =
    Iterator.continually(feed.poll()).takeWhile(_.isDefined).flatten.toVector

  test("Feed: a client gets the entries of each publish after its subscribe, in the order of seq") {
    val feeds = Feeds()
    feeds.publish(inputs(0, 2))
    val feed = feeds.subscribe(16)
    feeds.publish(inputs(2, 3))
    feeds.publish(inputs(5, 1))
    assertEquals(drain(feed), inputs(2, 4).map(Right(_)))
  }

  test("Feed: a full queue gives Behind at the next poll, and publish does not wait for the client") {
    val feeds = Feeds()
    val feed  = feeds.subscribe(2)
    (0 until 10).foreach(i => feeds.publish(inputs(i, 1)))
    feed.poll() match
      case Some(Left(Behind(lost))) => assert(lost > 0, lost.toString)
      case other                    => fail(s"no Behind: $other")
  }

  test("Feed: a client that reads the journal after its last seq on Behind misses no entry and gets none twice") {
    withRoot { root =>
      val file  = SessionFile.open(SessionFile.pathOf(root, id(1)), id(1)).fold(e => fail(e), identity)
      val feeds = Feeds()
      val w     = Writer(file, Vector.empty, () => 1000L, Secrets.none, feeds, 64, _ => ())
      try
        var feed         = feeds.subscribe(3)
        val seen         = ArrayBuffer.empty[Long]
        def take(): Unit =
          drain(feed).foreach {
            case Right(logged) => if seen.lastOption.forall(_ < logged.seq) then seen += logged.seq: Unit
            case Left(_)       =>
              feed = feeds.subscribe(3)
              w.log.filter(l => seen.lastOption.forall(_ < l.seq)).foreach(l => seen += l.seq: Unit)
          }
        assert(w.run(Command.Activate(Support.versions, config()), Source.Harness).isRight)
        (1 to 40).foreach { i =>
          assert(w.run(message(s"r$i"), Source.User).isRight)
          if i % 7 == 0 then take()
        }
        take()
        assertEquals(seen.toVector, w.log.map(_.seq))
      finally w.close()
    }
  }

  test("Feed: the writer publishes the entries of a commit before run returns") {
    withRoot { root =>
      val file  = SessionFile.open(SessionFile.pathOf(root, id(1)), id(1)).fold(e => fail(e), identity)
      val feeds = Feeds()
      val w     = Writer(file, Vector.empty, () => 1000L, Secrets.none, feeds, 64, _ => ())
      try
        val feed = feeds.subscribe(16)
        assert(w.run(Command.Activate(Support.versions, config()), Source.Harness).isRight)
        assertEquals(drain(feed).map(_.map(_.entry)), Vector(Right(Entry.Activated(Support.versions, config()))))
      finally w.close()
    }
  }

  test("Feed: after the close of the feeds, a client gets the entries of its queue, then none, and ended is true") {
    val feeds = Feeds()
    val feed  = feeds.subscribe(4)
    feeds.publish(inputs(0, 1))
    assert(!feed.ended)
    feeds.close()
    assertEquals(drain(feed), inputs(0, 1).map(Right(_)))
    assertEquals(feed.poll(), None)
    assert(feed.ended)
  }
