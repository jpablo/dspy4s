package dspy4s.core

import dspy4s.core.contracts.Completions
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.ValidationError
import dspy4s.core.contracts.:=
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

class DataSuite extends FunSuite:

  private def str(s: String): DynamicValue   = DynamicValue.Primitive(PrimitiveValue.String(s))
  private def dbl(d: Double): DynamicValue   = DynamicValue.Primitive(PrimitiveValue.Double(d))

  test("example inputs and labels split by input keys") {
    val example = Example("question" := "What is 1+1?", "answer" := "2").withInputs(Set("question"))

    assertEquals(example.inputs, DynamicValue.Record(Chunk("question" -> str("What is 1+1?"))))
    assertEquals(example.labels, DynamicValue.Record(Chunk("answer" -> str("2"))))
  }

  test("completion rows must have equal lengths") {
    intercept[IllegalArgumentException] {
      Completions(
        fields = Map(
          "answer" -> Vector(str("a"), str("b")),
          "score"  -> Vector(dbl(0.5))
        )
      )
    }
  }

  test("completion at out of bounds returns validation error") {
    val completions = Completions(fields = Map("answer" -> Vector(str("a"))))
    val result      = completions.at(2)

    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[ValidationError])
  }

  test("prediction from completions and score extraction") {
    val completions = Completions(
      fields = Map(
        "answer" -> Vector(str("hello")),
        "score"  -> Vector(dbl(0.9))
      )
    )
    val prediction = DynamicPrediction.fromCompletions(completions)

    assert(prediction.isRight)
    val built = prediction.toOption.get
    assertEquals(built.get("answer"), Some(str("hello")))
    assertEquals(built.score, Right(0.9))
  }

  test("completions fromRows builds equal-length columns indexable via at(i)") {
    val rows = Vector(
      DynamicValues.recordFromEntries(Vector("answer" := "a", "score" := 0.1)),
      DynamicValues.recordFromEntries(Vector("answer" := "b", "score" := 0.2))
    )
    val completions = Completions.fromRows(rows).toOption.get
    assertEquals(completions.size, 2)
    assertEquals(completions.at(0).toOption.get.get("answer"), Some(str("a")))
    assertEquals(completions.at(1).toOption.get.get("answer"), Some(str("b")))
  }

  test("completions fromRows fails on inconsistent row keys") {
    val rows = Vector(
      DynamicValues.recordFromEntries(Vector("answer" := "a", "score" := 0.1)),
      DynamicValues.recordFromEntries(Vector("answer" := "b"))
    )
    val completions = Completions.fromRows(rows)

    assert(completions.isLeft)
    assert(completions.left.toOption.get.isInstanceOf[ValidationError])
  }

  test("prediction asDouble and withValue") {
    val prediction = DynamicPrediction.empty
      .withRawValue("score", 3)
      .withRawValue("label", "ok")

    assertEquals(prediction.asDouble("score"), Right(3.0))
    assert(prediction.asDouble("label").isLeft)
    assert(prediction.value("missing").isLeft)
  }

  // ── Primitive accessor ladder (asString/asInt/asDouble/asBoolean) ────────

  test("asString returns native strings unchanged") {
    val p = DynamicPrediction.empty.withRawValue("s", "hello")
    assertEquals(p.asString("s"), Right("hello"))
  }

  test("asString stringifies primitive numerics and booleans") {
    val p = DynamicPrediction.empty
      .withRawValue("i", 7)
      .withRawValue("d", 1.5)
      .withRawValue("b", true)
    assertEquals(p.asString("i"), Right("7"))
    assertEquals(p.asString("d"), Right("1.5"))
    assertEquals(p.asString("b"), Right("true"))
  }

  test("asString rejects non-scalar values") {
    val p = DynamicPrediction.empty.withRawValue("m", Map("a" -> 1))
    assert(p.asString("m").isLeft)
  }

  test("asString fails for missing key") {
    assert(DynamicPrediction.empty.asString("missing").isLeft)
  }

  test("asInt accepts Int and Long that fits, rejects out-of-range Long") {
    val p = DynamicPrediction.empty
      .withRawValue("i", 42)
      .withRawValue("l", 42L)
      .withRawValue("huge", Long.MaxValue)
    assertEquals(p.asInt("i"), Right(42))
    assertEquals(p.asInt("l"), Right(42))
    assert(p.asInt("huge").isLeft)
  }

  test("asInt parses integer strings, rejects non-integer strings") {
    val p = DynamicPrediction.empty
      .withRawValue("clean", "42")
      .withRawValue("padded", "  7  ")
      .withRawValue("garbage", "abc")
      .withRawValue("float", "1.5")
    assertEquals(p.asInt("clean"), Right(42))
    assertEquals(p.asInt("padded"), Right(7))
    assert(p.asInt("garbage").isLeft)
    assert(p.asInt("float").isLeft)
  }

  test("asInt rejects Double and Boolean to avoid silent truncation") {
    val p = DynamicPrediction.empty
      .withRawValue("d", 1.5)
      .withRawValue("b", true)
    assert(p.asInt("d").isLeft)
    assert(p.asInt("b").isLeft)
  }

  test("asDouble parses numeric strings as Phase-1 addition") {
    val p = DynamicPrediction.empty
      .withRawValue("clean", "1.5")
      .withRawValue("int-shaped", "42")
      .withRawValue("garbage", "abc")
    assertEquals(p.asDouble("clean"), Right(1.5))
    assertEquals(p.asDouble("int-shaped"), Right(42.0))
    assert(p.asDouble("garbage").isLeft)
  }

  test("asBoolean accepts true/false and conservative string forms") {
    val p = DynamicPrediction.empty
      .withRawValue("native-t", true)
      .withRawValue("native-f", false)
      .withRawValue("str-t", "true")
      .withRawValue("str-f", "FALSE")
      .withRawValue("str-padded", "  True  ")
    assertEquals(p.asBoolean("native-t"), Right(true))
    assertEquals(p.asBoolean("native-f"), Right(false))
    assertEquals(p.asBoolean("str-t"), Right(true))
    assertEquals(p.asBoolean("str-f"), Right(false))
    assertEquals(p.asBoolean("str-padded"), Right(true))
  }

  test("asBoolean rejects ambiguous strings and other types") {
    val p = DynamicPrediction.empty
      .withRawValue("yes", "yes")
      .withRawValue("one", "1")
      .withRawValue("number", 1)
    assert(p.asBoolean("yes").isLeft, "'yes' must not coerce to true (Phase 1: conservative only)")
    assert(p.asBoolean("one").isLeft, "'1' must not coerce to true (Phase 1: conservative only)")
    assert(p.asBoolean("number").isLeft)
  }

  test("completion at out-of-bounds index fails with validation error") {
    val completions = Completions(Map.empty)
    val result      = completions.at(0)
    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[ValidationError])
  }

  test("prediction from empty rows fails") {
    val prediction = DynamicPrediction.fromRows(Vector.empty)

    assert(prediction.isLeft)
    assert(prediction.left.toOption.get.isInstanceOf[ValidationError])
  }
