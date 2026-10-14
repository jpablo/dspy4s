package dspy4s.core.contracts

import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue
import zio.blocks.schema.Schema

import java.nio.charset.StandardCharsets
import java.util.Locale

/** Renders LM [[HistoryEntry]] values as upstream `pretty_print_history` (`dspy/utils/inspect_history.py`), with no
  * colors. The payload keys are the keys that `LmHistoryRecord` writes (`dspy4s.lm.runtime.LmHistoryRecord`).
  *
  * The block of one entry has these lines, in this order:
  *   - `[<timestamp>]`, the timestamp of the entry;
  *   - for each object of `messages` (else one user message with the text of `prompt`): `<Role> message:` with the role
  *     in capitals at the start (`System`, `User`, `Assistant`, `Tool`), the stripped `content`, one
  *     `<<kind>: <n> characters>` line for each object of `parts`, and the tool calls of the message;
  *   - when the first object of `outputs` has text: `Response:` and the stripped text;
  *   - the tool calls of the first output;
  *   - when `outputs` has more than one object: `(and <n> other completions)`;
  *   - for an entry with `error_code`: `Error (<error_code>): <error_message>`.
  *
  * A list of tool calls is a `Tool calls:` line, then one `<name>: <args as JSON>` line for each call.
  *
  * The line ends are the line ends of upstream: the text of each upstream `print` call is the same, with no colors.
  */
object HistoryRenderer:

  private val jsonCodec = Schema.dynamic.jsonCodec

  /** Render `entries` (already limited to the last n by the caller) as a single string. Empty input yields a short "no
    * history" notice rather than an empty string, so callers can print the result unconditionally.
    */
  def render(entries: Vector[HistoryEntry]): String =
    if entries.isEmpty then "No LM history recorded."
    else (entries.flatMap(block) :+ line("\n\n\n")).mkString

  /** The text of one entry. Each element is the text of one upstream `print`, with its line ends. */
  private def block(entry: HistoryEntry): Vector[String] =
    val payload = entry.payload
    Vector(line("\n\n\n"), label(s"[${entry.timestamp}]")) ++
      messages(payload).flatMap(message) ++
      outputs(sequence(payload, "outputs")) ++
      error(payload)

  /** The objects of `messages`. When there is none, one user message with the `prompt` of the entry. */
  private def messages(payload: DynamicValue.Record): Vector[DynamicValue.Record] =
    val listed = records(sequence(payload, "messages"))
    if listed.nonEmpty then listed
    else
      Vector(DynamicValues.recordFromEntries(
        Vector[(String, DynamicValue)]("role" -> text("user")) ++ field(payload, "prompt").map("content" -> _)
      ))

  private def message(value: DynamicValue.Record): Vector[String] =
    val role = string(value, "role").getOrElse("unknown")
    Vector(label(s"${capitalized(role)} message:")) ++
      string(value, "content").map(content => line(content.strip())).toVector ++
      records(sequence(value, "parts")).map(part) ++
      toolCalls(sequence(value, "tool_calls")) :+
      line("\n")

  /** The line of one content part: its kind and the length of its payload. */
  private def part(value: DynamicValue.Record): String =
    val kind   = string(value, "kind").getOrElse("part")
    val length = string(value, "payload").fold(0)(payload => payload.codePointCount(0, payload.length))
    label(s"<$kind: $length characters>")

  /** The response and the tool calls of the first output, and the number of the other outputs. */
  private def outputs(values: Vector[DynamicValue]): Vector[String] =
    val first = values.headOption.toVector.flatMap {
      case output: DynamicValue.Record => string(output, "text").filter(_.nonEmpty).toVector.flatMap(response) ++
          toolCalls(sequence(output, "tool_calls"))
      case other => DynamicValues.asString(other).toVector.flatMap(response)
    }
    val others = Option.when(values.size > 1)(s" \t (and ${values.size - 1} other completions)\n")
    first ++ others

  private def response(content: String): Vector[String] =
    Vector(label("Response:"), answer(content.strip()))

  private def error(payload: DynamicValue.Record): Vector[String] =
    string(payload, "error_code").toVector.map { code =>
      label(s"Error ($code): ${string(payload, "error_message").getOrElse("")}")
    }

  private def toolCalls(calls: Vector[DynamicValue]): Vector[String] =
    if calls.isEmpty then Vector.empty
    else label("Tool calls:") +: calls.map(call => answer(toolCall(call)))

  /** `<name>: <arguments>` for one tool call. As upstream, the call can have its name and its arguments under
    * `function`, and arguments that are a JSON text are decoded.
    */
  private def toolCall(call: DynamicValue): String =
    val record   = asRecord(call)
    val function = record.flatMap(field(_, "function")).flatMap(asRecord)
    val name     = function.flatMap(string(_, "name")).filter(_.nonEmpty)
      .orElse(record.flatMap(string(_, "name")))
      .getOrElse("<unknown>")
    val arguments = function.flatMap(field(_, "arguments"))
      .orElse(record.flatMap(found => field(found, "args").orElse(field(found, "arguments"))))
      .getOrElse(DynamicValue.Record.empty)
    s"$name: ${argumentsText(arguments)}"

  /** An object or a list as JSON, and another value as text. */
  private def argumentsText(arguments: DynamicValue): String =
    val decoded = DynamicValues.asString(arguments).flatMap(decodeJson).getOrElse(arguments)
    decoded match
      case _: DynamicValue.Record | _: DynamicValue.Sequence => encodeJson(decoded)
      case other                                             => DynamicValues.renderText(other)

  private def decodeJson(json: String): Option[DynamicValue] =
    jsonCodec.decode(json.getBytes(StandardCharsets.UTF_8)).toOption

  private def encodeJson(value: DynamicValue): String =
    new String(jsonCodec.encode(value), StandardCharsets.UTF_8)

  /** The role with its first character in upper case and the other characters in lower case (Python `capitalize`). */
  private def capitalized(role: String): String =
    role.take(1).toUpperCase(Locale.ROOT) + role.drop(1).toLowerCase(Locale.ROOT)

  /** The text of upstream `print(text)`. */
  private def line(text: String): String = s"$text\n"

  /** The text of upstream `print(_red(text))` and `print(_blue(text))`, with no colors. */
  private def label(text: String): String = s"$text\n\n"

  /** The text of upstream `print(_green(text))`, with no colors. */
  private def answer(text: String): String = s"${text.stripLeading()}\n\n"

  private def field(record: DynamicValue.Record, key: String): Option[DynamicValue] =
    DynamicValues.recordGet(record, key)

  private def string(record: DynamicValue.Record, key: String): Option[String] =
    field(record, key).flatMap(DynamicValues.asString)

  private def sequence(record: DynamicValue.Record, key: String): Vector[DynamicValue] =
    field(record, key) match
      case Some(values: DynamicValue.Sequence) => values.elements.toVector
      case _                                   => Vector.empty

  private def records(values: Vector[DynamicValue]): Vector[DynamicValue.Record] =
    values.flatMap(asRecord)

  private def asRecord(value: DynamicValue): Option[DynamicValue.Record] =
    value match
      case record: DynamicValue.Record => Some(record)
      case _                           => None

  private def text(value: String): DynamicValue =
    DynamicValue.Primitive(PrimitiveValue.String(value))
