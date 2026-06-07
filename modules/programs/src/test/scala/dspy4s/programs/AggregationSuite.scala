package dspy4s.programs

import dspy4s.core.contracts.Completions
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.:=
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** Direct dspy4s port of Python DSPy's `tests/predict/test_aggregation.py`.
  * Each test mirrors a Python `test_majority_*` case. */
class AggregationSuite extends FunSuite:

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)

  private def lookup(rec: DynamicValue.Record, key: String): Option[Any] =
    DynamicValues.recordGet(rec, key).map(DynamicValues.toAny)

  test("majority with DynamicPrediction picks the most common answer") {
    val rows = Vector(
      rec("answer" := "2"),
      rec("answer" := "2"),
      rec("answer" := "3")
    )
    val prediction = DynamicPrediction.fromRows(rows).toOption.get
    val result = Aggregation.majorityOf(prediction).toOption.get
    assertEquals(lookup(result.values, "answer"), Some("2": Any))
  }

  test("majority with Completions picks the most common answer") {
    val completions = Completions.fromRows(Vector(
      rec("answer" := "2"),
      rec("answer" := "2"),
      rec("answer" := "3")
    )).toOption.get
    val result = Aggregation.majorityOf(completions, field = None, normalize = Aggregation.defaultNormalize).toOption.get
    assertEquals(lookup(result.values, "answer"), Some("2": Any))
  }

  test("majority with raw row list picks the most common answer") {
    val rows = Vector(
      rec("answer" := "2"),
      rec("answer" := "2"),
      rec("answer" := "3")
    )
    val result = Aggregation.majority(rows).toOption.get
    assertEquals(lookup(result.values, "answer"), Some("2": Any))
  }

  test("majority with a custom normalizer collapses whitespace-equivalent entries") {
    // Python's test uses the full `normalize_text` from dspy.evaluate; here
    // we supply an equivalent inline normalizer (lowercase + strip) that
    // canonicalizes "2" and " 2" to the same key.
    val rows = Vector(
      rec("answer" := "2"),
      rec("answer" := " 2"),
      rec("answer" := "3")
    )
    val normalize: DynamicValue => Option[String] = {
      case _: DynamicValue.Null.type                        => None
      case DynamicValue.Primitive(PrimitiveValue.String(s)) => Some(s.trim.toLowerCase).filter(_.nonEmpty)
      case other                                            => Some(DynamicValues.renderText(other).trim.toLowerCase).filter(_.nonEmpty)
    }
    val result = Aggregation.majority(rows, normalize = normalize).toOption.get
    // First completion with the majority key (normalised "2") wins on tie order.
    assertEquals(lookup(result.values, "answer"), Some("2": Any))
  }

  test("majority with explicit field uses that field for the vote") {
    val rows = Vector(
      rec("answer" := "2", "other" := "1"),
      rec("answer" := "2", "other" := "1"),
      rec("answer" := "3", "other" := "2")
    )
    val result = Aggregation.majority(rows, field = Some("other")).toOption.get
    assertEquals(lookup(result.values, "other"), Some("1": Any))
  }

  test("majority with no actual majority returns the first completion (tie broken by order)") {
    val rows = Vector(
      rec("answer" := "2"),
      rec("answer" := "3"),
      rec("answer" := "4")
    )
    val result = Aggregation.majority(rows).toOption.get
    assertEquals(lookup(result.values, "answer"), Some("2": Any))
  }

  test("majority breaks an N-way tie (>=5 distinct values) by FIRST occurrence, not hash order") {
    // Regression for the review finding: with 5+ distinct tied keys the internal tally Map becomes a HashMap,
    // and `maxBy` would return the hash-order-first key (arbitrary) instead of the first-occurring one. These
    // 7 single-vote answers all tie at count 1; the first in declaration order ("zebra") must win.
    val first = "zebra"
    val rows = Vector("zebra", "mango", "kiwi21", "fig", "kiwi14", "kiwi19", "berry").map(a => rec("answer" := a))
    val result = Aggregation.majority(rows).toOption.get
    assertEquals(lookup(result.values, "answer"), Some(first: Any))
  }
