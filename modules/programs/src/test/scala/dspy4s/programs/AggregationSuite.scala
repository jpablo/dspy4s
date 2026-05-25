package dspy4s.programs

import dspy4s.core.contracts.Completions
import dspy4s.core.contracts.DynamicPrediction
import munit.FunSuite

/** Direct dspy4s port of Python DSPy's `tests/predict/test_aggregation.py`.
  * Each test mirrors a Python `test_majority_*` case. */
class AggregationSuite extends FunSuite:

  test("majority with DynamicPrediction picks the most common answer") {
    val rows = Vector(
      Map[String, Any]("answer" -> "2"),
      Map[String, Any]("answer" -> "2"),
      Map[String, Any]("answer" -> "3")
    )
    val prediction = DynamicPrediction.fromRows(rows).toOption.get
    val result = Aggregation.majorityOf(prediction).toOption.get
    assertEquals(result.values("answer"), "2")
  }

  test("majority with Completions picks the most common answer") {
    val completions = Completions.fromRows(Vector(
      Map[String, Any]("answer" -> "2"),
      Map[String, Any]("answer" -> "2"),
      Map[String, Any]("answer" -> "3")
    )).toOption.get
    val result = Aggregation.majorityOf(completions, field = None, normalize = Aggregation.defaultNormalize).toOption.get
    assertEquals(result.values("answer"), "2")
  }

  test("majority with raw row list picks the most common answer") {
    val rows = Vector(
      Map[String, Any]("answer" -> "2"),
      Map[String, Any]("answer" -> "2"),
      Map[String, Any]("answer" -> "3")
    )
    val result = Aggregation.majority(rows).toOption.get
    assertEquals(result.values("answer"), "2")
  }

  test("majority with a custom normalizer collapses whitespace-equivalent entries") {
    // Python's test uses the full `normalize_text` from dspy.evaluate; here
    // we supply an equivalent inline normalizer (lowercase + strip) that
    // canonicalizes "2" and " 2" to the same key.
    val rows = Vector(
      Map[String, Any]("answer" -> "2"),
      Map[String, Any]("answer" -> " 2"),
      Map[String, Any]("answer" -> "3")
    )
    val normalize: Any => Option[String] = {
      case null      => None
      case s: String => Some(s.trim.toLowerCase).filter(_.nonEmpty)
      case other     => Some(other.toString.trim.toLowerCase).filter(_.nonEmpty)
    }
    val result = Aggregation.majority(rows, normalize = normalize).toOption.get
    // First completion with the majority key (normalised "2") wins on tie order.
    assertEquals(result.values("answer"), "2")
  }

  test("majority with explicit field uses that field for the vote") {
    val rows = Vector(
      Map[String, Any]("answer" -> "2", "other" -> "1"),
      Map[String, Any]("answer" -> "2", "other" -> "1"),
      Map[String, Any]("answer" -> "3", "other" -> "2")
    )
    val result = Aggregation.majority(rows, field = Some("other")).toOption.get
    assertEquals(result.values("other"), "1")
  }

  test("majority with no actual majority returns the first completion (tie broken by order)") {
    val rows = Vector(
      Map[String, Any]("answer" -> "2"),
      Map[String, Any]("answer" -> "3"),
      Map[String, Any]("answer" -> "4")
    )
    val result = Aggregation.majority(rows).toOption.get
    assertEquals(result.values("answer"), "2")
  }
