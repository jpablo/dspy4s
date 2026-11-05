package dspy4s.evaluate.metrics

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.NotFoundError
import dspy4s.core.contracts.ValidationError
import dspy4s.core.data.Example
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.RuntimeContext
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

class BuiltinMetricsSuite extends FunSuite:
  // Builtin metrics ignore the ambient context (no LM needed); a default one lets the `.score` calls compile.
  private given RuntimeContext = RuntimeContext()

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)

  test("ExactMatch returns 1.0 for matching answer after normalization") {
    val metric = new ExactMatch()
    val ex     = Example(rec("answer" := "the Paris"))
    val pred   = RawPrediction(rec("answer" := "Paris"))
    assertEquals(metric.score(ex, pred), Right(1.0))
  }

  test("ExactMatch returns 0.0 for non-matching answer") {
    val metric = new ExactMatch()
    val ex     = Example(rec("answer" := "Paris"))
    val pred   = RawPrediction(rec("answer" := "Brussels"))
    assertEquals(metric.score(ex, pred), Right(0.0))
  }

  test("ExactMatch accepts list of reference answers") {
    val metric = new ExactMatch()
    val ex     = Example(rec("answer" := Vector("Paris", "paris france")))
    val pred   = RawPrediction(rec("answer" := "paris"))
    assertEquals(metric.score(ex, pred), Right(1.0))
  }

  test("Contains returns 1.0 when prediction contains normalized reference") {
    val metric = new ContainsMatch()
    val ex     = Example(rec("answer" := "paris"))
    val pred   = RawPrediction(rec("answer" := "The capital CITY is Paris!"))
    assertEquals(metric.score(ex, pred), Right(1.0))
  }

  test("Contains returns 0.0 when prediction does not contain reference") {
    val metric = new ContainsMatch()
    val ex     = Example(rec("answer" := "paris"))
    val pred   = RawPrediction(rec("answer" := "Brussels is the capital"))
    assertEquals(metric.score(ex, pred), Right(0.0))
  }

  test("F1Score returns 1.0 for perfect match") {
    val metric = new F1Score()
    val ex     = Example(rec("answer" := "the cat sat"))
    val pred   = RawPrediction(rec("answer" := "the cat sat"))
    assertEquals(metric.score(ex, pred), Right(1.0))
  }

  test("F1Score returns partial score") {
    val metric = new F1Score()
    val ex     = Example(rec("answer" := "the engine broke down yesterday"))
    val pred   = RawPrediction(rec("answer" := "the engine broke"))
    val score  = metric.score(ex, pred).toOption.get
    assert(score > 0.6 && score < 1.0, s"expected partial F1, got $score")
  }

  test("F1Score returns 0.0 for no overlap") {
    val metric = new F1Score()
    val ex     = Example(rec("answer" := "the cat"))
    val pred   = RawPrediction(rec("answer" := "a dog"))
    assertEquals(metric.score(ex, pred), Right(0.0))
  }

  test("AnswerMatch uses exact match when frac >= 1.0") {
    val metric = new AnswerMatch(frac = 1.0)
    assertEquals(metric.name, "answer_exact_match")
    val ex   = Example(rec("answer" := "Paris"))
    val pred = RawPrediction(rec("answer" := "Paris"))
    assertEquals(metric.score(ex, pred), Right(1.0))
  }

  test("AnswerMatch uses F1 threshold when frac < 1.0") {
    val metric = new AnswerMatch(frac = 0.5)
    val ex     = Example(rec("answer" := "the red car"))
    val pred   = RawPrediction(rec("answer" := "red car"))
    val score  = metric.score(ex, pred).toOption.get
    assertEquals(score, 1.0)
  }

  test("PassageMatch returns 1.0 when context contains answer") {
    val metric = new PassageMatch()
    val ex     = Example(rec("answer" := "Paris"))
    val pred   = RawPrediction(rec("context" := Vector("The capital of France is Paris.", "It has a big tower.")))
    assertEquals(metric.score(ex, pred), Right(1.0))
  }

  test("PassageMatch returns 0.0 when no passage contains answer") {
    val metric = new PassageMatch()
    val ex     = Example(rec("answer" := "Paris"))
    val pred   = RawPrediction(rec("context" := Vector("Brussels is the capital.", "It has good waffles.")))
    assertEquals(metric.score(ex, pred), Right(0.0))
  }

  test("PassageMatch: an answer with no tokens matches each passage, as upstream range includes 0") {
    val metric = new PassageMatch()
    val ex     = Example(rec("answer" := "the"))
    assertEquals(metric.score(ex, RawPrediction(rec("context" := Vector("any text")))), Right(1.0))
    assertEquals(metric.score(ex, RawPrediction(rec("context" := "the"))), Right(1.0))
  }

  test("PassageMatch reads a string context as one passage, and a list answer as one answer for each item") {
    val metric = new PassageMatch()
    val pred   = RawPrediction(rec("context" := "The Eiffel Tower is in Paris."))
    assertEquals(metric.score(Example(rec("answer" := Vector("Rome", "Paris"))), pred), Right(1.0))
    assertEquals(metric.score(Example(rec("answer" := Vector("Rome", "Oslo"))), pred), Right(0.0))
  }

  test("a number or a bool answer reads as its Python text, where upstream raises") {
    val metric = new ExactMatch()
    assertEquals(metric.score(Example(rec("answer" := 42)), RawPrediction(rec("answer" := "42"))), Right(1.0))
    assertEquals(metric.score(Example(rec("answer" := "42")), RawPrediction(rec("answer" := 42))), Right(1.0))
    assertEquals(metric.score(Example(rec("answer" := true)), RawPrediction(rec("answer" := "true"))), Right(1.0))
  }

  test("a null is a ValidationError that names the field, also for an empty Option") {
    val metric = new ExactMatch("reply")
    val result = metric.score(Example(rec("reply" := "a")), RawPrediction(rec("reply" -> DynamicValue.Null)))
    assert(
      result.left.exists(error => error.isInstanceOf[ValidationError] && error.message.contains("reply")),
      result.toString
    )
    assert(
      metric.score(Example(rec("reply" := Option.empty[String])), RawPrediction(rec("reply" := "a"))).left
        .exists(_.isInstanceOf[ValidationError])
    )
    val passages = new PassageMatch()
    assert(passages.score(Example(rec("answer" := "a")), RawPrediction(rec("context" -> DynamicValue.Null))).isLeft)
    assert(passages.score(Example(rec("answer" -> DynamicValue.Null)), RawPrediction(rec("context" := "a"))).isLeft)
  }

  test("a sequence with no string item is a NotFoundError") {
    val result = new ExactMatch().score(Example(rec("answer" := Vector(1, 2))), RawPrediction(rec("answer" := "1")))
    assert(result.left.exists(_.isInstanceOf[NotFoundError]), result.toString)
  }

  test("an Option answer reads as its element") {
    val ex = Example(rec("answer" := Option("Paris")))
    assertEquals(new ExactMatch().score(ex, RawPrediction(rec("answer" := "paris"))), Right(1.0))
  }

  test("FunctionMetric wraps a custom boolean predicate") {
    val metric = FunctionMetric.bool("length_gt_3") { (_, pred) =>
      pred.get("answer").exists {
        case DynamicValue.Primitive(PrimitiveValue.String(s)) => s.length > 3
        case _                                                => false
      }
    }
    val ex    = Example(rec("answer" := "x"))
    val long  = RawPrediction(rec("answer" := "long answer"))
    val short = RawPrediction(rec("answer" := "hi"))
    assertEquals(metric.score(ex, long), Right(1.0))
    assertEquals(metric.score(ex, short), Right(0.0))
  }

  test("metrics report NotFoundError when prediction missing answer field") {
    val metric = new ExactMatch()
    val ex     = Example(rec("answer" := "Paris"))
    val pred   = RawPrediction.empty
    assert(metric.score(ex, pred).isLeft)
  }
