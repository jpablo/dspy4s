package dspy4s.gepa

import dspy4s.programs.optimization.OptimizableId
import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class GepaStatePersistenceSuite extends FunSuite:

  test("toJson/fromJson round-trips a state (candidates, subscores, multi-parent lineage, call meter)") {
    val state = GepaState(
      candidates = CandidatePool.applyUnsafe(Vector(
        Map(OptimizableId(0) -> Some("x"), OptimizableId(1) -> Some("y")),
        Map(OptimizableId(0) -> Some("z"), OptimizableId(1) -> Some("w")),
        Map(OptimizableId(0) -> Some("p"), OptimizableId(1) -> Some("q"))
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
      candidates = CandidatePool.applyUnsafe(Vector(Map(OptimizableId(0) -> None, OptimizableId(1) -> Some("")))),
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

  test("fromJson rejects malformed optimizable IDs") {
    val valid = GepaState(
      candidates = CandidatePool.applyUnsafe(Vector(Map(OptimizableId(0) -> Some("x")))),
      valSubscores = Vector(Vector(1.0)),
      parents = Vector(Vector.empty),
      totalMetricCalls = MetricCallCount(1)
    )
    val malformed = GepaStatePersistence.toJson(valid).replace(OptimizableId(0).render, "display-name")
    assert(GepaStatePersistence.fromJson(malformed).isLeft)
  }

  test("fromJson rejects pointers that are negative or not aligned with the candidates") {
    def snapshot(pointers: String): String =
      s"""{"candidates":[{"optimizable-0":"x"}],"valSubscores":[[1.0]],"parents":[[]],"totalMetricCalls":1,"nextComponent":$pointers}"""
    assertEquals(GepaStatePersistence.fromJson(snapshot("[1]")).map(_.pointerOf(0)), Right(1))
    assert(GepaStatePersistence.fromJson(snapshot("[-1]")).isLeft)
    assert(GepaStatePersistence.fromJson(snapshot("[0,0]")).isLeft)
  }

  test("fromJson rejects a merge triplet with two indices, a negative iteration, and unaligned discovery counts") {
    def snapshot(fields: String): String =
      s"""{"candidates":[{"optimizable-0":"x"}],"valSubscores":[[1.0]],"parents":[[]],"totalMetricCalls":1,$fields}"""
    val merge = """"merge":{"mergesDue":1,"totalMergesTested":0,"lastIterFoundNewProgram":true,"performedTriplets":"""
    assertEquals(
      GepaStatePersistence.fromJson(snapshot(merge + "[[1,2,0]]}")).map(_.merge.performedTriplets),
      Right(Set((1, 2, 0)))
    )
    assert(GepaStatePersistence.fromJson(snapshot(merge + "[[1,2]]}")).isLeft)
    assert(GepaStatePersistence.fromJson(snapshot(""""iteration":-1""")).isLeft)
    assert(GepaStatePersistence.fromJson(snapshot(""""discoveryCalls":[0,4]""")).isLeft)
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
