package dspy4s.lm.providers

import dspy4s.lm.contracts.TokenCategory
import munit.FunSuite

class OpenAiUsageSuite extends FunSuite:

  private def usage(json: String) =
    OpenAiUsage.fromDynamic(DynamicJson.decode(json).toOption.get).toLmUsage

  test("chat naming maps to typed core fields; extras stays empty (no duplication)") {
    val u = usage("""{"prompt_tokens":5,"completion_tokens":7,"total_tokens":12}""")
    assertEquals(u.promptTokens, 5L)
    assertEquals(u.completionTokens, 7L)
    assertEquals(u.totalTokens, 12L)
    assertEquals(u.extras, Map.empty[TokenCategory, Long])
  }

  test("responses naming: input/output tokens alias onto the core prompt/completion fields") {
    val u = usage("""{"input_tokens":3,"output_tokens":4,"total_tokens":7}""")
    assertEquals(u.promptTokens, 3L)
    assertEquals(u.completionTokens, 4L)
    assertEquals(u.totalTokens, 7L)
    assertEquals(u.extras, Map.empty[TokenCategory, Long])
  }

  test("total_tokens falls back to prompt + completion when absent") {
    assertEquals(usage("""{"prompt_tokens":4,"completion_tokens":6}""").totalTokens, 10L)
  }

  test("nested detail blocks populate typed extras; prompt/completion-side audio is summed") {
    val u = usage(
      """{"prompt_tokens":50,"completion_tokens":20,"total_tokens":70,
        |"prompt_tokens_details":{"cached_tokens":10,"audio_tokens":2},
        |"completion_tokens_details":{"reasoning_tokens":8,"audio_tokens":3}}""".stripMargin
    )
    assertEquals(u.promptTokens, 50L)
    assertEquals(u.completionTokens, 20L)
    assertEquals(
      u.extras,
      Map(TokenCategory.Cached -> 10L, TokenCategory.Audio -> 5L, TokenCategory.Reasoning -> 8L)
    )
  }
