package dspy4s.gepa

import dspy4s.programs.optimization.OptimizableId
import munit.FunSuite

import scala.util.Random

/** The objective scores of a candidate and the objective-aware Pareto fronts of the search state (PORT_GAPS G-45, item
  * O4; upstream 638e155cf, gepa 0.1.4 `core/state.py` and `strategies/candidate_selector.py`, and
  * `tests/teleprompt/test_gepa.py` `test_multi_objective_frontier_results`).
  */
class GepaObjectiveFrontierSuite extends FunSuite:

  private val id = OptimizableId(0)

  private def candidate(text: String): Candidate = Map(id -> Some(text))

  /** The score and the objective scores of one candidate on one validation example. */
  private type Cell = (Double, Map[String, Double])

  /** A state with one candidate for each row. A row holds one cell for each validation example. Candidate 0 is the
    * seed, and it is the parent of each other candidate.
    */
  private def state(frontierType: FrontierType)(rows: Vector[Cell]*): GepaState =
    val seed = GepaState.seed(
      candidate("c0"),
      rows.head.map(_._1),
      metricCalls = 0,
      objectiveScores = rows.head.map(_._2),
      frontierType = frontierType
    )
    rows.zipWithIndex.drop(1).foldLeft(seed) { case (current, (row, i)) =>
      current.add(
        candidate(s"c$i"),
        row.map(_._1),
        parents = Vector(0),
        metricCalls = 0,
        objectiveScores = row.map(_._2)
      )
    }

  /** The upstream test metric: `score = (correct + 1) / 2`, `correctness = correct`, `incorrectness = 1 - correct`. */
  private def graded(correct: Boolean): Cell =
    val value = if correct then 1.0 else 0.0
    ((value + 1) / 2, Map("correctness" -> value, "incorrectness" -> (1 - value)))

  /** Candidate 0 is wrong on the two examples. Candidate 1 is right on the first example only. */
  private def twoCandidates(frontierType: FrontierType): GepaState =
    state(frontierType)(Vector(graded(false), graded(false)), Vector(graded(true), graded(false)))

  private def draws(from: GepaState): Set[Int] =
    val rng = new Random(0L)
    (1 to 60).map(_ => CandidateSelector.Pareto.select(from, rng)).toSet

  import FrontierKey.{Cartesian, Instance, Objective}

  // ── The objective scores of a candidate ─────────────────────────────────────────────────────────────────────

  test("the aggregate of an objective is its mean over the validation examples") {
    val s = twoCandidates(FrontierType.Instance)
    assertEquals(s.objectiveAggregates(0), Map("correctness" -> 0.0, "incorrectness" -> 1.0))
    assertEquals(s.objectiveAggregates(1), Map("correctness" -> 0.5, "incorrectness" -> 0.5))
    assertEquals(s.objectiveNames, Vector("correctness", "incorrectness"))
  }

  test("the best candidates and the best value of each objective are those of the upstream test") {
    val s = twoCandidates(FrontierType.Instance)
    assertEquals(s.objectiveBest, Map("correctness" -> Set(1), "incorrectness" -> Set(0)))
    assertEquals(s.objectiveFront, Map("correctness" -> 0.5, "incorrectness" -> 1.0))
  }

  test("an example with no objective score does not count in the mean") {
    val sparse = state(FrontierType.Instance)(Vector((0.75, Map("quality" -> 1.0, "cost" -> 0.5)), (0.25, Map.empty)))
    assertEquals(sparse.objectiveAggregates(0), Map("quality" -> 1.0, "cost" -> 0.5))
    assertEquals(
      sparse.objectiveScoresOf(0),
      Vector(Map("quality" -> 1.0, "cost" -> 0.5), Map.empty[String, Double])
    )

    val partial =
      state(FrontierType.Instance)(Vector((1.0, Map("quality" -> 1.0)), (0.0, Map("quality" -> 0.0, "cost" -> 0.4))))
    assertEquals(partial.objectiveAggregates(0), Map("quality" -> 0.5, "cost" -> 0.4))
    assertEquals(partial.objectiveNames, Vector("cost", "quality"))
  }

  test("candidates with equal aggregates share the front of the objective") {
    val s = state(FrontierType.Objective)(
      Vector((0.5, Map("quality" -> 0.5))),
      Vector((0.5, Map("quality" -> 0.5))),
      Vector((0.5, Map("quality" -> 0.2)))
    )
    assertEquals(s.objectiveBest, Map("quality" -> Set(0, 1)))
    assertEquals(s.objectiveFront, Map("quality" -> 0.5))
  }

  test("an objective that only a later candidate has gets a front") {
    val s = state(FrontierType.Objective)(
      Vector((0.5, Map("accuracy" -> 0.5))),
      Vector((0.5, Map("accuracy" -> 0.4, "brevity" -> 0.9)))
    )
    assertEquals(s.objectiveNames, Vector("accuracy", "brevity"))
    assertEquals(s.objectiveBest, Map("accuracy" -> Set(0), "brevity" -> Set(1)))
    assertEquals(
      s.fronts,
      Vector[(FrontierKey, Set[Int])](Objective("accuracy") -> Set(0), Objective("brevity") -> Set(1))
    )
  }

  // ── The forms of the objective data ─────────────────────────────────────────────────────────────────────────

  test("a state with no objective score has no objective data") {
    val seed = GepaState.seed(candidate("c0"), Vector(0.5, 0.5), metricCalls = 2)
    assertEquals(seed.valObjectiveScores, Vector.empty)
    assertEquals(
      seed,
      GepaState(
        CandidatePool.applyUnsafe(Vector(candidate("c0"))),
        Vector(Vector(0.5, 0.5)),
        Vector(Vector.empty),
        MetricCallCount(2),
        // The seed has the discovery count 0 (batch 9).
        discoveryCalls = Vector(0)
      )
    )

    val grown = seed.add(candidate("c1"), Vector(1.0, 0.5), parents = Vector(0), metricCalls = 2)
    assertEquals(grown.valObjectiveScores, Vector.empty)
    assertEquals(grown.objectiveScoresOf(1), Vector(Map.empty[String, Double], Map.empty[String, Double]))
    assertEquals(grown.objectiveAggregates(1), Map.empty[String, Double])
    assertEquals(grown.objectiveNames, Vector.empty)
    assertEquals(grown.objectiveBest, Map.empty[String, Set[Int]])
    assertEquals(grown.objectiveFront, Map.empty[String, Double])
  }

  test("the first objective score gives each candidate a row") {
    val grown = GepaState.seed(candidate("c0"), Vector(0.5, 0.5), metricCalls = 2)
      .add(candidate("c1"), Vector(1.0, 0.5), Vector(0), 2, Vector(Map("quality" -> 1.0), Map.empty))
    assertEquals(
      grown.valObjectiveScores,
      Vector(
        Vector(Map.empty[String, Double], Map.empty[String, Double]),
        Vector(Map("quality" -> 1.0), Map.empty[String, Double])
      )
    )
    assertEquals(grown.objectiveBest, Map("quality" -> Set(1)))
  }

  test("objective scores that are not aligned with the scores are rejected") {
    val _ = intercept[IllegalArgumentException](
      GepaState.seed(candidate("c0"), Vector(0.5, 0.5), metricCalls = 2, objectiveScores = Vector(Map("q" -> 1.0)))
    )
    val seed = GepaState.seed(candidate("c0"), Vector(0.5, 0.5), metricCalls = 2)
    val _    = intercept[IllegalArgumentException](
      seed.add(candidate("c1"), Vector(1.0, 0.5), Vector(0), 2, Vector(Map("q" -> 1.0)))
    )
    intercept[IllegalArgumentException](seed.copy(valObjectiveScores = Vector(Vector(Map("q" -> 1.0)))))
  }

  // ── The fronts of each frontier type ────────────────────────────────────────────────────────────────────────

  test("the instance type has one front for each validation example, and it is the default") {
    val s = twoCandidates(FrontierType.Instance)
    assertEquals(s.fronts, Vector[(FrontierKey, Set[Int])](Instance(0) -> Set(1), Instance(1) -> Set(0, 1)))
    assertEquals(s.frontier, s.paretoFrontier)
    assertEquals(GepaState.seed(candidate("c0"), Vector(0.5), metricCalls = 1).frontierType, FrontierType.Instance)
  }

  test("the objective type has one front for each objective") {
    val s = twoCandidates(FrontierType.Objective)
    assertEquals(
      s.fronts,
      Vector[(FrontierKey, Set[Int])](Objective("correctness") -> Set(1), Objective("incorrectness") -> Set(0))
    )
    assertEquals(s.frontier, Map(0 -> Set(1), 1 -> Set(0)))
  }

  test("the hybrid type has the instance fronts and then the objective fronts") {
    val s = twoCandidates(FrontierType.Hybrid)
    assertEquals(
      s.fronts,
      Vector[(FrontierKey, Set[Int])](
        Instance(0)                -> Set(1),
        Instance(1)                -> Set(0, 1),
        Objective("correctness")   -> Set(1),
        Objective("incorrectness") -> Set(0)
      )
    )
    assertEquals(s.frontier, Map(0 -> Set(1), 1 -> Set(0, 1), 2 -> Set(1), 3 -> Set(0)))
  }

  test("the cartesian type has one front for each pair of an example and an objective") {
    val s = twoCandidates(FrontierType.Cartesian)
    assertEquals(
      s.fronts,
      Vector[(FrontierKey, Set[Int])](
        Cartesian(0, "correctness")   -> Set(1),
        Cartesian(0, "incorrectness") -> Set(0),
        Cartesian(1, "correctness")   -> Set(0, 1),
        Cartesian(1, "incorrectness") -> Set(0, 1)
      )
    )
    assertEquals(s.frontier, Map(0 -> Set(1), 1 -> Set(0), 2 -> Set(0, 1), 3 -> Set(0, 1)))
  }

  test("a cartesian pair that no candidate has a score for has no front") {
    val s = state(FrontierType.Cartesian)(
      Vector((0.5, Map("quality" -> 0.5)), (0.5, Map.empty)),
      Vector((0.5, Map("quality" -> 0.7)), (0.5, Map("cost" -> 0.1)))
    )
    assertEquals(
      s.fronts,
      Vector[(FrontierKey, Set[Int])](Cartesian(0, "quality") -> Set(1), Cartesian(1, "cost") -> Set(1))
    )
  }

  test("a candidate with no objective score is in no objective front and in no cartesian front") {
    def withSilent(frontierType: FrontierType): GepaState =
      twoCandidates(frontierType).add(candidate("c2"), Vector(1.0, 1.0), parents = Vector(1), metricCalls = 2)

    val objective = withSilent(FrontierType.Objective)
    assertEquals(objective.objectiveAggregates(2), Map.empty[String, Double])
    assertEquals(objective.objectiveScoresOf(2), Vector(Map.empty[String, Double], Map.empty[String, Double]))
    assertEquals(objective.objectiveBest, Map("correctness" -> Set(1), "incorrectness" -> Set(0)))
    assertEquals(objective.fronts, twoCandidates(FrontierType.Objective).fronts)
    assertEquals(withSilent(FrontierType.Cartesian).fronts, twoCandidates(FrontierType.Cartesian).fronts)

    // The instance fronts of the hybrid type use the score, so the candidate is in them.
    assertEquals(
      withSilent(FrontierType.Hybrid).fronts.take(2),
      Vector[(FrontierKey, Set[Int])](Instance(0) -> Set(1, 2), Instance(1) -> Set(2))
    )
  }

  // ── Selection ───────────────────────────────────────────────────────────────────────────────────────────────

  test("the instance type removes the dominated candidate, and the objective type keeps the two candidates") {
    // Candidate 0 is in one instance front only, with candidate 1, which has the larger mean score.
    assertEquals(twoCandidates(FrontierType.Instance).nonDominatedFrontier, Map(0 -> Set(1), 1 -> Set(1)))
    assertEquals(draws(twoCandidates(FrontierType.Instance)), Set(1))

    // Candidate 0 is alone in the front of `incorrectness`.
    assertEquals(twoCandidates(FrontierType.Objective).nonDominatedFrontier, Map(0 -> Set(1), 1 -> Set(0)))
    assertEquals(draws(twoCandidates(FrontierType.Objective)), Set(0, 1))
  }

  test("the hybrid type and the cartesian type keep a candidate that is alone in one front") {
    assertEquals(
      twoCandidates(FrontierType.Hybrid).nonDominatedFrontier,
      Map(0 -> Set(1), 1 -> Set(0, 1), 2 -> Set(1), 3 -> Set(0))
    )
    assertEquals(draws(twoCandidates(FrontierType.Hybrid)), Set(0, 1))
    assertEquals(
      twoCandidates(FrontierType.Cartesian).nonDominatedFrontier,
      Map(0 -> Set(1), 1 -> Set(0), 2 -> Set(0, 1), 3 -> Set(0, 1))
    )
    assertEquals(draws(twoCandidates(FrontierType.Cartesian)), Set(0, 1))
  }

  test("the best candidate uses the score only") {
    // Candidate 0 has the better objective scores and the lower score.
    val s = state(FrontierType.Objective)(
      Vector((0.2, Map("quality" -> 1.0)), (0.2, Map("quality" -> 1.0))),
      Vector((0.9, Map("quality" -> 0.1)), (0.9, Map("quality" -> 0.1)))
    )
    assertEquals(s.bestIndex, 1)
    assertEquals(CandidateSelector.CurrentBest.select(s, new Random(0L)), 1)
    assertEquals(s.objectiveBest, Map("quality" -> Set(0)))
  }

  // ── Persistence ─────────────────────────────────────────────────────────────────────────────────────────────

  test("a saved state keeps the objective scores and the frontier type") {
    FrontierType.values.foreach { frontierType =>
      val original = twoCandidates(frontierType)
      val restored = GepaStatePersistence.fromJson(GepaStatePersistence.toJson(original))
      assertEquals(restored, Right(original))
      assertEquals(restored.map(_.frontierType), Right(frontierType))
      assertEquals(restored.map(_.objectiveAggregates(1)), Right(Map("correctness" -> 0.5, "incorrectness" -> 0.5)))
    }
  }

  test("a state that an older version saved has the instance type and no objective data") {
    val older =
      """{"candidates":[{"optimizable-0":"c0"}],"valSubscores":[[0.5,0.5]],"parents":[[]],"totalMetricCalls":2}"""
    val restored = GepaStatePersistence.fromJson(older)
    assertEquals(restored.map(_.frontierType), Right(FrontierType.Instance))
    assertEquals(restored.map(_.valObjectiveScores), Right(Vector.empty))
    // An older snapshot has no discovery counts.
    assertEquals(
      restored,
      Right(GepaState.seed(candidate("c0"), Vector(0.5, 0.5), metricCalls = 2).copy(discoveryCalls = Vector.empty))
    )
  }

  test("a saved state with a frontier type that does not exist is rejected") {
    val unknown =
      """{"candidates":[{"optimizable-0":"c0"}],"valSubscores":[[0.5]],"parents":[[]],"totalMetricCalls":1,"frontierType":"Lexicographic"}"""
    assert(GepaStatePersistence.fromJson(unknown).isLeft)
  }

  test("a frontier type has a name that parses back") {
    FrontierType.values.foreach(frontierType =>
      assertEquals(FrontierType.parse(frontierType.toString), Some(frontierType))
    )
    assertEquals(FrontierType.parse("instance"), None)
  }
