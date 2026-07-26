package dspy4s.gepa

import dspy4s.programs.predictors.PredictorId
import dspy4s.programs.predictors.PredictorOrdinal
import munit.FunSuite

import scala.util.Random

class GepaStateSuite extends FunSuite:

  private def state(subscores: Vector[Double]*): GepaState =
    GepaState(
      candidates = CandidatePool.applyUnsafe(
        subscores.indices.map(i => Map(PredictorId(0) -> Some(s"instr$i"))).toVector
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
          Vector(Map(PredictorId(0) -> Some("a")), Map(PredictorId(0) -> Some("b")))
        ),
        valSubscores = Vector(Vector(1.0, 0.0), Vector(1.0)), // second row is shorter — would crash paretoFrontier
        parents = Vector(Vector.empty[Int], Vector.empty[Int]),
        totalMetricCalls = MetricCallCount(0)
      )
    }
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
    val s0 = GepaState.seed(Map(PredictorId(0) -> Some("seed")), Vector(0.5, 0.5), metricCalls = 2)
    val s1 = s0.add(Map(PredictorId(0) -> Some("child")), Vector(1.0, 1.0), parents = Vector(0), metricCalls = 2)
    assertEquals(s1.candidates.size, 2)
    assertEquals(s1.bestIndex, 1)
    assertEquals(s1.parents(1), Vector(0))
    assertEquals(s1.totalMetricCalls, MetricCallCount(4))
  }

  test("ancestors walks the full lineage, including a merge's two branches up to a common ancestor") {
    // Lineage: 0 (seed) -> 1, 0 -> 2; then 3 is a MERGE of 1 and 2 (two parents).
    val s = GepaState(
      candidates = CandidatePool.applyUnsafe(Vector.tabulate(4)(i => Map(PredictorId(0) -> Some(i.toString)))),
      valSubscores = Vector.fill(4)(Vector(1.0)),
      parents = Vector(Vector.empty, Vector(0), Vector(0), Vector(1, 2)),
      totalMetricCalls = MetricCallCount(0)
    )
    assertEquals(s.ancestors(0), Set.empty[Int])
    assertEquals(s.ancestors(1), Set(0))
    assertEquals(s.ancestors(3), Set(1, 2, 0)) // both branches converge on the common ancestor 0
  }

  test("RoundRobin picks one component per call and cycles through, wrapping the pointer") {
    val cs = Vector(PredictorId(0), PredictorId(1), PredictorId(2))
    // Thread the pointer through six calls; expect a, b, c, a, b, c.
    val (picks, _) = (0 until 6).foldLeft((Vector.empty[PredictorId], 0)) { case ((acc, ptr), _) =>
      val (chosen, next) = ComponentSelector.RoundRobin.select(cs, ptr)
      (acc :+ chosen.head, next)
    }
    assertEquals(
      picks,
      Vector(0, 1, 2, 0, 1, 2).map(i => PredictorId.fromOrdinal(PredictorOrdinal.assume(i)))
    )
  }

  test("RoundRobin normalizes an out-of-range pointer") {
    // A pointer past the end (e.g. after the component list shrank) wraps via modulo.
    assertEquals(
      ComponentSelector.RoundRobin.select(Vector(PredictorId(0), PredictorId(1)), 5),
      (Vector(PredictorId(1)), 0)
    )
  }

  test("RoundRobin on no components is a no-op that preserves the pointer") {
    assertEquals(
      ComponentSelector.RoundRobin.select(Vector.empty, 3),
      (Vector.empty[PredictorId], 3)
    )
  }

  test("All returns every component and leaves the pointer untouched") {
    val cs = Vector(PredictorId(0), PredictorId(1), PredictorId(2))
    assertEquals(ComponentSelector.All.select(cs, 2), (cs, 2))
  }
