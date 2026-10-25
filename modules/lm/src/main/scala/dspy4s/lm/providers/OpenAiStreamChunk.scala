package dspy4s.lm.providers

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmToolCallDelta
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.NameMapper
import zio.blocks.schema.PrimitiveValue
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonCodecDeriver

/** One chunk of an OpenAI Chat Completions stream (`chat.completion.chunk`). A derived `JsonCodec` reads the chunk from
  * the JSON text of the SSE `data:` line. The case classes have the shape of the wire, and the `SnakeCase` name mapper
  * gives the snake_case keys of their camelCase fields. The codec accepts the loose parts of a provider chunk: an
  * `Option` field or a field with a default decodes from an absent key or from `null`, and the codec ignores the keys
  * that it does not know (`id`, `object`, `role`, `type`, and others). [[OpenAiStreamChunk.parse]] reads a chunk that
  * the codec cannot read with [[OpenAiStreamChunk.lenient]], which reads the fields one at a time. An example of such a
  * chunk is a chunk with one field of the wrong type.
  *
  * [[toLmChunk]] gives the `LmChunk` of the chunk. It only reads the fields.
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
    error.flatMap(DynamicValues.asRecord)

  /** The text, the finish reason, and the tool-call deltas come from the first choice: OpenAI sends one choice in each
    * chunk. The usage comes in the last chunk, which has no choice.
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

  /** OpenAI does not send the `index` of each delta. A delta with no `index` gets its position in the array. */
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
    val choice = DynamicValues.sequenceAt(chunk, WireKeys.choices).headOption.flatMap(DynamicValues.asRecord)
    OpenAiStreamChunk(
      choices = choice.map(lenientChoice).toVector,
      usage =
        DynamicValues.recordGet(chunk, WireKeys.usage).flatMap(DynamicValues.asRecord).map(OpenAiUsage.fromDynamic),
      error = DynamicValues.recordGet(chunk, WireKeys.error)
    )

  private def lenientChoice(choice: DynamicValue.Record): OpenAiStreamChoice =
    OpenAiStreamChoice(
      delta = DynamicValues.recordGet(choice, WireKeys.delta).flatMap(DynamicValues.asRecord).map(lenientDelta),
      finishReason = DynamicValues.recordGet(choice, WireKeys.finishReason).flatMap(finishReasonOf)
    )

  private def lenientDelta(delta: DynamicValue.Record): OpenAiStreamDelta =
    val calls = DynamicValues.sequenceAt(delta, WireKeys.toolCalls)
    OpenAiStreamDelta(
      content = textOf(delta, WireKeys.content),
      toolCalls =
        calls.zipWithIndex.flatMap((call, position) => DynamicValues.asRecord(call).map(lenientCall(_, position)))
    )

  private def lenientCall(call: DynamicValue.Record, position: Int): OpenAiStreamToolCall =
    val function = DynamicValues.recordGet(call, WireKeys.function).flatMap(DynamicValues.asRecord)
    OpenAiStreamToolCall(
      index = Some(DynamicValues.recordGet(call, WireKeys.index).flatMap(DynamicValues.asLong).fold(position)(_.toInt)),
      id = pythonTextOf(call, WireKeys.id),
      function = function.map(f =>
        OpenAiStreamFunction(name = pythonTextOf(f, WireKeys.name), arguments = pythonTextOf(f, WireKeys.arguments))
      )
    )

  private def finishReasonOf(value: DynamicValue): Option[String] =
    value match
      case _: DynamicValue.Null.type                             => None
      case DynamicValue.Primitive(PrimitiveValue.Boolean(false)) => None
      case other                                                 => DynamicValues.asString(other).orElse(Some(stop))

  private def textOf(record: DynamicValue.Record, key: String): Option[String] =
    DynamicValues.recordGet(record, key).flatMap(DynamicValues.asString)

  /** The value of `key` with the rule `str(value or "")` of upstream `parse_stream_events`. */
  private def pythonTextOf(record: DynamicValue.Record, key: String): Option[String] =
    DynamicValues.recordGet(record, key).flatMap(PythonStr.orEmpty)
