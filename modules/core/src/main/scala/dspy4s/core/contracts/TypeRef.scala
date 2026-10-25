package dspy4s.core.contracts

import scala.util.matching.Regex

/** Wire-format type tag for a field, surfaced to the LM via adapter prompts (e.g. `"answer: bool"`).
  *
  * This is the *adapter / prompt* type, not the Scala type: `Shape[A]` carries the static Scala-side encoding. A Scala
  * enum, for instance, has Scala type `Sentiment` but [[TypeRef.string]] at the wire level (the LM sees a flat string
  * like `"joy"`).
  *
  * The twelve well-known refs of the companion object cover the common cases. Each other token passes through as an
  * opaque token, and an adapter that does not know the token renders it as a free-form string.
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
      case "tools"   => Some("list[Tool]")
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
    * `tool_calls` instead of asking the model to emit it as text. See PORT_GAPS G-7b. A typed field that is a sequence
    * of [[ToolCall]] values, for example a `Vector[ToolCall]`, gets this type from the marker of `ToolCall` (see
    * [[TypeMarker]]).
    */
  val toolCalls: TypeRef = TypeRef("tool_calls")

  /** The type of the input field that holds the conversation history (the analogue of Python dspy's `History` input
    * annotation). An adapter replays the messages of that field as earlier turns and does not show the field as a
    * value. See [[History]].
    */
  val history: TypeRef = TypeRef("history")

  /** The type of the input field that holds the tools of a call (the analogue of Python dspy's `list[dspy.Tool]` input
    * annotation; experimental, for `ReActV2`). The value is a list with the text of each tool. When native function
    * calling is active for the call, the adapter does not show the field: the request has the tools as provider tools
    * (upstream `Adapter._call_preprocess` deletes the field). Else the adapter shows the value as a JSON list.
    */
  val tools: TypeRef = TypeRef("tools")

  /** The type of a `Noul` decision output: a Boolean decision with a probability (experimental, upstream
    * `dspy.experimental.Noul`). See [[dspy4s.core.decisions]].
    */
  val noul: TypeRef = TypeRef("noul")

  /** The type of a `Score` decision output: a continuous score over ordered levels. [[FieldSpec.enumValues]] holds the
    * levels, in increasing order (experimental, upstream `dspy.experimental.Score`).
    */
  val score: TypeRef = TypeRef("score")

  /** The type of a `Choice` decision output: one option of a closed set. [[FieldSpec.enumValues]] holds the option
    * labels, in the order of declaration (experimental, upstream `dspy.experimental.Choice`).
    */
  val choice: TypeRef = TypeRef("choice")

  /** Parse a string DSL type token (e.g. the `"bool"` in `"comment -> toxic: bool"`) into the matching well-known
    * [[TypeRef]]. Accepts a handful of synonyms (`"str"`, `"integer"`, `"float"`, `"number"`, `"dict"`, `"map"`) and is
    * case-insensitive. A `dict[...]` token is [[TypeRef.json]], as `dict` is. Unknown tokens become opaque
    * `TypeRef(other)`. An empty / missing token defaults to [[TypeRef.string]] (DSPy convention: fields without a type
    * annotation are strings).
    */
  def fromToken(token: String): TypeRef =
    token.trim.toLowerCase match
      case "" | "str" | "string"         => string
      case "int" | "integer"             => int
      case "float" | "double" | "number" => double
      case "bool" | "boolean"            => bool
      case "json" | "dict" | "map"       => json
      case "tool_calls" | "toolcalls"    => toolCalls
      case DictToken()                   => json
      case other                         => TypeRef(other)

  /** A `dict` token with type arguments, for example `dict[str, int]` (after the token becomes lower case). */
  private val DictToken: Regex = """dict\[.*\]""".r
