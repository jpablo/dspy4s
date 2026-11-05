package dspy4s.gepa

/** Builds a [[GepaState]] for a test that gives only the pool of candidates. */
object GepaStateFixtures:

  /** A state in which each candidate has the round-robin pointer 0, no objective score, and the discovery count 0. */
  def state(
      candidates      : CandidatePool,
      valSubscores    : Vector[Vector[Double]],
      parents         : Vector[Vector[Int]],
      totalMetricCalls: MetricCallCount
  ): GepaState =
    GepaState(
      candidates,
      valSubscores,
      parents,
      totalMetricCalls,
      nextComponent = candidates.map(_ => 0),
      valObjectiveScores = valSubscores.map(_.map(_ => Map.empty)),
      discoveryCalls = candidates.map(_ => 0)
    )
