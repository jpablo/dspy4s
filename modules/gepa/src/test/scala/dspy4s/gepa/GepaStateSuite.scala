package dspy4s.gepa

import munit.FunSuite

import scala.util.Random

class GepaStateSuite extends FunSuite:

  private def state(subscores: Vector[Double]*): GepaState =
    GepaState(
      candidates = subscores.indices.map(i => Map("0" -> s"instr$i")).toVector,
      valSubscores = subscores.toVector,
      parents = subscores.indices.map(_ => Option.empty[Int]).toVector,
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
    val s1 = s0.add(Map("0" -> "child"), Vector(1.0, 1.0), parent = Some(0), metricCalls = 2)
    assertEquals(s1.candidates.size, 2)
    assertEquals(s1.bestIndex, 1)
    assertEquals(s1.parents(1), Some(0))
    assertEquals(s1.totalMetricCalls, 4)
  }
