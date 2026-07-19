package dspy4s.gepa

import dspy4s.programs.PredictorId
import munit.FunSuite

class GepaStatePersistenceSuite extends FunSuite:

  test("toJson/fromJson round-trips a state (candidates, subscores, multi-parent lineage, call meter)") {
    val state = GepaState(
      candidates = Vector(
        Map(PredictorId(0) -> "x", PredictorId(1) -> "y"),
        Map(PredictorId(0) -> "z", PredictorId(1) -> "w"),
        Map(PredictorId(0) -> "p", PredictorId(1) -> "q")
      ),
      valSubscores = Vector(Vector(1.0, 0.5), Vector(0.0, 1.0), Vector(1.0, 1.0)),
      parents = Vector(Vector.empty, Vector(0), Vector(0, 1)), // includes a two-parent (merge) node
      totalMetricCalls = 17
    )
    val restored = GepaStatePersistence.fromJson(GepaStatePersistence.toJson(state)).toOption.get
    assertEquals(restored.candidates, state.candidates)
    assertEquals(restored.valSubscores, state.valSubscores)
    assertEquals(restored.parents, state.parents)
    assertEquals(restored.totalMetricCalls, state.totalMetricCalls)
  }

  test("fromJson rejects malformed input") {
    assert(GepaStatePersistence.fromJson("not json at all").isLeft)
  }

  test("fromJson rejects malformed predictor IDs") {
    val valid = GepaState(
      candidates = Vector(Map(PredictorId(0) -> "x")),
      valSubscores = Vector(Vector(1.0)),
      parents = Vector(Vector.empty),
      totalMetricCalls = 1
    )
    val malformed = GepaStatePersistence.toJson(valid).replace(PredictorId(0).render, "display-name")
    assert(GepaStatePersistence.fromJson(malformed).isLeft)
  }
