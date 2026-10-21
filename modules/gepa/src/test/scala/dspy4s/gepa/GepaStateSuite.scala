package dspy4s.gepa

import dspy4s.programs.optimization.OptimizableId
import dspy4s.programs.optimization.OptimizableOrdinal
import munit.FunSuite

import scala.util.Random

class GepaStateSuite extends FunSuite:

  private def state(subscores: Vector[Double]*): GepaState =
    GepaState(
      candidates = CandidatePool.applyUnsafe(
        subscores.indices.map(i => Map(OptimizableId(0) -> Some(s"instr$i"))).toVector
      ),
      valSubscores = subscores.toVector,
      parents = subscores.indices.map(_ => Vector.empty[Int]).toVector,
      totalMetricCalls = MetricCallCount(0)
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

  test("GepaState rejects ragged valSubscores up front (instead of an IndexOutOfBounds in paretoFrontier)") {
    intercept[IllegalArgumentException] {
      val _ = GepaState(
        candidates = CandidatePool.applyUnsafe(
          Vector(Map(OptimizableId(0) -> Some("a")), Map(OptimizableId(0) -> Some("b")))
        ),
        valSubscores = Vector(Vector(1.0, 0.0), Vector(1.0)), // second row is shorter — would crash paretoFrontier
        parents = Vector(Vector.empty[Int], Vector.empty[Int]),
        totalMetricCalls = MetricCallCount(0)
      )
    }
  }

  test("GepaState rejects a round-robin pointer that is negative, and pointers that are not aligned") {
    val s = state(Vector(1.0), Vector(0.0))
    val _ = intercept[IllegalArgumentException](s.copy(nextComponent = Vector(0, -1)))
    val _ = intercept[IllegalArgumentException](s.withPointer(1, -1))
    intercept[IllegalArgumentException](s.copy(nextComponent = Vector(0)))
  }

  test("Pareto selection only ever returns a frontier candidate (never the dominated one)") {
    val s     = state(Vector(0.0, 0.0), Vector(1.0, 1.0)) // c1 dominates
    val rng   = new Random(0)
    val picks = (0 until 50).map(_ => CandidateSelector.Pareto.select(s, rng)).toSet
    assertEquals(picks, Set(1))
  }

  test("CurrentBest selection is the highest-mean candidate") {
    val s = state(Vector(1.0, 0.0), Vector(1.0, 1.0))
    assertEquals(CandidateSelector.CurrentBest.select(s, new Random(0)), 1)
  }

  test("add appends a candidate and accrues metric calls") {
    val s0 = GepaState.seed(Map(OptimizableId(0) -> Some("seed")), Vector(0.5, 0.5), metricCalls = 2)
    val s1 = s0.add(Map(OptimizableId(0) -> Some("child")), Vector(1.0, 1.0), parents = Vector(0), metricCalls = 2)
    assertEquals(s1.candidates.size, 2)
    assertEquals(s1.bestIndex, 1)
    assertEquals(s1.parents(1), Vector(0))
    assertEquals(s1.totalMetricCalls, MetricCallCount(4))
  }

  test("ancestors walks the full lineage, including a merge's two branches up to a common ancestor") {
    // Lineage: 0 (seed) -> 1, 0 -> 2; then 3 is a MERGE of 1 and 2 (two parents).
    val s = GepaState(
      candidates = CandidatePool.applyUnsafe(Vector.tabulate(4)(i => Map(OptimizableId(0) -> Some(i.toString)))),
      valSubscores = Vector.fill(4)(Vector(1.0)),
      parents = Vector(Vector.empty, Vector(0), Vector(0), Vector(1, 2)),
      totalMetricCalls = MetricCallCount(0)
    )
    assertEquals(s.ancestors(0), Set.empty[Int])
    assertEquals(s.ancestors(1), Set(0))
    assertEquals(s.ancestors(3), Set(1, 2, 0)) // both branches converge on the common ancestor 0
  }

  test("RoundRobin picks one component per call and cycles through, wrapping the pointer") {
    val cs = Vector(OptimizableId(0), OptimizableId(1), OptimizableId(2))
    // Thread the pointer through six calls; expect a, b, c, a, b, c.
    val (picks, _) = (0 until 6).foldLeft((Vector.empty[OptimizableId], 0)) { case ((acc, ptr), _) =>
      val (chosen, next) = ComponentSelector.RoundRobin.select(cs, ptr)
      (acc :+ chosen.head, next)
    }
    assertEquals(
      picks,
      Vector(0, 1, 2, 0, 1, 2).map(i => OptimizableId.fromOrdinal(OptimizableOrdinal.assume(i)))
    )
  }

  test("RoundRobin normalizes an out-of-range pointer") {
    // A pointer past the end (e.g. after the component list shrank) wraps via modulo.
    assertEquals(
      ComponentSelector.RoundRobin.select(Vector(OptimizableId(0), OptimizableId(1)), 5),
      (Vector(OptimizableId(1)), 0)
    )
  }

  test("RoundRobin on no components is a no-op that preserves the pointer") {
    assertEquals(
      ComponentSelector.RoundRobin.select(Vector.empty, 3),
      (Vector.empty[OptimizableId], 3)
    )
  }

  test("All returns every component and leaves the pointer untouched") {
    val cs = Vector(OptimizableId(0), OptimizableId(1), OptimizableId(2))
    assertEquals(ComponentSelector.All.select(cs, 2), (cs, 2))
  }

  // ── Dominated candidates (PORT_GAPS G-44; gepa `remove_dominated_programs`) ───────────────────────────────────────
  // A candidate is dominated when each front that holds it also holds a different candidate that stays.

  test("Pareto selection excludes a candidate that shares its only front with a stronger candidate") {
    // Front 0 holds c0 and c1. Front 1 holds c1 only. c1 covers each front that holds c0.
    val s     = state(Vector(1.0, 0.0), Vector(1.0, 1.0))
    val rng   = new Random(7)
    val picks = (0 until 200).map(_ => CandidateSelector.Pareto.select(s, rng)).toSet
    assertEquals(picks, Set(1))
  }

  test("Pareto selection excludes two specialists when one candidate covers the two fronts") {
    // Front 0 holds c0 and c2. Front 1 holds c1 and c2. c2 covers the fronts of c0 and of c1.
    val s     = state(Vector(1.0, 0.0), Vector(0.0, 1.0), Vector(1.0, 1.0))
    val rng   = new Random(7)
    val picks = (0 until 200).map(_ => CandidateSelector.Pareto.select(s, rng)).toSet
    assertEquals(picks, Set(2))
  }

  test("Pareto selection keeps two specialists when no candidate covers them") {
    val s     = state(Vector(1.0, 0.0), Vector(0.0, 1.0), Vector(0.5, 0.5))
    val rng   = new Random(7)
    val picks = (0 until 200).map(_ => CandidateSelector.Pareto.select(s, rng)).toSet
    assertEquals(picks, Set(0, 1))
  }

  test("Pareto selection keeps exactly one of two identical candidates") {
    val s     = state(Vector(1.0, 1.0), Vector(1.0, 1.0))
    val rng   = new Random(7)
    val picks = (0 until 200).map(_ => CandidateSelector.Pareto.select(s, rng)).toSet
    assertEquals(picks.size, 1)
  }

  test("nonDominatedFrontier restricts each front to the candidates that stay") {
    val s = state(Vector(1.0, 0.0), Vector(0.0, 1.0), Vector(1.0, 1.0))
    assertEquals(s.paretoFrontier, Map(0 -> Set(0, 2), 1 -> Set(1, 2)))
    assertEquals(s.nonDominatedFrontier, Map(0 -> Set(2), 1 -> Set(2)))
  }

  test("removal examines candidates with equal scores in the order of their first appearance in the fronts") {
    // The three aggregate scores are equal and each front holds two candidates. Each candidate is dominated until one
    // candidate leaves. Front 0 lists c1 first, so c1 leaves. The pinned gepa package (0.1.4) gives the same result.
    val s = state(Vector(0.0, 1.0, 1.0), Vector(1.0, 1.0, 0.0), Vector(1.0, 0.0, 1.0))
    assertEquals(s.paretoFrontier, Map(0 -> Set(1, 2), 1 -> Set(0, 1), 2 -> Set(0, 2)))
    assertEquals(s.nonDominatedFrontier, Map(0 -> Set(2), 1 -> Set(0), 2 -> Set(0, 2)))
  }

  test("removal examines a candidate with a lower score first") {
    // c0 and c1 cover each other on front 0. c0 has the lower aggregate score, so c0 leaves and c1 stays.
    val fronts = Map(0 -> Set(0, 1))
    assertEquals(GepaState.removeDominated(fronts, Map(0 -> 0.2, 1 -> 0.8)), Map(0 -> Set(1)))
    assertEquals(GepaState.removeDominated(fronts, Map(0 -> 0.8, 1 -> 0.2)), Map(0 -> Set(0)))
  }

  test("Pareto selection falls back to a uniform draw when the state has no frontier") {
    val s = state(Vector.empty, Vector.empty) // no validation instance
    assertEquals(s.nonDominatedFrontier, Map.empty[Int, Set[Int]])
    val rng   = new Random(7)
    val picks = (0 until 200).map(_ => CandidateSelector.Pareto.select(s, rng)).toSet
    assertEquals(picks, Set(0, 1))
  }
