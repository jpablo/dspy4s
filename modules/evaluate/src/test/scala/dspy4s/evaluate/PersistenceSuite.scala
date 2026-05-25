package dspy4s.evaluate

import dspy4s.core.contracts.Example
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.evaluate.contracts.EvaluationResult
import dspy4s.evaluate.contracts.ExampleEvaluation
import munit.FunSuite

import java.nio.file.Files
import java.nio.file.Paths
import scala.io.Source
import scala.jdk.CollectionConverters.*

class PersistenceSuite extends FunSuite:

  private def tmpPath(suffix: String) =
    Files.createTempFile("dspy4s-evaluate-", suffix).toString

  private val sampleResult: EvaluationResult =
    EvaluationResult(
      score = 66.67,
      results = Vector(
        ExampleEvaluation(
          Example(Map("question" -> "cap of France?", "answer" -> "Paris")),
          DynamicPrediction(Map("answer" -> "Paris")),
          score = 1.0
        ),
        ExampleEvaluation(
          Example(Map("question" -> "cap of Italy?", "answer" -> "Rome")),
          DynamicPrediction(Map("answer" -> "Naples")),
          score = 0.0
        ),
        ExampleEvaluation(
          Example(Map("question" -> "cap of Spain?", "answer" -> "Madrid")),
          DynamicPrediction(Map("answer" -> "Madrid")),
          score = 1.0
        )
      ),
      metricName = "exact_match"
    )

  test("saveAsJson writes array of flat result objects") {
    val path = tmpPath(".json")
    try
      val result = EvaluationResultPersistence.saveAsJson(sampleResult, path)
      assertEquals(result, Right(()))
      val content = Source.fromFile(path).mkString
      val parsed = ujson.read(content)
      val arr = parsed.arr
      assertEquals(arr.size, 3)
      assertEquals(arr(0)("example_question").str, "cap of France?")
      assertEquals(arr(0)("example_answer").str, "Paris")
      assertEquals(arr(0)("pred_answer").str, "Paris")
      assertEquals(arr(0)("exact_match").num, 1.0)
      assertEquals(arr(1)("exact_match").num, 0.0)
      assertEquals(arr(2)("exact_match").num, 1.0)
    finally Files.deleteIfExists(Paths.get(path))
  }

  test("saveAsCsv writes header and one row per evaluation") {
    val path = tmpPath(".csv")
    try
      val result = EvaluationResultPersistence.saveAsCsv(sampleResult, path)
      assertEquals(result, Right(()))
      val lines = Source.fromFile(path).getLines().toVector
      assert(lines.size >= 4, s"expected at least 4 lines (header + 3 rows), got ${lines.size}")
      val header = lines.head.split(",").map(_.stripPrefix("\"").stripSuffix("\"")).toSet
      assert(header.contains("example_question"))
      assert(header.contains("example_answer"))
      assert(header.contains("exact_match"))
      // prediction and example share 'answer', so CSV uses 'pred_answer'
      assert(header.contains("pred_answer"))
    finally Files.deleteIfExists(Paths.get(path))
  }

  test("saveAsCsv prefixes pred fields on collision with example fields") {
    val path = tmpPath(".csv")
    val collision = EvaluationResult(
      score = 50.0,
      results = Vector(
        ExampleEvaluation(
          Example(Map("answer" -> "Paris")),
          DynamicPrediction(Map("answer" -> "Lyon")),
          score = 0.0
        )
      ),
      metricName = "exact_match"
    )
    try
      val result = EvaluationResultPersistence.saveAsCsv(collision, path)
      assertEquals(result, Right(()))
      val header = Source.fromFile(path).getLines().next()
      assert(header.contains("example_answer"))
      assert(header.contains("pred_answer"))
    finally Files.deleteIfExists(Paths.get(path))
  }

  test("saveAsJson supports non-string field values") {
    val path = tmpPath(".json")
    val withNumbers = sampleResult.copy(
      results = Vector(
        ExampleEvaluation(
          Example(Map("answer" -> "Paris", "score" -> 0.8)),
          DynamicPrediction(Map("answer" -> "Paris", "confidence" -> 0.95)),
          score = 1.0
        )
      )
    )
    try
      val result = EvaluationResultPersistence.saveAsJson(withNumbers, path)
      assertEquals(result, Right(()))
      val content = Source.fromFile(path).mkString
      val parsed = ujson.read(content)
      assertEquals(parsed.arr(0)("example_score").num, 0.8)
      assertEquals(parsed.arr(0)("confidence").num, 0.95)
    finally Files.deleteIfExists(Paths.get(path))
  }
