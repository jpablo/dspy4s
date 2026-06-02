package dspy4s.lm.providers

import munit.FunSuite

class OpenAiUsageSuite extends FunSuite:

  private def usage(json: String) =
    OpenAiUsage.fromDynamic(DynamicJson.decode(json).toOption.get).toLmUsage

  test("chat naming: prompt/completion/total map straight through, details lists present fields") {
    val u = usage("""{"prompt_tokens":5,"completion_tokens":7,"total_tokens":12}""")
    assertEquals(u.promptTokens, 5L)
    assertEquals(u.completionTokens, 7L)
    assertEquals(u.totalTokens, 12L)
    assertEquals(u.details, Map("prompt_tokens" -> 5L, "completion_tokens" -> 7L, "total_tokens" -> 12L))
  }

  test("responses naming: input/output tokens alias onto prompt/completion") {
    val u = usage("""{"input_tokens":3,"output_tokens":4,"total_tokens":7}""")
    assertEquals(u.promptTokens, 3L)
    assertEquals(u.completionTokens, 4L)
    assertEquals(u.totalTokens, 7L)
    assertEquals(u.details, Map("total_tokens" -> 7L, "input_tokens" -> 3L, "output_tokens" -> 4L))
  }

  test("total_tokens falls back to prompt + completion; unknown fields ignored") {
    val u = usage("""{"prompt_tokens":4,"completion_tokens":6,"prompt_tokens_details":{"cached_tokens":2}}""")
    assertEquals(u.totalTokens, 10L)
    assertEquals(u.details.get("total_tokens"), None) // not on the wire -> not fabricated
    assertEquals(u.details.contains("prompt_tokens_details"), false) // nested object isn't a numeric field
  }
