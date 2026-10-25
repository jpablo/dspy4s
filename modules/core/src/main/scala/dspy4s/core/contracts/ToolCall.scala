package dspy4s.core.contracts

import dspy4s.core.collections.Traverse
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

/** A `Schema` for the `Record` case of `DynamicValue`.
  *
  * zio-blocks has a `Schema[DynamicValue]` for the dynamic catch-all, but it has no schema for each subtype. The
  * derivation cannot make one: the `Chunk[(String, DynamicValue)]` field of `DynamicValue.Record` has no matching
  * collection `Schema` on the derivation path (it resolves to `IndexedSeq`, which is not `Chunk`). Thus this given
  * narrows and widens the dynamic schema with `Schema.transform`. That is the only such combinator, because a `Schema`
  * converts in the two directions and has no `map` or `contramap` for one direction. With it, a case class can have a
  * `DynamicValue.Record` field and `derives Schema`. The narrowing throws when a value is not a `Record`. The call
  * sites here are safe, because the `args` of a tool call is always a JSON object (a record).
  */
given recordSchema: Schema[DynamicValue.Record] =
  Schema[DynamicValue].transform(
    {
      case record: DynamicValue.Record => record
      case other                       => throw new IllegalArgumentException(s"Expected a DynamicValue.Record, got: $other")
    },
    (record: DynamicValue.Record) => record
  )

/** A tool invocation requested by the model: the tool `name` and its `args` (the call's `arguments` object, decoded
  * from the provider's JSON into a `DynamicValue.Record` at the parse boundary, so it travels the tool pipeline as
  * structured data without a lossy `Any` round-trip).
  *
  * Single shared type across layers: the wire layer (`lm`) populates it from provider responses, the adapters read it,
  * and the decode path uses it as a `Vector[ToolCall]` output field (paired with
  * `dspy4s.signatures.Signature.markToolCalls`). Lives in `core` so both `lm` and `signatures` can name it, because
  * they do not depend on each other; the [[recordSchema]] given lets the `Schema` derivation keep the precise `args`
  * type.
  *
  * `id` is the call id of the provider (upstream `ToolCalls.ToolCall.id`). A provider needs it to match a tool result
  * with its call when a conversation history replays the call (see [[History]]). It is `None` for a call that the model
  * wrote as text, and the prompt of a text-mode tool call field does not ask the model for it.
  */
final case class ToolCall(name: String, args: DynamicValue.Record, id: Option[String] = None) derives Schema:

  /** The record form of this call: `name` and `args`, and `id` when the call has one. */
  def toRecord: DynamicValue.Record =
    DynamicValues.recordFromEntries(
      id.map(value => "id" := value).toVector ++ Vector[(String, DynamicValue)]("name" := name, "args" -> args)
    )

object ToolCall:

  /** A call from its record form. The function accepts the forms that upstream `_normalize_tool_call_dict` accepts:
    * `{name, args}`, `{name, arguments}`, and the provider form `{id, function: {name, arguments}}`. The id is under
    * `id` or `call_id`, and it can be absent. Arguments that are a JSON string are decoded. `None` when the record has
    * no tool name.
    */
  def fromRecord(record: DynamicValue.Record): Option[ToolCall] =
    def get(from: DynamicValue.Record, key: String): Option[DynamicValue] = DynamicValues.recordGet(from, key)
    def text(from: DynamicValue.Record, key: String): Option[String]      =
      get(from, key).flatMap(DynamicValues.asString).filter(_.nonEmpty)
    val function  = get(record, "function").collect { case nested: DynamicValue.Record => nested }
    val name      = function.flatMap(text(_, "name")).orElse(text(record, "name"))
    val arguments = function.flatMap(get(_, "arguments")).orElse(get(record, "args")).orElse(get(record, "arguments"))
    name.map { toolName =>
      ToolCall(
        name = toolName,
        args = argumentsRecord(arguments),
        id = text(record, "id").orElse(text(record, "call_id"))
      )
    }

  /** The calls of a tool calls value, in the forms that upstream `ToolCalls.validate_input` accepts
    * (`dspy/adapters/types/tool.py`):
    *   - a sequence of call records;
    *   - a record with a sequence of call records under the key `tool_calls`;
    *   - one call record that has `name` and `args` or `arguments`, or that has `function`.
    *
    * `None` for each other value, and when an entry of the sequence is not a call record.
    */
  def fromDynamic(value: DynamicValue): Option[Vector[ToolCall]] =
    value match
      case sequence: DynamicValue.Sequence => Traverse.option(sequence.elements) {
          case record: DynamicValue.Record => fromRecord(record)
          case _                           => None
        }
      case record: DynamicValue.Record => DynamicValues.recordGet(record, ToolCallsKey) match
          case Some(calls: DynamicValue.Sequence) => fromDynamic(calls)
          case Some(_)                            => None
          case None if isCallRecord(record)       => fromRecord(record).map(Vector(_))
          case None                               => None
      case _ => None

  /** The key of the list in the object form of a tool calls value (upstream `ToolCalls`). */
  private val ToolCallsKey: String = "tool_calls"

  /** `true` for a record in the form of one call (upstream `_is_tool_call_dict`). */
  private def isCallRecord(record: DynamicValue.Record): Boolean =
    def has(key: String): Boolean = DynamicValues.recordGet(record, key).isDefined
    (has("name") && (has("args") || has("arguments"))) || has("function")

  private def argumentsRecord(arguments: Option[DynamicValue]): DynamicValue.Record =
    arguments match
      case Some(record: DynamicValue.Record) => record
      case Some(other)                       => DynamicValues.asString(other).map(_.trim).filter(_.nonEmpty) match
          case Some(json) => DynamicValues.parseJsonRecord(json).getOrElse(DynamicValues.record("input" := json))
          case None       => DynamicValue.Record.empty
      case None => DynamicValue.Record.empty

/** The result of one tool call (upstream `ToolCallResults.ToolCallResult`).
  *
  * @param name
  *   the name of the tool
  * @param value
  *   the result. A string goes to the model as it is, and each other value goes as JSON.
  * @param callId
  *   the [[ToolCall.id]] of the call that this result answers
  * @param isError
  *   `true` when the tool failed and `value` describes the failure
  */
final case class ToolCallResult(
    name   : String,
    value  : DynamicValue,
    callId : Option[String] = None,
    isError: Boolean        = false
):

  /** The record form of this result, with the upstream keys: `call_id` (when the result has one), `name`, `value`, and
    * `is_error`.
    */
  def toRecord: DynamicValue.Record =
    DynamicValues.recordFromEntries(
      callId.map(id => "call_id" := id).toVector ++
        Vector[(String, DynamicValue)]("name" := name, "value" -> value, "is_error" := isError)
    )

object ToolCallResult:

  /** A result from its record form. `name` and `value` are necessary. `call_id` and `is_error` can be absent. */
  def fromRecord(record: DynamicValue.Record): Option[ToolCallResult] =
    for
      name  <- DynamicValues.recordGet(record, "name").flatMap(DynamicValues.asString)
      value <- DynamicValues.recordGet(record, "value")
    yield ToolCallResult(
      name = name,
      value = value,
      callId = DynamicValues.recordGet(record, "call_id").flatMap(DynamicValues.asString).filter(_.nonEmpty),
      isError = DynamicValues.recordGet(record, "is_error").exists {
        case DynamicValue.Primitive(PrimitiveValue.Boolean(flag)) => flag
        case _                                                    => false
      }
    )

  /** One result for each call, in the order of the calls (upstream `ToolCallResults.from_tool_calls_and_values`). Each
    * result takes the name and the id of its call. `isErrors` is empty, or it has one entry for each call.
    */
  def forCalls(
      calls   : Vector[ToolCall],
      values  : Vector[DynamicValue],
      isErrors: Vector[Boolean] = Vector.empty
  ): Either[DspyError, Vector[ToolCallResult]] =
    if calls.size != values.size then
      Left(ValidationError(s"`calls` and `values` must have the same length: ${calls.size} and ${values.size}."))
    else if isErrors.nonEmpty && isErrors.size != calls.size then
      Left(ValidationError(
        s"`isErrors` must be empty or have the length of `calls`: ${isErrors.size} and ${calls.size}."
      ))
    else
      Right(calls.zip(values).zipWithIndex.map { case ((call, value), index) =>
        ToolCallResult(name = call.name, value = value, callId = call.id, isError = isErrors.lift(index).contains(true))
      })
