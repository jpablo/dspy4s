package dspy4s.core

import dspy4s.core.contracts.Completions
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.ValidationError
import munit.FunSuite

class DataSuite extends FunSuite:
  test("example inputs and labels split by input keys") {
    val example = Example("question" -> "What is 1+1?", "answer" -> "2").withInputs(Set("question"))

    assertEquals(example.inputs, Map("question" -> "What is 1+1?"))
    assertEquals(example.labels, Map("answer" -> "2"))
  }

  test("completion rows must have equal lengths") {
    intercept[IllegalArgumentException] {
      Completions(
        fields = Map(
          "answer" -> Vector("a", "b"),
          "score" -> Vector(0.5)
        )
      )
    }
  }

  test("completion at out of bounds returns validation error") {
    val completions = Completions(fields = Map("answer" -> Vector("a")))
    val result = completions.at(2)

    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[ValidationError])
  }

  test("prediction from completions and score extraction") {
    val completions = Completions(
      fields = Map(
        "answer" -> Vector("hello"),
        "score" -> Vector(0.9)
      )
    )
    val prediction = DynamicPrediction.fromCompletions(completions)

    assert(prediction.isRight)
    val built = prediction.toOption.get
    assertEquals(built.values("answer"), "hello")
    assertEquals(built.score, Right(0.9))
  }

  test("completions fromRows builds equal-length columns indexable via at(i)") {
    val rows = Vector(
      Map("answer" -> "a", "score" -> 0.1),
      Map("answer" -> "b", "score" -> 0.2)
    )
    val completions = Completions.fromRows(rows).toOption.get
    assertEquals(completions.size, 2)
    assertEquals(completions.at(0).toOption.get.values("answer"), "a")
    assertEquals(completions.at(1).toOption.get.values("answer"), "b")
  }

  test("completions fromRows fails on inconsistent row keys") {
    val rows = Vector(
      Map("answer" -> "a", "score" -> 0.1),
      Map("answer" -> "b")
    )
    val completions = Completions.fromRows(rows)

    assert(completions.isLeft)
    assert(completions.left.toOption.get.isInstanceOf[ValidationError])
  }

  test("prediction asDouble and withValue") {
    val prediction = DynamicPrediction.empty
      .withValue("score", 3)
      .withValue("label", "ok")

    assertEquals(prediction.asDouble("score"), Right(3.0))
    assert(prediction.asDouble("label").isLeft)
    assert(prediction.value("missing").isLeft)
  }

  // ── Primitive accessor ladder (asString/asInt/asDouble/asBoolean) ────────

  test("asString returns native strings unchanged") {
    val p = DynamicPrediction.empty.withValue("s", "hello")
    assertEquals(p.asString("s"), Right("hello"))
  }

  test("asString stringifies primitive numerics and booleans") {
    val p = DynamicPrediction.empty
      .withValue("i", 7)
      .withValue("d", 1.5)
      .withValue("b", true)
    assertEquals(p.asString("i"), Right("7"))
    assertEquals(p.asString("d"), Right("1.5"))
    assertEquals(p.asString("b"), Right("true"))
  }

  test("asString rejects non-scalar values") {
    val p = DynamicPrediction.empty.withValue("m", Map("a" -> 1))
    assert(p.asString("m").isLeft)
  }

  test("asString fails for missing key") {
    assert(DynamicPrediction.empty.asString("missing").isLeft)
  }

  test("asInt accepts Int and Long that fits, rejects out-of-range Long") {
    val p = DynamicPrediction.empty
      .withValue("i", 42)
      .withValue("l", 42L)
      .withValue("huge", Long.MaxValue)
    assertEquals(p.asInt("i"), Right(42))
    assertEquals(p.asInt("l"), Right(42))
    assert(p.asInt("huge").isLeft)
  }

  test("asInt parses integer strings, rejects non-integer strings") {
    val p = DynamicPrediction.empty
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
    val p = DynamicPrediction.empty
      .withValue("d", 1.5)
      .withValue("b", true)
    assert(p.asInt("d").isLeft)
    assert(p.asInt("b").isLeft)
  }

  test("asDouble parses numeric strings as Phase-1 addition") {
    val p = DynamicPrediction.empty
      .withValue("clean", "1.5")
      .withValue("int-shaped", "42")
      .withValue("garbage", "abc")
    assertEquals(p.asDouble("clean"), Right(1.5))
    assertEquals(p.asDouble("int-shaped"), Right(42.0))
    assert(p.asDouble("garbage").isLeft)
  }

  test("asBoolean accepts true/false and conservative string forms") {
    val p = DynamicPrediction.empty
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
    val p = DynamicPrediction.empty
      .withValue("yes", "yes")
      .withValue("one", "1")
      .withValue("number", 1)
    assert(p.asBoolean("yes").isLeft, "'yes' must not coerce to true (Phase 1: conservative only)")
    assert(p.asBoolean("one").isLeft, "'1' must not coerce to true (Phase 1: conservative only)")
    assert(p.asBoolean("number").isLeft)
  }

  test("completion at out-of-bounds index fails with validation error") {
    val completions = Completions(Map.empty)
    val result = completions.at(0)
    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[ValidationError])
  }

  test("prediction from empty rows fails") {
    val prediction = DynamicPrediction.fromRows(Vector.empty)

    assert(prediction.isLeft)
    assert(prediction.left.toOption.get.isInstanceOf[ValidationError])
  }
