package dspy4s.evaluate

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
import dspy4s.core.data.RawPrediction
import dspy4s.evaluate.contracts.EvaluationResult
import dspy4s.evaluate.contracts.ExampleEvaluation
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.nio.file.Files

class PersistenceSuite extends FunSuite:
  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)

  private def savedText(save: (EvaluationResult, String) => Either[String, Unit], result: EvaluationResult): String =
    val path = Files.createTempFile("dspy4s-evaluate-", ".out")
    try
      assertEquals(save(result, path.toString), Right(()))
      Files.readString(path)
    finally
      val _ = Files.deleteIfExists(path)

  private val sampleResult: EvaluationResult = EvaluationResult(
    score = 66.67,
    results = Vector(
      ExampleEvaluation(
        Example(rec("question" := "cap of France?", "answer" := "Paris")),
        RawPrediction(rec("answer" := "Paris")),
        score = 1.0
      ),
      ExampleEvaluation(
        Example(rec("question" := "cap of Italy?", "answer" := "Rome")),
        RawPrediction(rec("answer" := "Naples")),
        score = 0.0
      ),
      ExampleEvaluation(
        Example(rec("question" := "cap of Spain?", "answer" := "Madrid")),
        RawPrediction(rec("answer" := "Madrid")),
        score = 1.0
      )
    ),
    metricName = "exact_match"
  )

  private def single(example: Example, prediction: RawPrediction): EvaluationResult =
    EvaluationResult(50.0, Vector(ExampleEvaluation(example, prediction, 0.5)), "m")

  test("saveAsJson writes one object for each row, with a prefix only on a name collision") {
    assertEquals(
      savedText(EvaluationResultPersistence.saveAsJson, sampleResult),
      """[{"question": "cap of France?", "example_answer": "Paris", "pred_answer": "Paris", "exact_match": 1.0}, """ +
        """{"question": "cap of Italy?", "example_answer": "Rome", "pred_answer": "Naples", "exact_match": 0.0}, """ +
        """{"question": "cap of Spain?", "example_answer": "Madrid", "pred_answer": "Madrid", "exact_match": 1.0}]"""
    )
  }

  test("saveAsCsv writes a header and one line for each row, with the line end of Python csv") {
    assertEquals(
      savedText(EvaluationResultPersistence.saveAsCsv, sampleResult),
      "question,example_answer,pred_answer,exact_match\r\n" +
        "cap of France?,Paris,Paris,1.0\r\n" +
        "cap of Italy?,Rome,Naples,0.0\r\n" +
        "cap of Spain?,Madrid,Madrid,1.0\r\n"
    )
  }

  test("saveAsCsv prefixes pred fields on collision with example fields") {
    val collision = single(Example(rec("answer" := "Paris")), RawPrediction(rec("answer" := "Lyon")))
    assertEquals(
      savedText(EvaluationResultPersistence.saveAsCsv, collision),
      "example_answer,pred_answer,m\r\nParis,Lyon,0.5\r\n"
    )
  }

  test("saveAsJson supports non-string field values") {
    val withNumbers = single(
      Example(rec("answer" := "Paris", "score" := 0.8, "tags" := Vector("x"))),
      RawPrediction(rec("answer" := "Paris", "confidence" := 0.95, "ok" := true))
    )
    assertEquals(
      savedText(EvaluationResultPersistence.saveAsJson, withNumbers),
      """[{"example_answer": "Paris", "score": 0.8, "tags": ["x"], """ +
        """"pred_answer": "Paris", "confidence": 0.95, "ok": true, "m": 0.5}]"""
    )
  }

  test("saveAsCsv quotes a field with a comma, a quote, or a line break, and writes each other value as Python str") {
    val values = single(
      Example(rec("a" := "x,y", "b" := "say \"hi\"", "c" := "one\ntwo", "d" := "one\rtwo", "e" := "plain")),
      RawPrediction(rec("f" := 2, "g" := true, "h" := Vector("x"), "i" := Option.empty[String]))
    )
    assertEquals(
      savedText(EvaluationResultPersistence.saveAsCsv, values),
      "a,b,c,d,e,f,g,h,i,m\r\n" +
        "\"x,y\",\"say \"\"hi\"\"\",\"one\ntwo\",\"one\rtwo\",plain,2,True,\"[\"\"x\"\"]\",,0.5\r\n"
    )
  }

  test("the CSV columns are the union of the keys of the rows, in first-seen order") {
    val failed = EvaluationResult(
      score = 50.0,
      results = Vector(
        ExampleEvaluation(Example(rec("q" := "1", "answer" := "a")), RawPrediction.empty, 0.0, Some("[code] boom")),
        ExampleEvaluation(Example(rec("q" := "2", "answer" := "b")), RawPrediction(rec("answer" := "b")), 1.0)
      ),
      metricName = "m"
    )
    assertEquals(
      savedText(EvaluationResultPersistence.saveAsCsv, failed),
      "q,answer,m,example_answer,pred_answer\r\n1,a,0.0,,\r\n2,,1.0,b,b\r\n"
    )
  }

  test("a path that cannot take a file is a Left") {
    val directory = Files.createTempDirectory("dspy4s-evaluate-")
    try assert(EvaluationResultPersistence.saveAsCsv(sampleResult, directory.toString).isLeft)
    finally
      val _ = Files.deleteIfExists(directory)
  }
