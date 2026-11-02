package dspy4s.gepa

import dspy4s.programs.optimization.OptimizableId
import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class GepaStatePersistenceSuite extends FunSuite:

  test("toJson/fromJson round-trips a state (candidates, subscores, multi-parent lineage, call meter)") {
    val state = GepaStateFixtures.state(
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
    val state = GepaStateFixtures.state(
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
    val valid = GepaStateFixtures.state(
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
      s"""{"candidates":[{"optimizable-0":"x"}],"valSubscores":[[1.0]],"parents":[[]],"totalMetricCalls":1,"nextComponent":$pointers,"valObjectiveScores":[[{}]],"discoveryCalls":[0]}"""
    assertEquals(GepaStatePersistence.fromJson(snapshot("[1]")).map(_.nextComponent), Right(Vector(1)))
    assert(GepaStatePersistence.fromJson(snapshot("[-1]")).isLeft)
    assert(GepaStatePersistence.fromJson(snapshot("[0,0]")).isLeft)
  }

  test("fromJson rejects a merge triplet with two indices, a negative iteration, and unaligned discovery counts") {
    def snapshot(fields: String, discoveryCalls: String = "[0]"): String =
      s"""{"candidates":[{"optimizable-0":"x"}],"valSubscores":[[1.0]],"parents":[[]],"totalMetricCalls":1,"nextComponent":[0],"valObjectiveScores":[[{}]],"discoveryCalls":$discoveryCalls,$fields}"""
    val merge = """"merge":{"mergesDue":1,"totalMergesTested":0,"lastIterFoundNewProgram":true,"performedTriplets":"""
    assertEquals(
      GepaStatePersistence.fromJson(snapshot(merge + "[[1,2,0]]}")).map(_.merge.performedTriplets),
      Right(Set((1, 2, 0)))
    )
    assert(GepaStatePersistence.fromJson(snapshot(merge + "[[1,2]]}")).isLeft)
    assert(GepaStatePersistence.fromJson(snapshot(""""iteration":-1""")).isLeft)
    assert(GepaStatePersistence.fromJson(snapshot(""""iteration":1""", discoveryCalls = "[0,4]")).isLeft)
  }

  test("fromJson rejects a checkpoint with no pointers, no objective rows, or no discovery counts") {
    val pool                              = """{"candidates":[{"optimizable-0":"x"}],"valSubscores":[[1.0]],"parents":[[]],"totalMetricCalls":1"""
    val nextComponent                     = """"nextComponent":[0]"""
    val valObjectiveScores                = """"valObjectiveScores":[[{}]]"""
    val discoveryCalls                    = """"discoveryCalls":[0]"""
    def snapshot(fields: String*): String = fields.map("," + _).mkString(pool, "", "}")
    assert(GepaStatePersistence.fromJson(snapshot(nextComponent, valObjectiveScores, discoveryCalls)).isRight)
    assert(GepaStatePersistence.fromJson(snapshot(valObjectiveScores, discoveryCalls)).isLeft)
    assert(GepaStatePersistence.fromJson(snapshot(nextComponent, discoveryCalls)).isLeft)
    assert(GepaStatePersistence.fromJson(snapshot(nextComponent, valObjectiveScores)).isLeft)
  }

  test("a field with its default is not in the JSON text, and an absent field reads as its default") {
    val state = GepaState.seed(Map(OptimizableId(0) -> Some("x")), Vector(0.5), metricCalls = 1)
    val json  = GepaStatePersistence.toJson(state)
    assertEquals(
      json,
      """{"candidates":[{"optimizable-0":"x"}],"valSubscores":[[0.5]],"parents":[[]],"totalMetricCalls":1,""" +
        """"nextComponent":[0],"valObjectiveScores":[[{}]],"discoveryCalls":[0]}"""
    )
    assertEquals(GepaStatePersistence.fromJson(json), Right(state))
  }

  test("a text that is not ASCII stays the same in the file") {
    val state = GepaState.seed(Map(OptimizableId(0) -> Some("é猫🙂 \"q\"\n")), Vector(0.5), metricCalls = 1)
    val dir   = Files.createTempDirectory("gepa-state-text")
    try
      GepaStatePersistence.save(dir, state)
      assertEquals(GepaStatePersistence.load(dir), Right(Some(state)))
    finally
      val _ = Files.deleteIfExists(dir.resolve(GepaStatePersistence.fileName))
      val _ = Files.deleteIfExists(dir)
  }

  test("the file that save writes has the JSON form of the typed codec, and load reads it") {
    val state = GepaState
      .seed(
        Map(OptimizableId(0) -> Some("x")),
        Vector(0.5, 1.0),
        metricCalls = 2,
        objectiveScores = Vector(Map("quality" -> 1.0), Map.empty),
        frontierType = FrontierType.Hybrid
      )
      .copy(
        iteration = 3,
        merge = MergeSchedule(
          mergesDue = 1,
          totalMergesTested = 2,
          lastIterFoundNewProgram = true,
          performedTriplets = Set((1, 2, 0)),
          performedDescriptions = Set((1, 2, Vector(0, 1)))
        ),
        failedProposals = 4
      )
    val dir = Files.createTempDirectory("gepa-state-form")
    try
      GepaStatePersistence.save(dir, state)
      assertEquals(
        Files.readString(dir.resolve(GepaStatePersistence.fileName)),
        """{"candidates":[{"optimizable-0":"x"}],"valSubscores":[[0.5,1.0]],"parents":[[]],"totalMetricCalls":2,""" +
          """"nextComponent":[0],"valObjectiveScores":[[{"quality":1.0},{}]],"discoveryCalls":[0],""" +
          """"frontierType":"Hybrid","iteration":3,"merge":{"mergesDue":1,"totalMergesTested":2,""" +
          """"lastIterFoundNewProgram":true,"performedTriplets":[[1,2,0]],""" +
          """"performedDescriptions":[{"first":1,"second":2,"sources":[0,1]}]},"failedProposals":4}"""
      )
      assertEquals(GepaStatePersistence.load(dir), Right(Some(state)))
    finally
      val _ = Files.deleteIfExists(dir.resolve(GepaStatePersistence.fileName))
      val _ = Files.deleteIfExists(dir)
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
