package dspy4s.lm

import dspy4s.lm.providers.BatchSize
import dspy4s.lm.runtime.{CacheCapacity, JitterFactor, RetryCount, RetryDelayMillis, RetryPolicies}
import munit.FunSuite

class LmLimitsSuite extends FunSuite:

  test("LM limit literals are checked at compile time") {
    assert(compileErrors("dspy4s.lm.runtime.RetryCount(-1)").nonEmpty)
    assert(compileErrors("dspy4s.lm.runtime.RetryDelayMillis(-1L)").nonEmpty)
    assert(compileErrors("dspy4s.lm.runtime.JitterFactor(1.1)").nonEmpty)
    assert(compileErrors("dspy4s.lm.runtime.CacheCapacity(0)").nonEmpty)
    assert(compileErrors("dspy4s.lm.providers.BatchSize(0)").nonEmpty)
  }

  test("LM limits validate runtime values") {
    assertEquals(RetryCount.either(0).map(_.toInt), Right(0))
    assert(RetryCount.either(-1).isLeft)
    assertEquals(RetryDelayMillis.either(0L).map(_.toLong), Right(0L))
    assert(RetryDelayMillis.either(-1L).isLeft)
    assertEquals(JitterFactor.either(0.5).map(_.toDouble), Right(0.5))
    assert(JitterFactor.either(-0.1).isLeft)
    assert(JitterFactor.either(1.1).isLeft)
    assertEquals(CacheCapacity.either(1).map(_.toInt), Right(1))
    assert(CacheCapacity.either(0).isLeft)
    assertEquals(BatchSize.either(1).map(_.toInt), Right(1))
    assert(BatchSize.either(0).isLeft)
  }

  test("exponential backoff retains the cross-field delay ordering law") {
    intercept[IllegalArgumentException] {
      RetryPolicies.exponentialBackoff(
        RetryCount(1),
        baseDelayMillis = RetryDelayMillis(2L),
        maxDelayMillis = RetryDelayMillis(1L)
      )
    }
  }
