package dspy4s.lm.providers

import dspy4s.lm.contracts.LmToolCallDelta
import dspy4s.lm.contracts.LmUsage
import zio.blocks.schema.DynamicValue

/** Typed view of a single OpenAI Chat Completions streaming chunk (`chat.completion.chunk`), decoded from one SSE
  * `data:` JSON object. Modeling the chunk as a datatype lets the client map named fields instead of poking the raw
  * `DynamicValue` by key throughout its streaming logic — all of that navigation lives here, next to the shape it
  * describes.
  *
  * Decoding is hand-written rather than `Schema.fromDynamicValue` on purpose: provider chunks are loose and highly
  * partial — every delta omits a different subset of fields, `finish_reason` arrives as JSON `null`, and unknown
  * fields (`id`, `object`, `role`, …) come and go. zio-blocks' derived decoder rejects both missing fields and
  * `null`, and represents `Option` as a tagged `Variant`, none of which matches raw wire JSON. So the navigation
  * helpers in [[DynamicJson]] are the right tool, confined to this one boundary. */
private[providers] final case class OpenAiStreamChunk(
    choices: Vector[OpenAiStreamChoice],
    usage: Option[LmUsage]
)

private[providers] final case class OpenAiStreamChoice(
    content: Option[String],
    finishReason: Option[String],
    toolCalls: Vector[LmToolCallDelta]
)

private[providers] object OpenAiStreamChunk:
  import DynamicJson.{field, asRecord, asString, asLong, asSequence}

  def decode(payload: DynamicValue): OpenAiStreamChunk =
    OpenAiStreamChunk(
      choices = field(payload, "choices").map(asSequence).getOrElse(Vector.empty).flatMap(decodeChoice),
      usage   = field(payload, "usage").flatMap(asRecord).map(decodeUsage)
    )

  private def decodeChoice(raw: DynamicValue): Option[OpenAiStreamChoice] =
    asRecord(raw).map { choice =>
      val delta = field(choice, "delta").flatMap(asRecord)
      OpenAiStreamChoice(
        content = delta.flatMap(d => field(d, "content")).flatMap(asString),
        finishReason = field(choice, "finish_reason").flatMap(asString),
        toolCalls = delta
          .flatMap(d => field(d, "tool_calls"))
          .map(asSequence)
          .map(decodeToolCalls)
          .getOrElse(Vector.empty)
      )
    }

  private def decodeToolCalls(entries: Vector[DynamicValue]): Vector[LmToolCallDelta] =
    entries.zipWithIndex.flatMap { case (raw, fallbackIdx) =>
      asRecord(raw).map { entry =>
        val function = field(entry, "function").flatMap(asRecord)
        LmToolCallDelta(
          index = field(entry, "index").flatMap(asLong).map(_.toInt).getOrElse(fallbackIdx),
          id = field(entry, "id").flatMap(asString),
          name = function.flatMap(f => field(f, "name")).flatMap(asString),
          argumentsFragment = function.flatMap(f => field(f, "arguments")).flatMap(asString)
        )
      }
    }

  private def decodeUsage(usage: DynamicValue.Record): LmUsage =
    val promptTokens = field(usage, "prompt_tokens").flatMap(asLong).getOrElse(0L)
    val completionTokens = field(usage, "completion_tokens").flatMap(asLong).getOrElse(0L)
    val totalTokens = field(usage, "total_tokens").flatMap(asLong).getOrElse(promptTokens + completionTokens)
    val details = usage.fields.iterator.collect {
      case (k, v) if asLong(v).isDefined => k -> asLong(v).get
    }.toMap
    LmUsage(
      totalTokens = totalTokens,
      promptTokens = promptTokens,
      completionTokens = completionTokens,
      details = details
    )
