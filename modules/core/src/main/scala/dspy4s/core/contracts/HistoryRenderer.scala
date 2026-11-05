package dspy4s.core.contracts

import zio.blocks.schema.DynamicValue

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
  import DynamicValues.{asRecord, recordGet, sequenceAt}

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
      outputs(sequenceAt(payload, "outputs")) ++
      error(payload)

  /** The objects of `messages`. When there is none, one user message with the `prompt` of the entry. */
  private def messages(payload: DynamicValue.Record): Vector[DynamicValue.Record] =
    val listed = records(sequenceAt(payload, "messages"))
    if listed.nonEmpty then listed
    else
      Vector(DynamicValues.recordFromEntries(
        Vector[(String, DynamicValue)]("role" -> DynamicValues.string("user")) ++
          recordGet(payload, "prompt").map("content" -> _)
      ))

  private def message(value: DynamicValue.Record): Vector[String] =
    val role = stringAt(value, "role").getOrElse("unknown")
    Vector(label(s"${capitalized(role)} message:")) ++
      stringAt(value, "content").map(content => line(content.strip())).toVector ++
      records(sequenceAt(value, "parts")).map(part) ++
      toolCalls(sequenceAt(value, "tool_calls")) :+
      line("\n")

  /** The line of one content part: its kind and the length of its payload. */
  private def part(value: DynamicValue.Record): String =
    val kind   = stringAt(value, "kind").getOrElse("part")
    val length = stringAt(value, "payload").fold(0)(payload => payload.codePointCount(0, payload.length))
    label(s"<$kind: $length characters>")

  /** The response and the tool calls of the first output, and the number of the other outputs. */
  private def outputs(values: Vector[DynamicValue]): Vector[String] =
    val first = values.headOption.toVector.flatMap {
      case output: DynamicValue.Record => stringAt(output, "text").filter(_.nonEmpty).toVector.flatMap(response) ++
          toolCalls(sequenceAt(output, "tool_calls"))
      case other => DynamicValues.asString(other).toVector.flatMap(response)
    }
    val others = Option.when(values.size > 1)(s" \t (and ${values.size - 1} other completions)\n")
    first ++ others

  private def response(content: String): Vector[String] =
    Vector(label("Response:"), answer(content.strip()))

  private def error(payload: DynamicValue.Record): Vector[String] =
    stringAt(payload, "error_code").toVector.map { code =>
      label(s"Error ($code): ${stringAt(payload, "error_message").getOrElse("")}")
    }

  private def toolCalls(calls: Vector[DynamicValue]): Vector[String] =
    if calls.isEmpty then Vector.empty
    else label("Tool calls:") +: calls.map(call => answer(toolCall(call)))

  /** `<name>: <arguments>` for one tool call. As upstream, the call can have its name and its arguments under
    * `function`, and arguments that are a JSON text are decoded.
    */
  private def toolCall(call: DynamicValue): String =
    val record   = asRecord(call)
    val function = record.flatMap(recordGet(_, "function")).flatMap(asRecord)
    val name     = function.flatMap(stringAt(_, "name")).filter(_.nonEmpty)
      .orElse(record.flatMap(stringAt(_, "name")))
      .getOrElse("<unknown>")
    val arguments = function.flatMap(recordGet(_, "arguments"))
      .orElse(record.flatMap(found => recordGet(found, "args").orElse(recordGet(found, "arguments"))))
      .getOrElse(DynamicValue.Record.empty)
    s"$name: ${argumentsText(arguments)}"

  /** An object or a list as JSON, and another value as text. */
  private def argumentsText(arguments: DynamicValue): String =
    val decoded = DynamicValues.asString(arguments).flatMap(DynamicValues.decodeJson).getOrElse(arguments)
    decoded match
      case _: DynamicValue.Record | _: DynamicValue.Sequence => DynamicValues.encodeJson(decoded)
      case other                                             => DynamicValues.renderText(other)

  /** The role with its first character in upper case and the other characters in lower case (Python `capitalize`). */
  private def capitalized(role: String): String =
    role.take(1).toUpperCase(Locale.ROOT) + role.drop(1).toLowerCase(Locale.ROOT)

  /** The text of upstream `print(text)`. */
  private def line(text: String): String = s"$text\n"

  /** The text of upstream `print(_red(text))` and `print(_blue(text))`, with no colors. */
  private def label(text: String): String = s"$text\n\n"

  /** The text of upstream `print(_green(text))`, with no colors. */
  private def answer(text: String): String = s"${text.stripLeading()}\n\n"

  /** The string in the field `key` of `record`. */
  private def stringAt(record: DynamicValue.Record, key: String): Option[String] =
    recordGet(record, key).flatMap(DynamicValues.asString)

  private def records(values: Vector[DynamicValue]): Vector[DynamicValue.Record] =
    values.flatMap(asRecord)
