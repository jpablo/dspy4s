package dspy4s.core.contracts

import scala.util.matching.Regex

/** Wire-format type tag for a field, surfaced to the LM via adapter prompts (e.g. `"answer: bool"`).
  *
  * This is the *adapter / prompt* type, not the Scala type -- `Shape[A]` carries the static Scala-side encoding. A
  * Scala enum, for instance, has Scala type `Sentiment` but [[TypeRef.string]] at the wire level (the LM sees a flat
  * string like `"joy"`).
  *
  * Six well-known refs cover the common cases. Anything outside that set passes through as an opaque token -- adapters
  * that don't recognize it fall back to rendering it as a free-form string.
  */
final case class TypeRef(repr: String) derives CanEqual:

  /** Python/DSPy-facing name for well-known wire types. `None` means there is no safe direct Python equivalent. */
  def pythonTypeName: Option[String] =
    repr match
      case "string"  => Some("str")
      case "int"     => Some("int")
      case "double"  => Some("float")
      case "bool"    => Some("bool")
      case "list"    => Some("list")
      case "json"    => Some("dict")
      case "history" => Some("History")
      case _         => None

object TypeRef:
  val string: TypeRef = TypeRef("string")
  val int: TypeRef    = TypeRef("int")
  val double: TypeRef = TypeRef("double")
  val bool: TypeRef   = TypeRef("bool")
  val json: TypeRef   = TypeRef("json")
  val list: TypeRef   = TypeRef("list")

  /** Sentinel for the output field that receives native provider tool calls (the analogue of Python dspy's `ToolCalls`
    * output annotation). An adapter with native function-calling enabled fills this field from the provider's
    * `tool_calls` instead of asking the model to emit it as text. See PORT_GAPS G-7b.
    */
  val toolCalls: TypeRef = TypeRef("tool_calls")

  /** The type of the input field that holds the conversation history (the analogue of Python dspy's `History` input
    * annotation). An adapter replays the messages of that field as earlier turns and does not show the field as a
    * value. See [[History]].
    */
  val history: TypeRef = TypeRef("history")

  /** Parse a string DSL type token (e.g. the `"bool"` in `"comment -> toxic: bool"`) into the matching well-known
    * [[TypeRef]]. Accepts a handful of synonyms (`"str"`, `"integer"`, `"float"`, `"number"`, `"dict"`, `"map"`) and is
    * case-insensitive. A `dict[...]` token is [[TypeRef.json]], as `dict` is. Unknown tokens become opaque
    * `TypeRef(other)`. An empty / missing token defaults to [[TypeRef.string]] (DSPy convention -- fields without a
    * type annotation are strings).
    */
  def fromToken(token: String): TypeRef =
    token.trim.toLowerCase match
      case "" | "str" | "string"         => string
      case "int" | "integer"             => int
      case "float" | "double" | "number" => double
      case "bool" | "boolean"            => bool
      case "json" | "dict" | "map"       => json
      case "tool_calls" | "toolcalls"    => toolCalls
      case "history"                     => history
      case DictToken()                   => json
      case other                         => TypeRef(other)

  /** A `dict` token with type arguments, for example `dict[str, int]` (after the token becomes lower case). */
  private val DictToken: Regex = """dict\[.*\]""".r
