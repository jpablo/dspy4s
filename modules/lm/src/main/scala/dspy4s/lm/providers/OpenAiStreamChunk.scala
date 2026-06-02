package dspy4s.lm.providers

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.ParseError
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmToolCallDelta
import dspy4s.lm.contracts.LmUsage
import zio.blocks.schema.NameMapper
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonCodecDeriver

/** Typed wire model of one OpenAI Chat Completions streaming chunk (`chat.completion.chunk`), parsed straight from
  * the SSE `data:` JSON by a derived `JsonCodec` — no by-hand `DynamicValue` navigation. The case classes mirror
  * the wire shape (camelCase fields bridged to the JSON's snake_case by the `SnakeCase` name mapper); the codec is
  * lenient exactly where provider chunks are loose: `Option`/defaulted fields decode from absent or `null`, and
  * unknown fields (`id`, `object`, `role`, `type`, …) are ignored.
  *
  * The domain mapping to `LmChunk` lives on the DTO, reading typed fields only. */
private[providers] final case class OpenAiStreamChunk(
    choices: Vector[OpenAiStreamChoice] = Vector.empty,
    usage: Option[OpenAiStreamUsage] = None
) derives Schema:

  /** Text, finish reason and tool-call deltas come from the first choice (OpenAI streams one choice per chunk);
    * usage rides the final, choice-less chunk. */
  def toLmChunk: LmChunk =
    val choice = choices.headOption
    LmChunk(
      text = choice.flatMap(_.delta).flatMap(_.content).getOrElse(""),
      finishReason = choice.flatMap(_.finishReason),
      usage = usage.map(_.toLmUsage),
      toolCalls = choice.flatMap(_.delta).map(_.toLmToolCallDeltas).getOrElse(Vector.empty)
    )

private[providers] final case class OpenAiStreamChoice(
    delta: Option[OpenAiStreamDelta] = None,
    finishReason: Option[String] = None
) derives Schema

private[providers] final case class OpenAiStreamDelta(
    content: Option[String] = None,
    toolCalls: Vector[OpenAiStreamToolCall] = Vector.empty
) derives Schema:

  /** OpenAI omits the explicit `index` on some deltas; fall back to the array position, matching the prior parser. */
  def toLmToolCallDeltas: Vector[LmToolCallDelta] =
    toolCalls.zipWithIndex.map { case (call, fallbackIdx) =>
      LmToolCallDelta(
        index = call.index.getOrElse(fallbackIdx),
        id = call.id,
        name = call.function.flatMap(_.name),
        argumentsFragment = call.function.flatMap(_.arguments)
      )
    }

private[providers] final case class OpenAiStreamToolCall(
    index: Option[Int] = None,
    id: Option[String] = None,
    function: Option[OpenAiStreamFunction] = None
) derives Schema

private[providers] final case class OpenAiStreamFunction(
    name: Option[String] = None,
    arguments: Option[String] = None
) derives Schema

private[providers] final case class OpenAiStreamUsage(
    promptTokens: Option[Long] = None,
    completionTokens: Option[Long] = None,
    totalTokens: Option[Long] = None
) derives Schema:

  /** `details` carries the present token counts (the prior parser collected the top-level numeric usage fields,
    * which for OpenAI are exactly these three). `totalTokens` falls back to prompt + completion when absent. */
  def toLmUsage: LmUsage =
    val prompt = promptTokens.getOrElse(0L)
    val completion = completionTokens.getOrElse(0L)
    val details = Vector(
      "prompt_tokens"     -> promptTokens,
      "completion_tokens" -> completionTokens,
      "total_tokens"      -> totalTokens
    ).collect { case (k, Some(v)) => k -> v }.toMap
    LmUsage(
      totalTokens = totalTokens.getOrElse(prompt + completion),
      promptTokens = prompt,
      completionTokens = completion,
      details = details
    )

private[providers] object OpenAiStreamChunk:
  private val codec = Schema[OpenAiStreamChunk].derive(JsonCodecDeriver.withFieldNameMapper(NameMapper.SnakeCase))

  /** Parse one SSE `data:` JSON object into the typed chunk. */
  def decode(json: String): Either[DspyError, OpenAiStreamChunk] =
    codec.decode(json) match
      case Right(chunk) => Right(chunk)
      case Left(err)    => Left(ParseError("json", s"Invalid OpenAI stream chunk: ${err.toString.take(200)}"))
