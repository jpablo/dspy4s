package dspy4s.lm.providers

import munit.FunSuite

class OpenAiStreamChunkSuite extends FunSuite:

  private def decode(json: String): OpenAiStreamChunk =
    OpenAiStreamChunk.decode(DynamicJson.decode(json).toOption.get)

  test("decodes content delta, tolerating null finish_reason and unknown fields") {
    val chunk = decode(
      """{"id":"x","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"},"finish_reason":null}]}"""
    )
    val choice = chunk.choices.head
    assertEquals(choice.content, Some("Hi"))
    assertEquals(choice.finishReason, None)
    assertEquals(choice.toolCalls, Vector.empty)
    assertEquals(chunk.usage, None)
  }

  test("decodes finish_reason when present") {
    val chunk = decode("""{"choices":[{"delta":{"content":" there"},"finish_reason":"stop"}]}""")
    assertEquals(chunk.choices.head.finishReason, Some("stop"))
  }

  test("decodes tool-call deltas with index fallback and partial function fragments") {
    val chunk = decode(
      """{"choices":[{"delta":{"tool_calls":[{"id":"call_1","function":{"name":"get_weather","arguments":""}},{"function":{"arguments":"{\"q\":1}"}}]}}]}"""
    )
    val calls = chunk.choices.head.toolCalls
    assertEquals(calls.size, 2)
    assertEquals(calls(0).index, 0) // explicit index absent -> falls back to position
    assertEquals(calls(0).id, Some("call_1"))
    assertEquals(calls(0).name, Some("get_weather"))
    assertEquals(calls(0).argumentsFragment, Some(""))
    assertEquals(calls(1).index, 1)
    assertEquals(calls(1).argumentsFragment, Some("{\"q\":1}"))
  }

  test("decodes the choice-less final usage chunk including all numeric fields as details") {
    val chunk = decode("""{"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5,"cached_tokens":1}}""")
    assertEquals(chunk.choices, Vector.empty)
    val usage = chunk.usage.get
    assertEquals(usage.promptTokens, 3L)
    assertEquals(usage.completionTokens, 2L)
    assertEquals(usage.totalTokens, 5L)
    assertEquals(usage.details.get("cached_tokens"), Some(1L))
  }

  test("total_tokens falls back to prompt + completion when absent") {
    val chunk = decode("""{"usage":{"prompt_tokens":4,"completion_tokens":6}}""")
    assertEquals(chunk.usage.get.totalTokens, 10L)
  }
