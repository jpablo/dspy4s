package dspy4s.harness.inspect

import zio.blocks.schema.json.Json

/** The reply of a stream, put together from its chunks (docs/code-harness/OBSERVABILITY.md, 5.4): the reply in the form
  * that an OpenAI-compatible endpoint gives with no stream. The Reply view shows it next to the lines of the stream.
  *
  * The merge reads the JSON of the chunks and not the reading of the model (`Chat.read`), so it keeps each field that
  * the endpoint sent, also a field that the model does not read. A law checks that the two agree.
  *
  *   - A line counts only when it starts with `data:` and holds a JSON object. `data: [DONE]`, a comment, an event line
  *     and a text that is not JSON change nothing.
  *   - The top fields come from the first chunk that has them, `object` becomes `chat.completion`, and `usage` comes
  *     from the last chunk that has it.
  *   - The choices join by their `index`. The deltas of a choice become its `message`: the parts of `content`,
  *     `reasoning`, `reasoning_content` and `refusal` join, `role` comes from the first delta, and the tool calls join
  *     by their `index`. `finish_reason` comes from the last chunk that has it, and the items of each array of
  *     `logprobs` join.
  *   - A tool call takes its `id`, `type` and `function.name` from its first part, and the parts of
  *     `function.arguments` join.
  *   - Each other field of a choice or of a delta comes from the last chunk that has it.
  */
object StreamJson:

  /** The keys of a delta whose text parts join. */
  private val Joined = Set("content", "reasoning", "reasoning_content", "refusal")

  /** The reply that the chunks of the `data:` lines of `lines` give, or none when no line has a chunk. */
  def merge(lines: Vector[String]): Option[Json] =
    val all = chunks(lines)
    Option.when(all.nonEmpty)(reply(all))

  /** The JSON objects of the `data:` lines of `lines`, in their order. `[DONE]` is not JSON, so its line gives none. */
  def chunks(lines: Vector[String]): Vector[Json.Object] =
    lines.flatMap { line =>
      Option.when(line.startsWith("data:"))(line.drop(5).trim).flatMap(data => Json.parse(data).toOption)
        .collect { case o: Json.Object => o }
    }

  private def reply(all: Vector[Json.Object]): Json =
    Json.Object(keys(all).flatMap {
      case "object"  => Some("object" -> Json.String("chat.completion"))
      case "choices" => Some("choices" -> Json.Array(choices(all.flatMap(objects(_, "choices")))*))
      case "usage"   => last(all, "usage").map("usage" -> _)
      case key       => first(all, key).map(key -> _)
    }*)

  /** The choices of the parts `parts`, joined by their `index`. */
  private def choices(parts: Vector[Json.Object]): Vector[Json] =
    byIndex(parts).map { group =>
      Json.Object(keys(group).flatMap {
        case "delta"    => Some("message" -> message(group.flatMap(objectAt(_, "delta"))))
        case "index"    => first(group, "index").map("index" -> _)
        case "logprobs" => logprobs(group).map("logprobs" -> _)
        case key        => last(group, key).map(key -> _)
      }*)
    }

  /** The message of the deltas `deltas` of one choice. */
  private def message(deltas: Vector[Json.Object]): Json =
    Json.Object(keys(deltas).flatMap {
      case key if Joined(key) => Some(key -> joined(deltas, key))
      case "role"             => first(deltas, "role").map("role" -> _)
      case "tool_calls"       => Some("tool_calls" -> Json.Array(toolCalls(deltas.flatMap(objects(_, "tool_calls")))*))
      case key                => last(deltas, key).map(key -> _)
    }*)

  /** The tool calls of the parts `parts`, joined by their `index`. A call of the reply has no `index`. */
  private def toolCalls(parts: Vector[Json.Object]): Vector[Json] =
    byIndex(parts).map { group =>
      Json.Object(keys(group).flatMap {
        case "index"    => None
        case "function" =>
          val functions = group.flatMap(objectAt(_, "function"))
          Some("function" -> Json.Object(keys(functions).flatMap {
            case "arguments" => Some("arguments" -> joined(functions, "arguments"))
            case key         => first(functions, key).map(key -> _)
          }*))
        case key => first(group, key).map(key -> _)
      }*)
    }

  /** The `logprobs` of the parts of one choice: for each key, the items of its arrays in their order. None when no part
    * has an object of `logprobs`.
    */
  private def logprobs(group: Vector[Json.Object]): Option[Json] =
    val parts = group.flatMap(objectAt(_, "logprobs"))
    Option.when(parts.nonEmpty)(Json.Object(keys(parts).map(key =>
      key -> Json.Array(parts.flatMap(arrayAt(_, key))*)
    )*))

  /** The parts of `parts` in groups by the text of their `index`, in the order of the first part of each group. */
  private def byIndex(parts: Vector[Json.Object]): Vector[Vector[Json.Object]] =
    val index = (part: Json.Object) => valueOf(part, "index").fold("")(_.print)
    parts.map(index).distinct.map(i => parts.filter(index(_) == i))

  /** The text parts of `key` in `parts`, joined, or null when no part has a text there. */
  private def joined(parts: Vector[Json.Object], key: String): Json =
    val texts = parts.flatMap(valueOf(_, key)).collect { case s: Json.String => s.value }
    if texts.isEmpty then Json.Null else Json.String(texts.mkString)

  /** The keys of `parts` in the order that they first come. */
  private def keys(parts: Vector[Json.Object]): Vector[String] = parts.flatMap(_.value.toVector.map(_._1)).distinct

  private def first(parts: Vector[Json.Object], key: String): Option[Json] =
    parts.iterator.flatMap(valueOf(_, key)).find(present)

  private def last(parts: Vector[Json.Object], key: String): Option[Json] =
    parts.reverseIterator.flatMap(valueOf(_, key)).find(present)

  private def present(value: Json): Boolean =
    value match
      case _: Json.Null.type => false
      case _                 => true

  private def valueOf(part: Json.Object, key: String): Option[Json] = part.value.collectFirst { case (`key`, v) => v }

  private def objectAt(part: Json.Object, key: String): Option[Json.Object] =
    valueOf(part, key).collect { case o: Json.Object => o }

  private def arrayAt(part: Json.Object, key: String): Vector[Json] =
    valueOf(part, key).collect { case a: Json.Array => a.value.toVector }.getOrElse(Vector.empty)

  private def objects(part: Json.Object, key: String): Vector[Json.Object] =
    arrayAt(part, key).collect { case o: Json.Object => o }
