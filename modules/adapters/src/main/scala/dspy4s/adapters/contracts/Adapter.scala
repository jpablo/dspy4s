package dspy4s.adapters.contracts

import dspy4s.adapters.ChatAdapterPrompt
import dspy4s.core.contracts.{AdapterRef, DspyError, RuntimeContext, SignatureLayout}
import dspy4s.core.data.Example
import dspy4s.lm.contracts.{LmOutput, LmRequest}
import zio.blocks.schema.DynamicValue

trait Adapter extends AdapterRef:
  def name: String

  /** The adapter that a predictor tries after a parse error of this adapter (dspy's `use_json_adapter_fallback`). The
    * predictor then formats, calls the model, and parses one more time with that adapter.
    *
    * Only a [[dspy4s.core.contracts.ParseError]] of the parse step gives the second call. An error of the model, of the
    * configuration, or of the format step does not. A call that already streamed visible output to a listener gets no
    * second call. `None` (the default) gives no second call.
    */
  def fallback: Option[Adapter] = None

  def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt]

  /** The parse of `output`, the reply to the request that [[format]] made from `invocation`. The predictor calls this
    * method. An adapter that needs more of the invocation than the layout uses it: [[dspy4s.adapters.TwoStepAdapter]]
    * gives the output JSON schema of the invocation to its extraction prompt. An adapter that wraps another adapter
    * gives the invocation to the parse of that adapter.
    */
  def parse(invocation: AdapterInvocation, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput]

  /** The parse of `output` for an invocation of `layout` with no demos, no inputs, no output JSON schema, and no tools.
    */
  final def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
    parse(
      AdapterInvocation(layout = layout, demos = Vector.empty, inputs = Example.empty, request = LmRequest(model = "")),
      output
    )

  /** The text of a user turn that shows the values of the input fields of `layout` that `values` has, with no output
    * reminder (upstream `Adapter.format_user_message_content` with `main_request=False`). `ReAct` writes its trajectory
    * with this text (upstream `ReAct._format_trajectory`). The default is the marker form of the chat adapter.
    */
  def formatUserMessageContent(layout: SignatureLayout, values: DynamicValue.Record): String =
    ChatAdapterPrompt.userText(layout, values, reminder = None)

  /** Streaming-aware adapters override this to return a per-call state machine. The default returns [[None]] and the
    * streaming pipeline falls back to emitting raw tokens with an empty field name. The context is the context of the
    * call, as for [[parse]], so the state can follow the same rules as the parse of the call, for example native
    * function calling.
    */
  def streamingState(layout: SignatureLayout)(using RuntimeContext): Option[AdapterStreamingState] = None
