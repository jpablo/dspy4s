package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.adapters.contracts.ToolChoice
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.lm.contracts.LmOutput

import scala.util.matching.Regex

/** Chat-style adapter that frames each layout field with `[[ ## field_name ## ]]` markers and terminates output with
  * `[[ ## completed ## ]]`. Mirrors Python DSPy's `ChatAdapter`.
  *
  * The marker framing is required for:
  *   - multi-line field values to round-trip cleanly,
  *   - unambiguous parsing when a field value contains a colon-prefixed line,
  *   - reliable streaming detection of field boundaries.
  */
final case class ChatAdapter(
    name: String = "chat",
    /** Enable provider-native function-calling. When on AND the signature declares a `tool_calls` output field AND tool
      * specs are supplied AND the resolved LM advertises `supportsFunctionCalling`, the `tools` are injected into the
      * request option bag and the tool-calls field is filled from the provider's structured `tool_calls` instead of
      * being requested as text. Off by default (mirrors dspy's `use_native_function_calling`).
      */
    useNativeFunctionCalling: Boolean = false,
    /** When native function-calling is active, request provider-side parallel tool-call generation. `None` leaves the
      * knob unset (provider default).
      */
    parallelToolCalls: Option[Boolean] = None,
    /** When native function-calling is active, set the provider `tool_choice` ([[ToolChoice.Auto]] /
      * [[ToolChoice.Required]] / [[ToolChoice.Off]], or [[ToolChoice.Function]] to force a specific tool). `None`
      * leaves it unset (provider default).
      */
    toolChoice: Option[ToolChoice] = None
) extends Adapter:

  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    ChatAdapterPrompt.format(invocation, useNativeFunctionCalling, parallelToolCalls, toolChoice)

  override def streamingState(layout: SignatureLayout): Option[AdapterStreamingState] = Some(
    new ChatStreamingState(layout.outputFields)
  )

  override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
    ChatAdapterParser.parse(name, layout, output)

object ChatAdapter:
  /** Pattern matching `[[ ## field_name ## ]]`. Capture group 1 is the field name. The pattern is intentionally
    * anchored to start-of-string by callers (`findPrefixMatchOf`), so leading whitespace is the caller's responsibility
    * to strip.
    */
  val MarkerPattern: Regex = """\[\[ ## (\w+) ## \]\]""".r

  /** Reserved field name that closes the structured output. */
  val CompletedFieldName: String = "completed"
  val CompletedMarker: String = s"[[ ## $CompletedFieldName ## ]]"

  /** Canonical type name to surface in the system prompt's field description block. Maps dspy4s's internal
    * `TypeRef.repr` to the names users will recognise (and that match Python DSPy).
    */
  def displayTypeName(t: TypeRef): String = t.pythonTypeName.getOrElse(t.repr)

  /** Hint phrasing for the final-user-message reminder ("Respond with `[[ ## answer ## ]]` (must be …)"). Returns
    * `None` for strings (no hint needed) and a "(must be formatted as a valid …)" string otherwise.
    */
  def reminderHint(t: TypeRef): Option[String] =
    t match
      case TypeRef.string =>
        None
      case TypeRef.list =>
        Some("must be a valid JSON array")
      case _ =>
        Some(s"must be formatted as a valid ${displayTypeName(t)}")

  /** Hint phrasing for the structure-example `# note: ...` comments. More specific than the reminder hint: enumerates
    * booleans and gives a brief "single X value" form for scalars.
    */
  def structureHint(t: TypeRef): Option[String] =
    t match
      case TypeRef.string =>
        None
      case TypeRef.int =>
        Some("must be a single int value")
      case TypeRef.double =>
        Some("must be a single float value")
      case TypeRef.bool =>
        Some("must be true or false")
      case TypeRef.json =>
        Some("must be a valid JSON object")
      case TypeRef.list =>
        Some("must be a valid JSON array")
      case other =>
        Some(s"must be a valid ${displayTypeName(other)}")
