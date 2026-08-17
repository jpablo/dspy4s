package dspy4s.gepa

import dspy4s.programs.ParameterId
import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class GepaStatePersistenceSuite extends FunSuite:

  test("toJson/fromJson round-trips a state (candidates, subscores, multi-parent lineage, call meter)") {
    val state = GepaState(
      candidates = CandidatePool.applyUnsafe(Vector(
        Map(ParameterId("p0") -> Some("x"), ParameterId("p1") -> Some("y")),
        Map(ParameterId("p0") -> Some("z"), ParameterId("p1") -> Some("w")),
        Map(ParameterId("p0") -> Some("p"), ParameterId("p1") -> Some("q"))
      )),
      valSubscores = Vector(Vector(1.0, 0.5), Vector(0.0, 1.0), Vector(1.0, 1.0)),
      parents = Vector(Vector.empty, Vector(0), Vector(0, 1)), // includes a two-parent (merge) node
      totalMetricCalls = MetricCallCount(17)
    )
    val restored = GepaStatePersistence.fromJson(GepaStatePersistence.toJson(state)).toOption.get
    assertEquals(restored.candidates, state.candidates)
    assertEquals(restored.valSubscores, state.valSubscores)
    assertEquals(restored.parents, state.parents)
    assertEquals(restored.totalMetricCalls, state.totalMetricCalls)
  }

  test("checkpoint round-trip distinguishes absent from explicitly empty instructions") {
    val state = GepaState(
      candidates = CandidatePool.applyUnsafe(Vector(Map(ParameterId("p0") -> None, ParameterId("p1") -> Some("")))),
      valSubscores = Vector(Vector(1.0)),
      parents = Vector(Vector.empty),
      totalMetricCalls = MetricCallCount(1)
    )

    val restored = GepaStatePersistence.fromJson(GepaStatePersistence.toJson(state)).toOption.get
    assertEquals(restored.candidates, state.candidates)
  }

  test("fromJson rejects malformed input") {
    assert(GepaStatePersistence.fromJson("not json at all").isLeft)
  }

  test("fromJson rejects empty parameter IDs") {
    val valid = GepaState(
      candidates = CandidatePool.applyUnsafe(Vector(Map(ParameterId("p0") -> Some("x")))),
      valSubscores = Vector(Vector(1.0)),
      parents = Vector(Vector.empty),
      totalMetricCalls = MetricCallCount(1)
    )
    val malformed = GepaStatePersistence.toJson(valid).replace("\"p0\"", "\" \"")
    assert(GepaStatePersistence.fromJson(malformed).isLeft)
  }

  test("load distinguishes an absent checkpoint from a corrupt checkpoint") {
    val dir = Files.createTempDirectory("gepa-state-load")
    try
      assertEquals(GepaStatePersistence.load(dir), Right(None))

      val _      = Files.write(dir.resolve(GepaStatePersistence.fileName), "not json".getBytes(StandardCharsets.UTF_8))
      val loaded = GepaStatePersistence.load(dir)
      assert(loaded.isLeft, s"expected corrupt checkpoint to be reported, got $loaded")
    finally
      val _ = Files.deleteIfExists(dir.resolve(GepaStatePersistence.fileName))
      val _ = Files.deleteIfExists(dir)
  }
