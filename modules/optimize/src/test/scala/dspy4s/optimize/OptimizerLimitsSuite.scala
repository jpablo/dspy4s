package dspy4s.optimize

import munit.FunSuite

class OptimizerLimitsSuite extends FunSuite:

  test("optimizer limit literals are checked at compile time") {
    assert(compileErrors("dspy4s.optimize.CandidateCount(0)").nonEmpty)
    assert(compileErrors("dspy4s.optimize.SearchCandidateCount(-1)").nonEmpty)
    assert(compileErrors("dspy4s.optimize.TrialCount(0)").nonEmpty)
    assert(compileErrors("dspy4s.optimize.RuleCount(0)").nonEmpty)
    assert(compileErrors("dspy4s.optimize.RoundCount(0)").nonEmpty)
    assert(compileErrors("dspy4s.optimize.CoproBreadth(1)").nonEmpty)
    assert(compileErrors("dspy4s.optimize.EnsembleSize(0)").nonEmpty)
    assert(compileErrors("dspy4s.optimize.DatasetSampleSize(0)").nonEmpty)
  }

  test("optimizer limits validate runtime values") {
    assertEquals(CandidateCount.either(1).map(_.toInt), Right(1))
    assert(CandidateCount.either(0).isLeft)
    assertEquals(SearchCandidateCount.either(0).map(_.toInt), Right(0))
    assert(SearchCandidateCount.either(-1).isLeft)
    assert(TrialCount.either(0).isLeft)
    assert(RuleCount.either(0).isLeft)
    assert(RoundCount.either(0).isLeft)
    assertEquals(CoproBreadth.either(2).map(_.toInt), Right(2))
    assert(CoproBreadth.either(1).isLeft)
    assert(EnsembleSize.either(0).isLeft)
    assert(DatasetSampleSize.either(0).isLeft)
  }
