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
