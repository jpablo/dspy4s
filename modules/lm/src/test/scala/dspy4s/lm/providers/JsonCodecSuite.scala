package dspy4s.lm.providers

import munit.FunSuite

class JsonCodecSuite extends FunSuite:

  test("encode round-trip preserves primitives") {
    val payload: Map[String, Any] = Map(
      "s" -> "hello",
      "i" -> 42,
      "l" -> 1234567890L,
      "d" -> 3.14,
      "b" -> true,
      "empty" -> ""
    )
    val json = JsonCodec.encodeString(payload)
    val decoded = JsonCodec.decodeString(json)
    assert(decoded.isRight)
    val back = decoded.toOption.get
    assertEquals(back("s"), "hello")
    assertEquals(back("i").asInstanceOf[Long], 42L)
    assertEquals(back("l").asInstanceOf[Long], 1234567890L)
    assertEquals(back("d").asInstanceOf[Double], 3.14)
    assertEquals(back("b"), true)
    assertEquals(back("empty"), "")
  }

  test("encode preserves nested maps and vectors") {
    val payload: Map[String, Any] = Map(
      "messages" -> Vector(
        Map("role" -> "user", "content" -> "hi"),
        Map("role" -> "assistant", "content" -> "hello")
      ),
      "nested" -> Map("inner" -> Vector(1, 2, 3))
    )
    val decoded = JsonCodec.decodeString(JsonCodec.encodeString(payload))
    assert(decoded.isRight)
    val back = decoded.toOption.get
    val messages = back("messages").asInstanceOf[Vector[Map[String, Any]]]
    assertEquals(messages.size, 2)
    assertEquals(messages(0)("role"), "user")
    assertEquals(messages(1)("content"), "hello")
    val nested = back("nested").asInstanceOf[Map[String, Any]]
    val inner = nested("inner").asInstanceOf[Vector[Long]]
    assertEquals(inner, Vector(1L, 2L, 3L))
  }

  test("encode strips None values from top level and nested") {
    val payload: Map[String, Any] = Map(
      "keep" -> "yes",
      "drop1" -> None,
      "drop2" -> null,
      "inner" -> Map("nestedKeep" -> "x", "nestedDrop" -> None)
    )
    val cleaned = JsonCodec.stripNone(payload)
    assert(!cleaned.contains("drop1"))
    assert(!cleaned.contains("drop2"))
    assertEquals(cleaned("keep"), "yes")
    val inner = cleaned("inner").asInstanceOf[Map[String, Any]]
    assert(!inner.contains("nestedDrop"))
    assertEquals(inner("nestedKeep"), "x")
  }

  test("decodeString returns parse error for malformed JSON") {
    val result = JsonCodec.decodeString("{ broken json")
    assert(result.isLeft)
  }

  test("decode wraps non-object responses under a value key") {
    val result = JsonCodec.decodeString("\"just a string\"")
    assert(result.isRight)
    assertEquals(result.toOption.get("value"), "just a string")
  }

  test("Some wrapping unwraps during encode") {
    val payload: Map[String, Any] = Map(
      "wrapped" -> Some("inner")
    )
    val json = JsonCodec.encodeString(payload)
    assert(json.contains("\"wrapped\":\"inner\""))
    val cleaned = JsonCodec.stripNone(payload.updated("noneWrapped", Some(None)))
    assertEquals(cleaned("wrapped"), Some("inner"))
    assertEquals(cleaned.get("noneWrapped"), Some(Some(None)))
  }
