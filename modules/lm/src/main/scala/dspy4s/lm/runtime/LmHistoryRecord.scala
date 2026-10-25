package dspy4s.lm.runtime

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.LmUsage
import dspy4s.core.contracts.ToolCall
import dspy4s.lm.contracts.ContentPart
import dspy4s.lm.contracts.LmCost
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.providers.ChatWire
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

import java.util.Locale
import java.util.UUID

/** The payload of the history entry of an LM call (upstream `finalize` in `dspy/clients/execution.py`).
  * [[ManagedLanguageModel]] writes one such entry for each call and each streamed call, with the component `lm:<id>`.
  *
  * The keys of a success, in this order:
  *   - `model`: the model of the request;
  *   - `model_type`: the mode of the model in lower case (`chat`, `text`, or `responses`);
  *   - `cache_hit`;
  *   - `messages`: one object for each message of the request, with `role` (lower case) and `content` (the text, else
  *     an empty string), then `tool_calls` (when the message has tool calls), `tool_call_id` and `name` (when the
  *     message has them), and `parts` (a list of `{"kind", "payload"}`, when the message has parts);
  *   - `kwargs`: the options of the request, with no top-level key that starts with `api_`, for example `api_key`
  *     (upstream `finalize`, and `BaseLM._record_response` in `dspy/clients/base_lm.py`). A nested key stays;
  *   - `outputs`: one object for each output, with `text` and `tool_calls`;
  *   - `usage`: `prompt_tokens`, `completion_tokens`, `total_tokens`, then each extra under its wire name, or `null`;
  *   - `cost`: the estimated cost in US dollars, or `null` when the estimate is unknown (decision L10);
  *   - `cost_details`: `{"kind": "estimate", "currency": "USD", "model", "source": "declared"}` for an estimate, else
  *     `{"kind": "unknown", "reason"}` (upstream `cost_details`);
  *   - `response_model`: the model name of the response, or `null`;
  *   - `uuid`: a new random UUID.
  *
  * A tool call is `{"id", "name", "args"}` ([[dspy4s.core.contracts.ToolCall.toRecord]]), with no `id` key when the
  * call has no id. A failure has `model`, `model_type`, `cache_hit` (`false`), `messages`, `kwargs`, `error_code`,
  * `error_message`, and `uuid`.
  *
  * The extras of the usage are in the alphabetical order of their wire names.
  */
object LmHistoryRecord:

  /** The payload for `response` to `request` of a model with the mode `mode`, with the cost estimate `cost`. */
  def success(
      request : LmRequest,
      mode    : LmMode,
      response: LmResponse,
      cacheHit: Boolean,
      cost    : LmCost
  ): DynamicValue.Record =
    DynamicValues.recordFromEntries(
      head(request, mode, cacheHit) ++ Vector[(String, DynamicValue)](
        "outputs"        -> DynamicValues.sequence(response.outputs.map(output)),
        "usage"          -> response.usage.fold[DynamicValue](DynamicValue.Null)(usage),
        "cost"           -> costValue(cost),
        "cost_details"   -> costDetails(cost),
        "response_model" -> response.modelName.fold[DynamicValue](DynamicValue.Null)(DynamicValues.string),
        "uuid"           := newUuid()
      )
    )

  private def costValue(cost: LmCost): DynamicValue =
    cost match
      case LmCost.Estimate(usd, _) => DynamicValue.Primitive(PrimitiveValue.Double(usd))
      case LmCost.Unknown(_)       => DynamicValue.Null

  private def costDetails(cost: LmCost): DynamicValue =
    cost match
      case LmCost.Estimate(_, model) =>
        DynamicValues.record("kind" := "estimate", "currency" := "USD", "model" := model, "source" := "declared")
      case LmCost.Unknown(reason) => DynamicValues.record("kind" := "unknown", "reason" := reason)

  /** The payload for a call of `request` to a model with the mode `mode` that failed with `error`. */
  def failure(request: LmRequest, mode: LmMode, error: DspyError): DynamicValue.Record =
    DynamicValues.recordFromEntries(
      head(request, mode, cacheHit = false) ++ Vector[(String, DynamicValue)](
        "error_code"    := error.code,
        "error_message" := error.message,
        "uuid"          := newUuid()
      )
    )

  /** The keys that a success and a failure share: `model`, `model_type`, `cache_hit`, `messages`, and `kwargs`. */
  private def head(request: LmRequest, mode: LmMode, cacheHit: Boolean): Vector[(String, DynamicValue)] =
    Vector(
      "model"      := request.model,
      "model_type" := mode.toString.toLowerCase(Locale.ROOT),
      "cache_hit"  := cacheHit,
      "messages"   -> DynamicValues.sequence(request.messages.map(message)),
      "kwargs"     -> DynamicValues.recordFilterKeys(request.options, key => !key.startsWith("api_"))
    )

  private def message(value: Message): DynamicValue =
    DynamicValues.recordFromEntries(
      Vector[(String, DynamicValue)]("role" := ChatWire.roleName(value.role), "content" := value.text.getOrElse(""))
        ++ Option.when(value.toolCalls.nonEmpty)("tool_calls" -> toolCalls(value.toolCalls))
        ++ value.toolCallId.map(id => "tool_call_id" := id)
        ++ value.name.map(name => "name" := name)
        ++ Option.when(value.parts.nonEmpty)("parts" -> DynamicValues.sequence(value.parts.map(part)))
    )

  private def part(value: ContentPart): DynamicValue =
    DynamicValues.record("kind" := value.kind, "payload" := value.payload)

  private def output(value: LmOutput): DynamicValue =
    DynamicValues.record("text" := value.text, "tool_calls" -> toolCalls(value.toolCalls))

  private def toolCalls(calls: Vector[ToolCall]): DynamicValue =
    DynamicValues.sequence(calls.map(_.toRecord))

  private def usage(value: LmUsage): DynamicValue =
    val extras = value.extras.toVector
      .map((category, count) => category.wireName -> count)
      .sortBy(_._1)
      .map((name, count) => name := count)
    DynamicValues.recordFromEntries(
      Vector[(String, DynamicValue)](
        "prompt_tokens"     := value.promptTokens,
        "completion_tokens" := value.completionTokens,
        "total_tokens"      := value.totalTokens
      ) ++ extras
    )

  private def newUuid(): String =
    UUID.randomUUID().toString
