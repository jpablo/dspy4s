package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.NativeFunctionCalling
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.adapters.contracts.ToolChoice
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
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
      *
      * When the flag is off, or the LM does not support function calling, the tool-calls field is a text output field.
      * See [[NativeFunctionCalling.isToolCallsField]].
      *
      * The flag also sets how the adapter replays a turn of a conversation history that has tool calls. When on AND the
      * resolved LM advertises `supportsFunctionCalling`, the turn is an assistant message with tool calls and one tool
      * message for each result. In each other case the turn stays text.
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
    toolChoice: Option[ToolChoice] = None,
    /** Try a [[JSONAdapter]] after a parse error (dspy's `use_json_adapter_fallback`). On by default, as upstream. The
      * fallback adapter has the native function-calling options of this adapter. See [[Adapter.fallback]].
      */
    useJsonAdapterFallback: Boolean = true
) extends Adapter:

  /** A [[JSONAdapter]] with the native function-calling options of this adapter, when [[useJsonAdapterFallback]] is on.
    */
  override def fallback: Option[Adapter] =
    Option.when(useJsonAdapterFallback)(
      JSONAdapter(
        useNativeFunctionCalling = useNativeFunctionCalling,
        parallelToolCalls = parallelToolCalls,
        toolChoice = toolChoice
      )
    )

  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    ChatAdapterPrompt.format(invocation, useNativeFunctionCalling, parallelToolCalls, toolChoice)

  /** The stream state of the text fields of the parser: with native function calling active, the tool calls field is
    * not a text field ([[ChatAdapterParser.textFields]]). When native function calling is not active, the parser also
    * removes that field for a reply that has provider tool calls. The stream state cannot know these calls before the
    * end of the stream, so it then gets each output field.
    */
  override def streamingState(layout: SignatureLayout)(using RuntimeContext): Option[AdapterStreamingState] =
    Some(
      new ChatStreamingState(
        ChatAdapterParser.textFields(layout, NativeFunctionCalling.isActive(useNativeFunctionCalling))
      )
    )

  override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
    ChatAdapterParser.parse(name, layout, output, NativeFunctionCalling.isActive(useNativeFunctionCalling))

object ChatAdapter:
  /** Pattern matching `[[ ## field_name ## ]]`. Capture group 1 is the field name. The pattern is intentionally
    * anchored to start-of-string by callers (`findPrefixMatchOf`), so leading whitespace is the caller's responsibility
    * to strip.
    */
  val MarkerPattern: Regex = """\[\[ ## (\w+) ## \]\]""".r

  /** Reserved field name that closes the structured output. */
  val CompletedFieldName: String = "completed"
  val CompletedMarker: String    = s"[[ ## $CompletedFieldName ## ]]"
