package dspy4s.lm.providers

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.:=
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmToolCallDelta
import dspy4s.core.contracts.ToolCall
import zio.blocks.schema.DynamicValue

import scala.collection.mutable

/** Assembles the tool-call deltas of a stream into complete [[ToolCall]]s, and decodes the arguments of a provider tool
  * call. The provider package holds it, because the plain reply parsers of [[ChatWire]] and [[ResponsesWire]] use the
  * same decode ([[decodeArguments]]).
  *
  * OpenAI sends the function `name` and the call `id` once (usually on the first delta of an `index`), and the
  * `arguments` JSON text in fragments on the next deltas. The assembler joins the deltas by `index`, in the order in
  * which each index first came, and decodes the joined arguments into a `DynamicValue.Record`. Arguments that are not
  * valid JSON give `{input: raw}`, as on the plain path ([[decodeArguments]]). A stream in the Responses mode gives
  * `{partial_json: raw}` for such a text (see [[assemble]]).
  *
  * Each call keeps its `id`, because a tool result must name it. An empty id counts as no id, as in the reply parser of
  * [[ChatWire]].
  */
object ToolCallAssembler:

  /** The keys of the records that hold arguments that are not a JSON object. They are not keys of the wire. */
  private object Keys:
    final val input       = "input"
    final val value       = "value"
    final val partialJson = "partial_json"

  private final class Accumulator(val index: Int):
    var id: Option[String]       = None
    var name: Option[String]     = None
    val arguments: StringBuilder = new StringBuilder

    def merge(delta: LmToolCallDelta): Unit =
      if id.isEmpty then id = idOf(delta)
      if name.isEmpty then name = delta.name
      delta.argumentsFragment.foreach(arguments.append)

  /** The tool calls of `deltas`, from a stream in `mode`. In the chat mode, the arguments go through
    * [[parseArguments]]. In the Responses mode, the arguments go through [[partialArguments]] (upstream lm15
    * `StreamAccumulator`).
    */
  def assemble(deltas: Iterable[LmToolCallDelta], mode: LmMode = LmMode.Chat): Vector[ToolCall] =
    val arguments: String => DynamicValue.Record = mode match
      case LmMode.Responses          => partialArguments
      case LmMode.Chat | LmMode.Text => parseArguments(_)
    val ordered = mutable.ArrayBuffer.empty[Accumulator]
    val active  = mutable.HashMap.empty[Int, Accumulator]
    deltas.foreach { delta =>
      val acc = active.get(delta.index) match
        case Some(current) if !startsNewCall(current, delta) => current
        case _                                               =>
          val fresh = new Accumulator(delta.index)
          ordered += fresh
          active(delta.index) = fresh
          fresh
      acc.merge(delta)
    }
    ordered.iterator.flatMap { acc =>
      acc.name.map { name =>
        ToolCall(name = name, args = arguments(acc.arguments.toString), id = acc.id)
      }
    }.toVector

  /** Some OpenAI-compatible servers omit `tool_calls[].index`, so different calls that follow each other all come at
    * the fallback index 0. Thus a delta with an `id` or a `name` that is different from the `id` or the `name` of the
    * current call of the index starts a new call. A stream that follows the specification has no such conflict in one
    * index, so this rule does not change it. A fragment with no id and no name goes to the last call of the index.
    */
  private def startsNewCall(current: Accumulator, delta: LmToolCallDelta): Boolean =
    idOf(delta).exists(id => current.id.exists(_ != id)) ||
      delta.name.exists(name => current.name.exists(_ != name))

  /** The id of a delta. An empty id counts as no id: it does not set the id of a call, and it does not conflict with
    * the id of the current call.
    */
  private def idOf(delta: LmToolCallDelta): Option[String] = delta.id.filter(_.nonEmpty)

  /** The arguments of a provider tool call (the `arguments` value of a chat tool call or of a Responses `function_call`
    * item) as a record. An object stays as it is. A text that is not blank goes through [[parseArguments]], as on the
    * stream path. Each other value gives `{value: <value>}`. No value gives an empty record.
    *
    * @param notJson
    *   the record of a text that is not JSON. The default is the chat rule, `{input: <text>}`. The Responses path gives
    *   an empty record (upstream `responses_outputs` in `dspy/clients/legacy_outputs.py`).
    */
  private[providers] def decodeArguments(
      raw    : Option[DynamicValue],
      notJson: String => DynamicValue.Record = inputRecord
  ): DynamicValue.Record =
    raw match
      case Some(rec: DynamicValue.Record) => rec
      case Some(other)                    => DynamicValues.asString(other).filter(_.trim.nonEmpty) match
          // OpenAI sends `arguments` as the text of a JSON value. The parser decodes it into a record, because the
          // arguments of a `ToolCall` are a record. Text that is not JSON goes to `notJson` in parseArguments.
          case Some(text) => parseArguments(text, notJson)
          case None       => DynamicValues.record(Keys.value -> other)
      case None => DynamicValue.Record.empty

  /** The record of the `arguments` text of a tool call. A text that is not valid JSON gives `notJson` of the trimmed
    * text (`{input: raw}` by default). A JSON value that is not an object gives `{value: json}`. [[decodeArguments]]
    * uses it for a response, so the stream path and the response path decode in the same way.
    */
  private def parseArguments(
      raw    : String,
      notJson: String => DynamicValue.Record = inputRecord
  ): DynamicValue.Record =
    val trimmed = raw.trim
    if trimmed.isEmpty then DynamicValue.Record.empty else jsonArguments(trimmed, notJson(trimmed))

  /** The arguments of a call in a Responses stream (upstream lm15 `_parse_json_best_effort` in
    * `dspy/_vendor/lm15/result.py`): an empty text gives an empty record, and a text that is not JSON gives
    * `{partial_json: <text>}`. The text keeps its white space, so a text of white space only is not JSON. Each other
    * text follows [[parseArguments]].
    */
  private def partialArguments(raw: String): DynamicValue.Record =
    if raw.isEmpty then DynamicValue.Record.empty
    else jsonArguments(raw, DynamicValues.record(Keys.partialJson := raw))

  /** The record of a JSON text: an object stays, and each other JSON value gives `{value: json}`. A text that is not
    * JSON gives `notJson`.
    */
  private def jsonArguments(text: String, notJson: => DynamicValue.Record): DynamicValue.Record =
    DynamicJson.decode(text) match
      case Right(rec: DynamicValue.Record) => rec
      case Right(other)                    => DynamicValues.record(Keys.value -> other)
      case Left(_)                         => notJson

  /** The chat rule for arguments that are not JSON: `{input: <text>}`. */
  private def inputRecord(text: String): DynamicValue.Record =
    DynamicValues.record(Keys.input := text)
