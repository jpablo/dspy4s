package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.NativeTools
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.adapters.internal.AdapterTextSupport
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
    /** The native function calling options ([[NativeTools]]). Off by default, as upstream. */
    native: NativeTools = NativeTools.Off,
    /** Try a [[JSONAdapter]] after a parse error (dspy's `use_json_adapter_fallback`). On by default, as upstream. The
      * fallback adapter has the native function-calling options of this adapter. See [[Adapter.fallback]].
      */
    useJsonAdapterFallback: Boolean = true
) extends Adapter:

  override def name: String = "chat"

  /** A [[JSONAdapter]] with the native function-calling options of this adapter, when [[useJsonAdapterFallback]] is on.
    */
  override def fallback: Option[Adapter] =
    Option.when(useJsonAdapterFallback)(JSONAdapter(native = native))

  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    ChatAdapterPrompt.format(invocation, native)

  /** The stream state of the text fields of the parser: with native function calling active, the tool calls field is
    * not a text field ([[AdapterTextSupport.textFields]]). When native function calling is not active, the parser also
    * removes that field for a reply that has provider tool calls. The stream state cannot know these calls before the
    * end of the stream, so it then gets each output field.
    */
  override def streamingState(layout: SignatureLayout)(using RuntimeContext): Option[AdapterStreamingState] =
    Some(new ChatStreamingState(AdapterTextSupport.textFields(layout, native.isActive)))

  override def parse(invocation: AdapterInvocation, output: LmOutput)(using
      RuntimeContext
  ): Either[DspyError, ParsedOutput] =
    ChatAdapterParser.parse(invocation.layout, output, native.isActive)

object ChatAdapter:

  /** The pattern of a marker `[[ ## field_name ## ]]` ([[marker]]). Capture group 1 is the field name. The parser
    * matches it at the start of a stripped line (`findPrefixMatchOf`). The stream state finds it in its buffer, and
    * then checks that only the indentation of its line comes before it.
    */
  private[adapters] val MarkerPattern: Regex = """\[\[ ## (\w+) ## \]\]""".r

  /** The marker of the field `name`: `[[ ## name ## ]]`. */
  private[adapters] def marker(name: String): String = s"[[ ## $name ## ]]"

  /** Reserved field name that closes the structured output. */
  private[adapters] val CompletedFieldName: String = "completed"
  private[adapters] val CompletedMarker: String    = marker(CompletedFieldName)
