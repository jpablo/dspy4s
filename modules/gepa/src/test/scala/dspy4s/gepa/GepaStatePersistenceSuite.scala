package dspy4s.gepa

import munit.FunSuite

class GepaStatePersistenceSuite extends FunSuite:

  test("toJson/fromJson round-trips a state (candidates, subscores, multi-parent lineage, call meter)") {
    val state = GepaState(
      candidates   = Vector(Map("a" -> "x", "b" -> "y"), Map("a" -> "z", "b" -> "w"), Map("a" -> "p", "b" -> "q")),
      valSubscores = Vector(Vector(1.0, 0.5), Vector(0.0, 1.0), Vector(1.0, 1.0)),
      parents      = Vector(Vector.empty, Vector(0), Vector(0, 1)), // includes a two-parent (merge) node
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
