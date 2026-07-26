package dspy4s.gepa

import dspy4s.programs.predictors.PredictorId
import munit.FunSuite

class GepaLimitsSuite extends FunSuite:

  test("GEPA limit literals are checked at compile time") {
    assert(compileErrors("dspy4s.gepa.MetricCallCount(-1)").nonEmpty)
    assert(compileErrors("dspy4s.gepa.MinibatchSize(0)").nonEmpty)
    assert(compileErrors("dspy4s.gepa.MergeInvocationLimit(-1)").nonEmpty)
    assert(compileErrors("dspy4s.gepa.MergeSubsampleSize(0)").nonEmpty)
    assert(compileErrors("dspy4s.gepa.GepaCandidateCount(0)").nonEmpty)
  }

  test("GEPA limits and candidate pools validate runtime values") {
    assertEquals(MetricCallCount.either(0).map(_.toInt), Right(0))
    assert(MetricCallCount.either(-1).isLeft)
    assert(MinibatchSize.either(0).isLeft)
    assertEquals(MergeInvocationLimit.either(0).map(_.toInt), Right(0))
    assert(MergeInvocationLimit.either(-1).isLeft)
    assert(MergeSubsampleSize.either(0).isLeft)
    assert(GepaCandidateCount.either(0).isLeft)
    assert(CandidatePool.either(Vector.empty).isLeft)
    val candidate = Map(PredictorId(0) -> Some("instruction"))
    assertEquals(CandidatePool.either(Vector(candidate)).map(_.toVector), Right(Vector(candidate)))
  }

  test("metric-call reconstruction rejects negative deltas and overflow") {
    assertEquals(MetricCallCount.add(MetricCallCount(2), 3).toInt, 5)
    val _ = intercept[IllegalArgumentException](MetricCallCount.add(MetricCallCount(2), -1))
    intercept[ArithmeticException](MetricCallCount.add(MetricCallCount(Int.MaxValue), 1))
  }
