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
import dspy4s.lm.contracts.LmOutput

final case class JSONAdapter(
    name                            : String  = "json",
    allowTextFallbackForSingleOutput: Boolean = true,
    /** See [[ChatAdapter.useNativeFunctionCalling]] — same adapter-level native function-calling gate, shared via
      * [[NativeFunctionCalling]]. Off by default.
      */
    useNativeFunctionCalling: Boolean         = false,
    parallelToolCalls       : Option[Boolean] = None,
    /** See [[ChatAdapter.toolChoice]]. */
    toolChoice: Option[ToolChoice] = None
) extends Adapter:
  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    JSONAdapterPrompt.format(invocation, useNativeFunctionCalling, parallelToolCalls, toolChoice)

  override def streamingState(layout: SignatureLayout): Option[AdapterStreamingState] = Some(
    new JsonStreamingState(layout.outputFields)
  )

  override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
    JSONAdapterParser.parse(name, allowTextFallbackForSingleOutput, layout, output)
