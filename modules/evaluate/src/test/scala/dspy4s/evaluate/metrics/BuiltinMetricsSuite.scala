package dspy4s.evaluate.metrics

import dspy4s.core.contracts.Example
import dspy4s.core.contracts.DynamicPrediction
import munit.FunSuite

class BuiltinMetricsSuite extends FunSuite:

  test("ExactMatch returns 1.0 for matching answer after normalization") {
    val metric = new ExactMatch()
    val ex = Example(Map("answer" -> "the Paris"))
    val pred = DynamicPrediction(Map("answer" -> "Paris"))
    assertEquals(metric.score(ex, pred), Right(1.0))
  }

  test("ExactMatch returns 0.0 for non-matching answer") {
    val metric = new ExactMatch()
    val ex = Example(Map("answer" -> "Paris"))
    val pred = DynamicPrediction(Map("answer" -> "Brussels"))
    assertEquals(metric.score(ex, pred), Right(0.0))
  }

  test("ExactMatch accepts list of reference answers") {
    val metric = new ExactMatch()
    val ex = Example(Map("answer" -> Vector("Paris", "paris france")))
    val pred = DynamicPrediction(Map("answer" -> "paris"))
    assertEquals(metric.score(ex, pred), Right(1.0))
  }

  test("Contains returns 1.0 when prediction contains normalized reference") {
    val metric = new ContainsMatch()
    val ex = Example(Map("answer" -> "paris"))
    val pred = DynamicPrediction(Map("answer" -> "The capital CITY is Paris!"))
    assertEquals(metric.score(ex, pred), Right(1.0))
  }

  test("Contains returns 0.0 when prediction does not contain reference") {
    val metric = new ContainsMatch()
    val ex = Example(Map("answer" -> "paris"))
    val pred = DynamicPrediction(Map("answer" -> "Brussels is the capital"))
    assertEquals(metric.score(ex, pred), Right(0.0))
  }

  test("F1Score returns 1.0 for perfect match") {
    val metric = new F1Score()
    val ex = Example(Map("answer" -> "the cat sat"))
    val pred = DynamicPrediction(Map("answer" -> "the cat sat"))
    assertEquals(metric.score(ex, pred), Right(1.0))
  }

  test("F1Score returns partial score") {
    val metric = new F1Score()
    val ex = Example(Map("answer" -> "the engine broke down yesterday"))
    val pred = DynamicPrediction(Map("answer" -> "the engine broke"))
    val score = metric.score(ex, pred).toOption.get
    assert(score > 0.6 && score < 1.0, s"expected partial F1, got $score")
  }

  test("F1Score returns 0.0 for no overlap") {
    val metric = new F1Score()
    val ex = Example(Map("answer" -> "the cat"))
    val pred = DynamicPrediction(Map("answer" -> "a dog"))
    assertEquals(metric.score(ex, pred), Right(0.0))
  }

  test("AnswerMatch uses exact match when frac >= 1.0") {
    val metric = new AnswerMatch(frac = 1.0)
    assertEquals(metric.name, "answer_exact_match")
    val ex = Example(Map("answer" -> "Paris"))
    val pred = DynamicPrediction(Map("answer" -> "Paris"))
    assertEquals(metric.score(ex, pred), Right(1.0))
  }

  test("AnswerMatch uses F1 threshold when frac < 1.0") {
    val metric = new AnswerMatch(frac = 0.5)
    val ex = Example(Map("answer" -> "the red car"))
    val pred = DynamicPrediction(Map("answer" -> "red car"))
    val score = metric.score(ex, pred).toOption.get
    assertEquals(score, 1.0)
  }

  test("PassageMatch returns 1.0 when context contains answer") {
    val metric = new PassageMatch()
    val ex = Example(Map("answer" -> "Paris"))
    val pred = DynamicPrediction(Map("context" -> Vector("The capital of France is Paris.", "It has a big tower.")))
    assertEquals(metric.score(ex, pred), Right(1.0))
  }

  test("PassageMatch returns 0.0 when no passage contains answer") {
    val metric = new PassageMatch()
    val ex = Example(Map("answer" -> "Paris"))
    val pred = DynamicPrediction(Map("context" -> Vector("Brussels is the capital.", "It has good waffles.")))
    assertEquals(metric.score(ex, pred), Right(0.0))
  }

  test("FunctionMetric wraps a custom boolean predicate") {
    val metric = FunctionMetric.bool("length_gt_3") { (_, pred) =>
      pred.get("answer").exists { case s: String => s.length > 3; case _ => false }
    }
    val ex = Example(Map("answer" -> "x"))
    val long = DynamicPrediction(Map("answer" -> "long answer"))
    val short = DynamicPrediction(Map("answer" -> "hi"))
    assertEquals(metric.score(ex, long), Right(1.0))
    assertEquals(metric.score(ex, short), Right(0.0))
  }

  test("metrics report NotFoundError when prediction missing answer field") {
    val metric = new ExactMatch()
    val ex = Example(Map("answer" -> "Paris"))
    val pred = DynamicPrediction(Map.empty[String, Any])
    assert(metric.score(ex, pred).isLeft)
  }
