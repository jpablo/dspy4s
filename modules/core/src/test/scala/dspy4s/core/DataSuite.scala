package dspy4s.core

import dspy4s.core.contracts.CompletionData
import dspy4s.core.contracts.ExampleData
import dspy4s.core.contracts.PredictionData
import dspy4s.core.contracts.ValidationError
import munit.FunSuite

class DataSuite extends FunSuite:
  test("example inputs and labels split by input keys") {
    val example = ExampleData("question" -> "What is 1+1?", "answer" -> "2").withInputs(Set("question"))

    assertEquals(example.inputs, Map("question" -> "What is 1+1?"))
    assertEquals(example.labels, Map("answer" -> "2"))
  }

  test("completion rows must have equal lengths") {
    intercept[IllegalArgumentException] {
      CompletionData(
        fields = Map(
          "answer" -> Vector("a", "b"),
          "score" -> Vector(0.5)
        )
      )
    }
  }

  test("completion at out of bounds returns validation error") {
    val completions = CompletionData(fields = Map("answer" -> Vector("a")))
    val result = completions.at(2)

    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[ValidationError])
  }

  test("prediction from completions and score extraction") {
    val completions = CompletionData(
      fields = Map(
        "answer" -> Vector("hello"),
        "score" -> Vector(0.9)
      )
    )
    val prediction = PredictionData.fromCompletions(completions)

    assert(prediction.isRight)
    val built = prediction.toOption.get
    assertEquals(built.values("answer"), "hello")
    assertEquals(built.score, Right(0.9))
  }

  test("completions fromRows and toPredictions") {
    val rows = Vector(
      Map("answer" -> "a", "score" -> 0.1),
      Map("answer" -> "b", "score" -> 0.2)
    )
    val completions = CompletionData.fromRows(rows)

    assert(completions.isRight)
    val predictions = completions.toOption.get.toPredictions
    assert(predictions.isRight)
    val vector = predictions.toOption.get
    assertEquals(vector.size, 2)
    assertEquals(vector.head.values("answer"), "a")
    assertEquals(vector(1).values("answer"), "b")
  }

  test("completions fromRows fails on inconsistent row keys") {
    val rows = Vector(
      Map("answer" -> "a", "score" -> 0.1),
      Map("answer" -> "b")
    )
    val completions = CompletionData.fromRows(rows)

    assert(completions.isLeft)
    assert(completions.left.toOption.get.isInstanceOf[ValidationError])
  }

  test("prediction asDouble and withValue") {
    val prediction = PredictionData.empty
      .withValue("score", 3)
      .withValue("label", "ok")

    assertEquals(prediction.asDouble("score"), Right(3.0))
    assert(prediction.asDouble("label").isLeft)
    assert(prediction.value("missing").isLeft)
  }

  test("completion single exposes first and last prediction") {
    val completions = CompletionData.single(Map("answer" -> "hello", "score" -> 0.42))

    assertEquals(completions.size, 1)
    assertEquals(completions.first.map(_.values("answer")), Right("hello"))
    assertEquals(completions.last.map(_.values("answer")), Right("hello"))
  }

  test("completion empty first and last fail with validation error") {
    val completions = CompletionData(Map.empty)

    assert(completions.first.isLeft)
    assert(completions.last.isLeft)
    assert(completions.first.left.toOption.get.isInstanceOf[ValidationError])
    assert(completions.last.left.toOption.get.isInstanceOf[ValidationError])
  }

  test("prediction from empty rows fails") {
    val prediction = PredictionData.fromRows(Vector.empty)

    assert(prediction.isLeft)
    assert(prediction.left.toOption.get.isInstanceOf[ValidationError])
  }
