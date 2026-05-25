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

  // ── Primitive accessor ladder (asString/asInt/asDouble/asBoolean) ────────

  test("asString returns native strings unchanged") {
    val p = PredictionData.empty.withValue("s", "hello")
    assertEquals(p.asString("s"), Right("hello"))
  }

  test("asString stringifies primitive numerics and booleans") {
    val p = PredictionData.empty
      .withValue("i", 7)
      .withValue("d", 1.5)
      .withValue("b", true)
    assertEquals(p.asString("i"), Right("7"))
    assertEquals(p.asString("d"), Right("1.5"))
    assertEquals(p.asString("b"), Right("true"))
  }

  test("asString rejects non-scalar values") {
    val p = PredictionData.empty.withValue("m", Map("a" -> 1))
    assert(p.asString("m").isLeft)
  }

  test("asString fails for missing key") {
    assert(PredictionData.empty.asString("missing").isLeft)
  }

  test("asInt accepts Int and Long that fits, rejects out-of-range Long") {
    val p = PredictionData.empty
      .withValue("i", 42)
      .withValue("l", 42L)
      .withValue("huge", Long.MaxValue)
    assertEquals(p.asInt("i"), Right(42))
    assertEquals(p.asInt("l"), Right(42))
    assert(p.asInt("huge").isLeft)
  }

  test("asInt parses integer strings, rejects non-integer strings") {
    val p = PredictionData.empty
      .withValue("clean", "42")
      .withValue("padded", "  7  ")
      .withValue("garbage", "abc")
      .withValue("float", "1.5")
    assertEquals(p.asInt("clean"), Right(42))
    assertEquals(p.asInt("padded"), Right(7))
    assert(p.asInt("garbage").isLeft)
    assert(p.asInt("float").isLeft)
  }

  test("asInt rejects Double and Boolean to avoid silent truncation") {
    val p = PredictionData.empty
      .withValue("d", 1.5)
      .withValue("b", true)
    assert(p.asInt("d").isLeft)
    assert(p.asInt("b").isLeft)
  }

  test("asDouble parses numeric strings as Phase-1 addition") {
    val p = PredictionData.empty
      .withValue("clean", "1.5")
      .withValue("int-shaped", "42")
      .withValue("garbage", "abc")
    assertEquals(p.asDouble("clean"), Right(1.5))
    assertEquals(p.asDouble("int-shaped"), Right(42.0))
    assert(p.asDouble("garbage").isLeft)
  }

  test("asBoolean accepts true/false and conservative string forms") {
    val p = PredictionData.empty
      .withValue("native-t", true)
      .withValue("native-f", false)
      .withValue("str-t", "true")
      .withValue("str-f", "FALSE")
      .withValue("str-padded", "  True  ")
    assertEquals(p.asBoolean("native-t"), Right(true))
    assertEquals(p.asBoolean("native-f"), Right(false))
    assertEquals(p.asBoolean("str-t"), Right(true))
    assertEquals(p.asBoolean("str-f"), Right(false))
    assertEquals(p.asBoolean("str-padded"), Right(true))
  }

  test("asBoolean rejects ambiguous strings and other types") {
    val p = PredictionData.empty
      .withValue("yes", "yes")
      .withValue("one", "1")
      .withValue("number", 1)
    assert(p.asBoolean("yes").isLeft, "'yes' must not coerce to true (Phase 1: conservative only)")
    assert(p.asBoolean("one").isLeft, "'1' must not coerce to true (Phase 1: conservative only)")
    assert(p.asBoolean("number").isLeft)
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
