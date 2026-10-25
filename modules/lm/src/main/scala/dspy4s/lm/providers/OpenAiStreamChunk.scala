package dspy4s.lm.providers

import dspy4s.core.contracts.DspyError
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmToolCallDelta
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.NameMapper
import zio.blocks.schema.PrimitiveValue
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonCodecDeriver

/** Wire model of one OpenAI Chat Completions streaming chunk (`chat.completion.chunk`), parsed straight from the SSE
  * `data:` JSON by a derived `JsonCodec` — no by-hand `DynamicValue` navigation. The case classes mirror the wire shape
  * (camelCase fields bridged to the JSON's snake_case by the `SnakeCase` name mapper); the codec is lenient exactly
  * where provider chunks are loose: `Option`/defaulted fields decode from absent or `null`, and unknown fields (`id`,
  * `object`, `role`, `type`, …) are ignored. [[OpenAiStreamChunk.parse]] reads a chunk that the codec cannot read, for
  * example a chunk with one field of the wrong type, with [[OpenAiStreamChunk.lenient]], which reads the fields one at
  * a time.
  *
  * The domain mapping to `LmChunk` lives on the DTO, reading fields only.
  *
  * @param error
  *   the `error` value of an error event. It stays JSON, because the providers do not agree on its shape: a code can be
  *   a number, and the value can be a string.
  */
private[providers] final case class OpenAiStreamChunk(
    choices: Vector[OpenAiStreamChoice] = Vector.empty,
    usage  : Option[OpenAiUsage]        = None,
    error  : Option[DynamicValue]       = None
) derives Schema:

  /** The `error` object of an error event. An `error` value that is not an object does not make an error event
    * (upstream `parse_stream_events`).
    */
  def errorObject: Option[DynamicValue.Record] =
    error.flatMap(DynamicJson.asRecord)

  /** Text, finish reason and tool-call deltas come from the first choice (OpenAI streams one choice per chunk); usage
    * rides the final, choice-less chunk.
    */
  def toLmChunk: LmChunk =
    val choice = choices.headOption
    LmChunk(
      text = choice.flatMap(_.delta).flatMap(_.content).getOrElse(""),
      finishReason = choice.flatMap(_.finishReason),
      usage = usage.map(_.toLmUsage),
      toolCalls = choice.flatMap(_.delta).map(_.toLmToolCallDeltas).getOrElse(Vector.empty)
    )

private[providers] final case class OpenAiStreamChoice(
    delta       : Option[OpenAiStreamDelta] = None,
    finishReason: Option[String]            = None
) derives Schema

private[providers] final case class OpenAiStreamDelta(
    content  : Option[String]               = None,
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
    index   : Option[Int]                  = None,
    id      : Option[String]               = None,
    function: Option[OpenAiStreamFunction] = None
) derives Schema

private[providers] final case class OpenAiStreamFunction(
    name     : Option[String] = None,
    arguments: Option[String] = None
) derives Schema

private[providers] object OpenAiStreamChunk:
  private val codec = Schema[OpenAiStreamChunk].derive(JsonCodecDeriver.withFieldNameMapper(NameMapper.SnakeCase))

  /** The finish reason that [[lenient]] gives for a `finish_reason` value that is not a string. */
  private val stop = "stop"

  /** The chunk of one SSE `data:` JSON text. The typed codec reads the text first. A text that the codec cannot read
    * gives the chunk of [[lenient]] when the text is a JSON object, and `None` when it is an other JSON value (upstream
    * skips a JSON value that is not an object). A text that is not JSON is a `ParseError`. The stream parser reads the
    * error object of each chunk ([[OpenAiStreamChunk.errorObject]]), so one check covers the two readers.
    */
  def parse(data: String): Either[DspyError, Option[OpenAiStreamChunk]] =
    codec.decode(data) match
      case Right(chunk) => Right(Some(chunk))
      case Left(_)      => DynamicJson.decode(data).map {
          case record: DynamicValue.Record => Some(lenient(record))
          case _                           => None
        }

  /** The chunk of a JSON object that the typed codec cannot read. Each field of the wrong type is dropped, and the
    * other fields stay (upstream `parse_stream_events` in `dspy/_vendor/lm15/providers/openai_chat.py` reads each field
    * with an `isinstance` check):
    *
    *   - The choice is the first element of `choices` when `choices` is an array and the element is an object.
    *   - `delta` must be an object, and `content` a string.
    *   - Each element of `tool_calls` that is an object gives a tool call. An `index` that is not a number (or the text
    *     of a number) gives the position of the call in the array, as the typed codec does. `id`, `function.name`, and
    *     `function.arguments` follow `str(value or "")` ([[PythonStr.orEmpty]]): a string stays, a value that is false
    *     in Python gives no value, and each other value gives the text of Python `str`, for example `True` or `1.5`.
    *   - A string `finish_reason` stays. Each other value that is not null and not `false` gives `stop` (upstream gives
    *     `stop` for a finish reason that is not in `_FINISH_REASON_MAP`).
    *   - `usage` must be an object. Its counts follow [[OpenAiUsage.fromDynamic]].
    *   - `error` stays as it is, as in the typed codec.
    */
  def lenient(chunk: DynamicValue.Record): OpenAiStreamChunk =
    val choice = DynamicJson.field(chunk, WireKeys.choices).map(DynamicJson.asSequence).flatMap(_.headOption)
      .flatMap(DynamicJson.asRecord)
    OpenAiStreamChunk(
      choices = choice.map(lenientChoice).toVector,
      usage = DynamicJson.field(chunk, WireKeys.usage).flatMap(DynamicJson.asRecord).map(OpenAiUsage.fromDynamic),
      error = DynamicJson.field(chunk, WireKeys.error)
    )

  private def lenientChoice(choice: DynamicValue.Record): OpenAiStreamChoice =
    OpenAiStreamChoice(
      delta = DynamicJson.field(choice, WireKeys.delta).flatMap(DynamicJson.asRecord).map(lenientDelta),
      finishReason = DynamicJson.field(choice, WireKeys.finishReason).flatMap(finishReasonOf)
    )

  private def lenientDelta(delta: DynamicValue.Record): OpenAiStreamDelta =
    val calls = DynamicJson.field(delta, WireKeys.toolCalls).map(DynamicJson.asSequence).getOrElse(Vector.empty)
    OpenAiStreamDelta(
      content = textOf(delta, WireKeys.content),
      toolCalls =
        calls.zipWithIndex.flatMap((call, position) => DynamicJson.asRecord(call).map(lenientCall(_, position)))
    )

  private def lenientCall(call: DynamicValue.Record, position: Int): OpenAiStreamToolCall =
    val function = DynamicJson.field(call, WireKeys.function).flatMap(DynamicJson.asRecord)
    OpenAiStreamToolCall(
      index = Some(DynamicJson.field(call, WireKeys.index).flatMap(DynamicJson.asLong).fold(position)(_.toInt)),
      id = pythonTextOf(call, WireKeys.id),
      function = function.map(f =>
        OpenAiStreamFunction(name = pythonTextOf(f, WireKeys.name), arguments = pythonTextOf(f, WireKeys.arguments))
      )
    )

  private def finishReasonOf(value: DynamicValue): Option[String] =
    value match
      case _: DynamicValue.Null.type                             => None
      case DynamicValue.Primitive(PrimitiveValue.Boolean(false)) => None
      case other                                                 => DynamicJson.asString(other).orElse(Some(stop))

  private def textOf(record: DynamicValue.Record, key: String): Option[String] =
    DynamicJson.field(record, key).flatMap(DynamicJson.asString)

  /** The value of `key` with the rule `str(value or "")` of upstream `parse_stream_events`. */
  private def pythonTextOf(record: DynamicValue.Record, key: String): Option[String] =
    DynamicJson.field(record, key).flatMap(PythonStr.orEmpty)
