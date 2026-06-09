package dspy4s.gepa

import munit.FunSuite

import scala.util.Random

class GepaStateSuite extends FunSuite:

  private def state(subscores: Vector[Double]*): GepaState =
    GepaState(
      candidates = subscores.indices.map(i => Map("0" -> s"instr$i")).toVector,
      valSubscores = subscores.toVector,
      parents = subscores.indices.map(_ => Vector.empty[Int]).toVector,
      totalMetricCalls = 0
    )

  test("aggregateScore is the mean and bestIndex picks the highest mean") {
    val s = state(Vector(1.0, 0.0), Vector(1.0, 1.0)) // means 0.5, 1.0
    assertEquals(s.aggregateScore(0), 0.5)
    assertEquals(s.aggregateScore(1), 1.0)
    assertEquals(s.bestIndex, 1)
  }

  test("paretoFrontier maps each instance to the candidates that are best on it") {
    // c0 best on instance 0; c1 best on instance 1 (complementary specialists).
    val s = state(Vector(1.0, 0.0), Vector(0.0, 1.0))
    assertEquals(s.paretoFrontier, Map(0 -> Set(0), 1 -> Set(1)))
  }

  test("a dominating candidate occupies the whole frontier") {
    val s = state(Vector(0.0, 0.0), Vector(1.0, 1.0))
    assertEquals(s.paretoFrontier, Map(0 -> Set(1), 1 -> Set(1)))
  }

  test("Pareto selection only ever returns a frontier candidate (never the dominated one)") {
    val s   = state(Vector(0.0, 0.0), Vector(1.0, 1.0)) // c1 dominates
    val rng = new Random(0)
    val picks = (0 until 50).map(_ => CandidateSelector.Pareto.select(s, rng)).toSet
    assertEquals(picks, Set(1))
  }

  test("CurrentBest selection is the highest-mean candidate") {
    val s = state(Vector(1.0, 0.0), Vector(1.0, 1.0))
    assertEquals(CandidateSelector.CurrentBest.select(s, new Random(0)), 1)
  }

  test("add appends a candidate and accrues metric calls") {
    val s0 = GepaState.seed(Map("0" -> "seed"), Vector(0.5, 0.5), metricCalls = 2)
    val s1 = s0.add(Map("0" -> "child"), Vector(1.0, 1.0), parents = Vector(0), metricCalls = 2)
    assertEquals(s1.candidates.size, 2)
    assertEquals(s1.bestIndex, 1)
    assertEquals(s1.parents(1), Vector(0))
    assertEquals(s1.totalMetricCalls, 4)
  }

  test("ancestors walks the full lineage, including a merge's two branches up to a common ancestor") {
    // Lineage: 0 (seed) -> 1, 0 -> 2; then 3 is a MERGE of 1 and 2 (two parents).
    val s = GepaState(
      candidates = Vector(Map("a" -> "0"), Map("a" -> "1"), Map("a" -> "2"), Map("a" -> "3")),
      valSubscores = Vector.fill(4)(Vector(1.0)),
      parents = Vector(Vector.empty, Vector(0), Vector(0), Vector(1, 2)),
      totalMetricCalls = 0
    )
    assertEquals(s.ancestors(0), Set.empty[Int])
    assertEquals(s.ancestors(1), Set(0))
    assertEquals(s.ancestors(3), Set(1, 2, 0)) // both branches converge on the common ancestor 0
  }

  test("RoundRobin picks one component per call and cycles through, wrapping the pointer") {
    val cs         = Vector("a", "b", "c")
    // Thread the pointer through six calls; expect a, b, c, a, b, c.
    val (picks, _) = (0 until 6).foldLeft((Vector.empty[String], 0)) { case ((acc, ptr), _) =>
      val (chosen, next) = ComponentSelector.RoundRobin.select(cs, ptr)
      (acc :+ chosen.head, next)
    }
    assertEquals(picks, Vector("a", "b", "c", "a", "b", "c"))
  }

  test("RoundRobin normalizes an out-of-range pointer") {
    // A pointer past the end (e.g. after the component list shrank) wraps via modulo.
    assertEquals(ComponentSelector.RoundRobin.select(Vector("a", "b"), 5), (Vector("b"), 0))
  }

  test("RoundRobin on no components is a no-op that preserves the pointer") {
    assertEquals(ComponentSelector.RoundRobin.select(Vector.empty, 3), (Vector.empty[String], 3))
  }

  test("All returns every component and leaves the pointer untouched") {
    val cs = Vector("a", "b", "c")
    assertEquals(ComponentSelector.All.select(cs, 2), (cs, 2))
  }
