package dspy4s.lm.providers

import munit.FunSuite

class OpenAiStreamChunkSuite extends FunSuite:

  private def decode(json: String): OpenAiStreamChunk =
    OpenAiStreamChunk.parse(json) match
      case Right(Some(chunk)) => chunk
      case other              => fail(s"no chunk: $other")

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
    assertEquals(
      decode("""{"choices":[{"delta":{"content":" there"},"finish_reason":"stop"}]}""").toLmChunk.finishReason,
      Some("stop")
    )
  }

  test("the typed codec reads the reasoning keys of a delta: a null is no value, and an empty text stays a text") {
    val delta =
      decode("""{"choices":[{"delta":{"reasoning_content":null,"reasoning":"two"}}]}""").choices.flatMap(_.delta)
    assertEquals(delta.map(d => (d.reasoningContent, d.reasoning)), Vector((None, Some("two"))))
    val empty =
      decode("""{"choices":[{"delta":{"reasoning_content":"","reasoning":"two"}}]}""").choices.flatMap(_.delta)
    assertEquals(empty.map(d => (d.reasoningContent, d.reasoning)), Vector((Some(""), Some("two"))))
    assertEquals(empty.map(_.reasoningText), Vector("two"))
  }

  test("a reasoning value that is not a text fails the typed codec, and the lenient reader drops only that value") {
    // The lenient reader keeps only the first choice. Thus one choice here shows that the typed codec rejected the text.
    val chunk =
      decode("""{"choices":[{"delta":{"content":"a","reasoning":{"effort":"low"}}},{"delta":{"content":"b"}}]}""")
    assertEquals(chunk.choices.size, 1)
    assertEquals(chunk.toLmChunk.text, "a")
    assertEquals(chunk.toLmChunk.reasoning, "")
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

  test("decodes the choice-less final usage chunk into core fields") {
    val lm = decode("""{"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""").toLmChunk
    assertEquals(lm.text, "")
    val usage = lm.usage.get
    assertEquals(usage.promptTokens, 3L)
    assertEquals(usage.completionTokens, 2L)
    assertEquals(usage.totalTokens, 5L)
    assertEquals(usage.extras, Map.empty[dspy4s.core.contracts.TokenCategory, Long])
  }

  test("total_tokens falls back to prompt + completion when absent") {
    val usage = decode("""{"usage":{"prompt_tokens":4,"completion_tokens":6}}""").toLmChunk.usage.get
    assertEquals(usage.totalTokens, 10L)
  }

  test("returns Left for malformed JSON") {
    assert(OpenAiStreamChunk.parse("{not json").isLeft)
  }
