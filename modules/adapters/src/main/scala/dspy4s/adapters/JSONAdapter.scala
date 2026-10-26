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

final case class JSONAdapter(
    allowTextFallbackForSingleOutput: Boolean = true,
    /** The native function calling options ([[NativeTools]]), with the same rules as for [[ChatAdapter.native]]. Off by
      * default.
      */
    native: NativeTools = NativeTools.Off
) extends Adapter:
  override def name: String = "json"

  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    JSONAdapterPrompt.format(invocation, native)

  /** The stream state of the text fields of the parser, as for [[ChatAdapter.streamingState]]: with native function
    * calling active, the provider fills the tool calls field, so the stream does not track it
    * ([[AdapterTextSupport.textFields]]).
    */
  override def streamingState(layout: SignatureLayout)(using RuntimeContext): Option[AdapterStreamingState] =
    Some(new JsonStreamingState(AdapterTextSupport.textFields(layout, native.isActive)))

  override def parse(invocation: AdapterInvocation, output: LmOutput)(using
      RuntimeContext
  ): Either[DspyError, ParsedOutput] =
    JSONAdapterParser.parse(allowTextFallbackForSingleOutput, invocation.layout, output, native.isActive)
