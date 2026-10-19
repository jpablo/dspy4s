package dspy4s.lm.runtime

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.LmUsage
import dspy4s.core.contracts.ToolCall
import dspy4s.lm.contracts.ContentPart
import dspy4s.lm.contracts.LmCost
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import zio.blocks.chunk.Chunk
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

import java.util.Locale
import java.util.UUID

/** The payload of the history entry of an LM call (upstream `finalize` in `dspy/clients/execution.py`).
  * [[ManagedLanguageModel]] writes one such entry for each call and each streamed call, with the component `lm:<id>`.
  *
  * The keys of a success, in this order:
  *   - `model`: the model of the request;
  *   - `model_type`: the mode of the request in lower case (`chat`, `text`, or `responses`);
  *   - `cache_hit`;
  *   - `messages`: one object for each message of the request, with `role` (lower case) and `content` (the text, else
  *     an empty string), then `tool_calls` (when the message has tool calls), `tool_call_id` and `name` (when the
  *     message has them), and `parts` (a list of `{"kind", "payload"}`, when the message has parts);
  *   - `kwargs`: the options of the request;
  *   - `outputs`: one object for each output, with `text` and `tool_calls`;
  *   - `usage`: `prompt_tokens`, `completion_tokens`, `total_tokens`, then each extra under its wire name, or `null`;
  *   - `cost`: the estimated cost in US dollars, or `null` when the estimate is unknown (decision L10);
  *   - `cost_details`: `{"kind": "estimate", "currency": "USD", "model", "source": "declared"}` for an estimate, else
  *     `{"kind": "unknown", "reason"}` (upstream `cost_details`);
  *   - `response_model`: the model name of the response, or `null`;
  *   - `uuid`: a new random UUID.
  *
  * A tool call is `{"name", "args", "id"}`, with no `id` key when the call has no id. A failure has `model`,
  * `model_type`, `cache_hit` (`false`), `messages`, `kwargs`, `error_code`, `error_message`, and `uuid`.
  *
  * The extras of the usage are in the alphabetical order of their wire names.
  */
object LmHistoryRecord:

  /** The payload for `response` to `request`, with the cost estimate `cost`. */
  def success(request: LmRequest, response: LmResponse, cacheHit: Boolean, cost: LmCost): DynamicValue.Record =
    DynamicValues.recordFromEntries(
      head(request, cacheHit) ++ Vector[(String, DynamicValue)](
        "outputs"        -> sequence(response.outputs.map(output)),
        "usage"          -> response.usage.fold[DynamicValue](DynamicValue.Null)(usage),
        "cost"           -> costValue(cost),
        "cost_details"   -> costDetails(cost),
        "response_model" -> response.modelName.fold[DynamicValue](DynamicValue.Null)(text),
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

  /** The payload for a call of `request` that failed with `error`. */
  def failure(request: LmRequest, error: DspyError): DynamicValue.Record =
    DynamicValues.recordFromEntries(
      head(request, cacheHit = false) ++ Vector[(String, DynamicValue)](
        "error_code"    := error.code,
        "error_message" := error.message,
        "uuid"          := newUuid()
      )
    )

  /** The keys that a success and a failure share: `model`, `model_type`, `cache_hit`, `messages`, and `kwargs`. */
  private def head(request: LmRequest, cacheHit: Boolean): Vector[(String, DynamicValue)] =
    Vector(
      "model"      := request.model,
      "model_type" := lowerCase(request.mode.toString),
      "cache_hit"  := cacheHit,
      "messages"   -> sequence(request.messages.map(message)),
      "kwargs"     -> request.options
    )

  private def message(value: Message): DynamicValue =
    DynamicValues.recordFromEntries(
      Vector[(String, DynamicValue)]("role" := lowerCase(value.role.toString), "content" := value.text.getOrElse(""))
        ++ Option.when(value.toolCalls.nonEmpty)("tool_calls" -> sequence(value.toolCalls.map(toolCall)))
        ++ value.toolCallId.map(id => "tool_call_id" := id)
        ++ value.name.map(name => "name" := name)
        ++ Option.when(value.parts.nonEmpty)("parts" -> sequence(value.parts.map(part)))
    )

  private def part(value: ContentPart): DynamicValue =
    DynamicValues.record("kind" := value.kind, "payload" := value.payload)

  private def output(value: LmOutput): DynamicValue =
    DynamicValues.record("text" := value.text, "tool_calls" -> sequence(value.toolCalls.map(toolCall)))

  private def toolCall(call: ToolCall): DynamicValue =
    DynamicValues.recordFromEntries(
      Vector[(String, DynamicValue)]("name" := call.name, "args" -> call.args) ++ call.id.map(id => "id" := id)
    )

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

  private def sequence(values: Vector[DynamicValue]): DynamicValue =
    DynamicValue.Sequence(Chunk.from(values))

  private def text(value: String): DynamicValue =
    DynamicValue.Primitive(PrimitiveValue.String(value))

  private def lowerCase(value: String): String =
    value.toLowerCase(Locale.ROOT)

  private def newUuid(): String =
    UUID.randomUUID().toString
