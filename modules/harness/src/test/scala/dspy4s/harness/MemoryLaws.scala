package dspy4s.harness

import dspy4s.harness.kernel.Source
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.scalacheck.{Gen, Prop}

import java.nio.charset.StandardCharsets.UTF_8

/** The laws of the memory algebra (CODE_HARNESS.md, 6.2; CODE_HARNESS_DESIGN.md, 7.1). A memory comes only from the
  * public functions: messages through `append`, and lines through `build` of ready nodes, so each memory of a law is
  * one that a session can make.
  */
class MemoryLaws extends ScalaCheckSuite:

  private def message(i: Int): MemoryMessage = MemoryMessage(MessageKind.Heard(Source.User), s"message $i", i.toLong)

  /** A memory of `n` messages, after `rounds` rounds that each build some of the ready nodes. */
  private def memoryOf(n: Int, picks: Vector[Vector[Boolean]]): Memory =
    val logged = (0 until n).foldLeft(Memory.empty)((m, i) => Memory.append(m, message(i)))
    picks.foldLeft(logged) { (m, round) =>
      Memory.ready(m).zipWithIndex.foldLeft(m) { case (acc, (id, i)) =>
        if round.lift(i).getOrElse(true) then Memory.buildNode(acc, id, MemoryLine.of(s"line ${id.ref}")) else acc
      }
    }

  /** A memory with each node built: each round builds each ready node. */
  private def fullOf(n: Int): Memory = memoryOf(n, Vector.fill(8)(Vector.empty))

  private val memories: Gen[Memory] =
    for
      n      <- Gen.choose(0, 70)
      rounds <- Gen.choose(0, 8)
      picks  <- Gen.listOfN(rounds, Gen.listOfN(70, Gen.oneOf(true, false)).map(_.toVector))
    yield memoryOf(n, picks.toVector)

  private val nodes: Gen[NodeId] =
    for
      level <- Gen.choose(0, 10)
      index <- Gen.choose(0L, 1000L)
    yield NodeId(level, index)

  /** The spans of `v` follow each other from 0. */
  private def covers(v: View): Boolean =
    v.parts.foldLeft(Option(0L))((at, id) => at.filter(_ == id.span._1).map(_ => id.span._2)).contains(v.upTo)

  property("Memory: the halves of a node cover its span in order, and the parent of each half is the node") {
    forAll(nodes) { id =>
      val (start, end) = id.span
      Prop(end - start == (1L << id.level)) && Prop(start == id.index * (1L << id.level)) &&
      Prop(id.halves.forall((a, b) => a.span._1 == start && a.span._2 == b.span._1 && b.span._2 == end)) &&
      Prop(id.halves.forall((a, b) => a.parent == id && b.parent == id)) &&
      Prop(id.halves.isEmpty == (id.level == 0))
    }
  }

  property("Memory: a reference parses back to its node, and a text of another form gives None") {
    forAll(nodes) { id =>
      Prop(NodeId.parse(id.ref) == Some(id)) && Prop(NodeId.parse(s"x${id.ref}").isEmpty) &&
      Prop(NodeId.parse("").isEmpty) && Prop(NodeId.parse("1.-2").isEmpty)
    }
  }

  property("Memory: MemoryLine.of keeps a text of 512 bytes or less, and cuts a longer one at the end of a character") {
    forAll(Gen.choose(0, 900), Gen.oneOf("a", "é", "€", "𝄞")) { (n, unit) =>
      val text  = unit * n
      val line  = MemoryLine.of(text)
      val bytes = line.text.getBytes(UTF_8).length
      if text.getBytes(UTF_8).length <= MemoryLine.MaxBytes then Prop(line.text == text)
      else
        Prop(bytes <= MemoryLine.MaxBytes) && Prop(text.startsWith(line.text)) && Prop(bytes > MemoryLine.MaxBytes - 4)
    }
  }

  property("Memory: Roots: roots(n) has one node for each set bit of n, and their spans cover [0, n) in order") {
    forAll(Gen.choose(0L, 1L << 40)) { n =>
      val roots = Memory.roots(n)
      Prop(roots.size == java.lang.Long.bitCount(n)) && Prop(covers(View(roots))) && Prop(View(roots).upTo == n) &&
      Prop(roots.map(_.level) == roots.map(_.level).sorted.reverse)
    }
  }

  property("Memory: Coverage: a view from extend, fit and split has spans that follow each other and cover [0, end)") {
    forAll(memories, Gen.choose(1, 40), Gen.listOfN(5, Gen.choose(0, 70))) { (m, high, stops) =>
      val ends  = stops.map(_.toLong.min(m.log.size.toLong)).sorted.toVector
      val views = ends.scanLeft(View.empty)((v, upTo) => View.fit(View.extend(v, upTo), m, high))
      Prop(views.forall(covers)) && Prop(views.tail.map(_.upTo) == ends) &&
      Prop(views.forall(v => v.parts.forall(p => covers(View.split(v, p)))))
    }
  }

  property("Memory: Zoom: a view that shows the two halves of a built node joins back to the view that shows it") {
    forAll(Gen.choose(1, 70), Gen.choose(1, 40)) { (n, high) =>
      val m = fullOf(n)
      val v = View.fit(View.extend(View.empty, n.toLong), m, high)
      v.parts.forall(p => View.join(View.split(v, p), m, p) == v) &&
      v.parts.filter(_.level == 0).forall(p => View.split(v, p) == v)
    }
  }

  property("Memory: join of a node that is not built, or whose halves are not adjacent parts, gives the same view") {
    forAll(memories, nodes) { (m, id) =>
      val v = View.extend(View.empty, m.log.size.toLong)
      m.built.contains(id) && id.level == 1 && id.span._2 <= m.log.size || View.join(v, m, id) == v
    }
  }

  property("Memory: Prefix: extend keeps the view at its start, and fit keeps a view of high parts or fewer") {
    forAll(memories, Gen.choose(1, 40), Gen.choose(0, 70)) { (m, high, upTo) =>
      val start    = View.fit(View.extend(View.empty, (upTo / 2).toLong.min(m.log.size)), m, high)
      val extended = View.extend(start, upTo.toLong.min(m.log.size))
      Prop(extended.parts.startsWith(start.parts)) &&
      Prop(extended.parts.size > high || View.fit(extended, m, high) == extended)
    }
  }

  property("Memory: Immutability: build never changes a built node, and a fork keeps each node that ends before it") {
    forAll(memories, nodes, Gen.choose(0, 70)) { (m, id, n) =>
      val again = Memory.buildNode(m, id, MemoryLine.of("another line"))
      val fork  = Memory.fork(m, n.toLong.min(m.log.size))
      Prop(m.built.forall((k, line) => again.built.get(k).contains(line))) &&
      Prop(fork.log == m.log.take(n.min(m.log.size))) &&
      Prop(m.built.filter((k, _) => k.span._2 <= fork.log.size).toSet == fork.built.toSet)
    }
  }

  property("Memory: Order: ready gives only nodes whose halves are built, and build of another node changes nothing") {
    forAll(memories, nodes) { (m, id) =>
      val ready = Memory.ready(m)
      Prop(ready.forall(r => !m.built.contains(r) && r.span._2 <= m.log.size)) &&
      Prop(ready.forall(r => r.halves.forall((a, b) => m.built.contains(a) && m.built.contains(b)))) &&
      Prop(ready.contains(id) || Memory.buildNode(m, id, MemoryLine.of("x")) == m)
    }
  }

  property(
    "Memory: Size: after fit a view has at most high parts, when each parent is built and roots fit in high / 2"
  ) {
    forAll(Gen.choose(0, 70), Gen.choose(1, 40)) { (n, high) =>
      val m = fullOf(n)
      Memory.roots(n.toLong).size > high / 2 ||
      View.fit(View.extend(View.empty, n.toLong), m, high).parts.size <= high
    }
  }

  property("Memory: zoom gives the message at level 0 and the lines of the halves of a built node, else None") {
    forAll(Gen.choose(1, 70), nodes) { (n, id) =>
      val m   = fullOf(n)
      val got = Memory.zoom(m, id)
      if id.span._2 > n then Prop(got.isEmpty)
      else if id.level == 0 then Prop(got == Some(Left(m.log(id.index.toInt))))
      else
        val (a, b) = id.halves.get
        Prop(got == Some(Right((m.built(a), m.built(b)))))
    }
  }

  property("Memory: a reference has a level from 0 to 62 and an index below 2^(62 - level), else parse gives None") {
    forAll(Gen.choose(0, 70), Gen.choose(-3L, 3L)) { (level, delta) =>
      val limit = if level <= NodeId.MaxLevel then 1L << (NodeId.MaxLevel - level) else 0L
      val index = (limit - 1 + delta).max(-1L)
      val fits  = level <= NodeId.MaxLevel && index >= 0 && index < limit
      Prop(NodeId.parse(s"$level.$index") == Option.when(fits)(NodeId(level, index)))
    }
  }

  property("Memory: key gives the same digest for the same texts, another for another text, and None outside the log") {
    forAll(Gen.choose(1, 40), Gen.choose(0, 39)) { (n, changed) =>
      val m     = fullOf(n)
      val other = (0 until n).foldLeft(Memory.empty) { (acc, i) =>
        Memory.append(acc, if i == changed then message(i).copy(text = "another text") else message(i))
      }
      val built = m.built.keys.toVector
      Prop(built.forall(id => Memory.key(m, id) == Memory.key(fullOf(n), id))) &&
      Prop(built.forall(id => Memory.key(m, id).map(_.id).contains(id))) &&
      Prop(built.forall { id =>
        val (start, end) = id.span
        val touched      = changed >= start && changed < end
        (Memory.key(m, id) == Memory.key(other, id)) != touched
      }) &&
      Prop(Memory.key(m, NodeId(0, n.toLong)).isEmpty)
    }
  }

  property("Memory: advance: the view of n messages depends only on n, so two advances give the view of one") {
    forAll(Gen.choose(0, 70), Gen.choose(0, 70), Gen.choose(1, 12)) { (a, b, high) =>
      val (k, n) = (a.min(b).toLong, a.max(b).toLong)
      val m      = fullOf(n.toInt)
      View.advance(View.advance(View.empty, m, high, k), m, high, n) == View.advance(View.empty, m, high, n)
    }
  }

  property("Memory: a fit that changes a view drops it to high / 2 parts, or to the roots, when each parent is built") {
    forAll(Gen.choose(0, 70), Gen.choose(1, 40)) { (n, high) =>
      val m   = fullOf(n)
      val all = View.extend(View.empty, n.toLong)
      val fit = View.fit(all, m, high)
      fit == all || fit.parts.size <= (high / 2).max(Memory.roots(n.toLong).size)
    }
  }

  property("Memory: in a view of advance, the levels do not grow from the oldest part to the newest") {
    forAll(Gen.choose(0, 200), Gen.choose(2, 40)) { (n, high) =>
      val levels = View.advance(View.empty, fullOf(n), high, n.toLong).parts.map(_.level)
      levels.zip(levels.drop(1)).forall((older, newer) => older >= newer)
    }
  }
