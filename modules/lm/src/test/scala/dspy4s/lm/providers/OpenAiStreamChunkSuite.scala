package dspy4s.lm.providers

import munit.FunSuite

class OpenAiStreamChunkSuite extends FunSuite:

  private def decode(json: String): OpenAiStreamChunk =
    OpenAiStreamChunk.decode(json).toOption.get

  test("decodes content delta, tolerating null finish_reason and unknown fields") {
    val chunk = decode(
      """{"id":"x","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"},"finish_reason":null}]}"""
    )
    val lm = chunk.toLmChunk
    assertEquals(lm.text, "Hi")
    assertEquals(lm.finishReason, None)
    assertEquals(lm.toolCalls, Vector.empty)
    assertEquals(lm.usage, None)
  }

  test("decodes finish_reason when present") {
    assertEquals(decode("""{"choices":[{"delta":{"content":" there"},"finish_reason":"stop"}]}""").toLmChunk.finishReason, Some("stop"))
  }

  test("decodes tool-call deltas with index fallback and partial function fragments") {
    val calls = decode(
      """{"choices":[{"delta":{"tool_calls":[{"id":"call_1","function":{"name":"get_weather","arguments":""}},{"function":{"arguments":"{\"q\":1}"}}]}}]}"""
    ).toLmChunk.toolCalls
    assertEquals(calls.size, 2)
    assertEquals(calls(0).index, 0) // explicit index absent -> falls back to position
    assertEquals(calls(0).id, Some("call_1"))
    assertEquals(calls(0).name, Some("get_weather"))
    assertEquals(calls(0).argumentsFragment, Some(""))
    assertEquals(calls(1).index, 1)
    assertEquals(calls(1).argumentsFragment, Some("{\"q\":1}"))
  }

  test("decodes the choice-less final usage chunk into typed core fields") {
    val lm = decode("""{"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""").toLmChunk
    assertEquals(lm.text, "")
    val usage = lm.usage.get
    assertEquals(usage.promptTokens, 3L)
    assertEquals(usage.completionTokens, 2L)
    assertEquals(usage.totalTokens, 5L)
    assertEquals(usage.extras, Map.empty[dspy4s.lm.contracts.TokenCategory, Long])
  }

  test("total_tokens falls back to prompt + completion when absent") {
    val usage = decode("""{"usage":{"prompt_tokens":4,"completion_tokens":6}}""").toLmChunk.usage.get
    assertEquals(usage.totalTokens, 10L)
  }

  test("returns Left for malformed JSON") {
    assert(OpenAiStreamChunk.decode("{not json").isLeft)
  }
