package dspy4s.adapters.contracts

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.lm.contracts.LanguageModel
import dspy4s.core.contracts.ToolCall
import zio.blocks.schema.DynamicValue

/** Provider `tool_choice` setting for native function-calling, mirroring OpenAI's string-or-object form: `"auto"` /
  * `"required"` / `"none"`, or force a specific tool via `{type:"function", function:{name}}`.
  */
enum ToolChoice derives CanEqual:
  case Auto
  case Required
  case Off
  case Function(name: String)

/** The native function calling options of an adapter (upstream `use_native_function_calling` in
  * `dspy/adapters/base.py`). [[dspy4s.adapters.ChatAdapter]] and [[dspy4s.adapters.JSONAdapter]] have these options,
  * and the functions of this value give the rules of a call, so that the two adapters have the same rules. ReAct keeps
  * the text protocol, as upstream. See PORT_GAPS G-7b.
  *
  * @param enabled
  *   use the function calling of the provider. Off by default, as upstream. The request gets the tools when four
  *   conditions are true: this flag is on, the call has tool specs, the layout has a tool calls field, and the LM of
  *   the context supports function calling ([[toolOptions]]). The provider tool calls then fill the tool calls field,
  *   and the prompt does not ask for it. Else the tool calls field is a text output field
  *   ([[NativeFunctionCalling.isToolCallsField]]). The flag also sets how the adapter replays a turn of a conversation
  *   history that has tool calls ([[isActive]]).
  * @param parallelToolCalls
  *   the provider `parallel_tool_calls` option when the request has the tools. `None` sends no option (the provider
  *   default).
  * @param toolChoice
  *   the provider `tool_choice` option when the request has the tools: [[ToolChoice.Auto]], [[ToolChoice.Required]],
  *   [[ToolChoice.Off]], or [[ToolChoice.Function]] to force one tool. `None` sends no option (the provider default).
  */
final case class NativeTools(
    enabled          : Boolean            = false,
    parallelToolCalls: Option[Boolean]    = None,
    toolChoice       : Option[ToolChoice] = None
):

  /** `true` when native function calling is active for a call: [[enabled]] is on, and the LM of the context supports
    * function calling (upstream `_call_preprocess`). The parsers then fill the tool calls field from the provider tool
    * calls. A replayed turn of a history with tool calls is then an assistant message with the tool calls and one tool
    * message for each result. Upstream checks the flag only. dspy4s also checks the model, because a model without
    * function calling rejects these messages.
    */
  def isActive(using RuntimeContext): Boolean =
    enabled && NativeTools.lmSupportsFunctionCalling

  /** The fields of `layout` that the prompt shows. When native function calling is active ([[isActive]]) and `layout`
    * has a tool calls field, the prompt does not show that field or the tools input field: the request has the tools as
    * provider tools (upstream `_call_preprocess` deletes the two fields). Else the prompt shows each field.
    */
  def promptLayout(layout: SignatureLayout)(using RuntimeContext): SignatureLayout =
    if isActive && layout.outputFields.exists(NativeFunctionCalling.isToolCallsField) then
      layout
        .withInputFields(layout.inputFields.filterNot(NativeTools.isToolsField))
        .withOutputFields(layout.outputFields.filterNot(NativeFunctionCalling.isToolCallsField))
    else layout

  /** The tool options of the request for `invocation` (upstream `_call_preprocess`):
    *   - The request gets `tools`, and `parallel_tool_calls` and `tool_choice` when they are set, when [[enabled]] is
    *     on, `invocation` has tools, its layout has a tool calls field, and the LM of the context supports function
    *     calling.
    *   - The request must not have these three keys, also when the call config has them, when [[enabled]] is off, and
    *     when the layout has a tool calls field but the LM does not support function calling. Upstream pops them from
    *     the options of the call.
    */
  def toolOptions(invocation: AdapterInvocation)(using RuntimeContext): NativeTools.ToolOptions =
    val supported = NativeTools.lmSupportsFunctionCalling
    val hasField  = invocation.layout.outputFields.exists(NativeFunctionCalling.isToolCallsField)
    val request   =
      if enabled && supported && hasField && invocation.tools.nonEmpty then
        DynamicValues.recordFromEntries(
          Vector("tools" -> ToolSchemaBridge.toOpenAiToolsDynamic(invocation.tools)) ++
            parallelToolCalls.map("parallel_tool_calls" := _) ++
            toolChoice.map(choice => "tool_choice" -> NativeTools.encodeToolChoice(choice))
        )
      else DynamicValue.Record.empty
    val removed = if !enabled || (hasField && !supported) then NativeTools.OptionKeys else Set.empty[String]
    NativeTools.ToolOptions(request, removed)

object NativeTools:

  /** No native function calling: the default of the adapters. */
  val Off: NativeTools = NativeTools()

  /** The tool options of one request ([[NativeTools.toolOptions]]).
    *
    * @param request
    *   the options that the request gets ([[FormattedPrompt.requestOptions]])
    * @param removed
    *   the keys that the request must not have ([[FormattedPrompt.removedOptions]])
    */
  final case class ToolOptions(request: DynamicValue.Record, removed: Set[String])

  /** The request options of native function calling (upstream `_call_preprocess`). An adapter with no native function
    * calling removes them from each request ([[FormattedPrompt.removedOptions]]).
    */
  val OptionKeys: Set[String] = Set("tools", "tool_choice", "parallel_tool_calls")

  /** Whether the ambient LM advertises function-calling support (narrows the opaque `RuntimeContext.lm` ref). */
  private def lmSupportsFunctionCalling(using ctx: RuntimeContext): Boolean =
    ctx.lm match
      case Some(lm: LanguageModel) => lm.supportsFunctionCalling
      case _                       => false

  /** True for the input field that holds the texts of the tools (`typeRef == TypeRef.tools`). */
  private def isToolsField(field: FieldSpec): Boolean =
    field.typeRef == TypeRef.tools

  /** Encode a [[ToolChoice]] as the provider `tool_choice` value: a string for the modes, an object for a forced
    * function.
    */
  private def encodeToolChoice(choice: ToolChoice): DynamicValue =
    choice match
      case ToolChoice.Auto        => DynamicValues.string("auto")
      case ToolChoice.Required    => DynamicValues.string("required")
      case ToolChoice.Off         => DynamicValues.string("none")
      case ToolChoice.Function(n) =>
        DynamicValues.record("type" := "function", "function" -> DynamicValues.record("name" := n))

/** The tool calls field helpers that the adapters, the parsers, and the programs share. */
object NativeFunctionCalling:

  /** True for the output field that receives the tool calls (`typeRef == TypeRef.toolCalls`). When native function
    * calling is active ([[NativeTools.isActive]]), the structured response fills such a field, and the prompt does not
    * ask for it. Else the field is a text output field: the prompt asks for a JSON object like
    * `{"tool_calls": [{"name", "args"}]}` (upstream `ToolCalls`), and the parser reads that object, or a JSON list of
    * the calls, from the text.
    */
  def isToolCallsField(field: FieldSpec): Boolean =
    field.typeRef == TypeRef.toolCalls

  /** Encode native tool calls as the value of a `tool_calls` output field: a sequence of `{name, args}` records,
    * matching the shape `PredictEngine` attaches to predictions. A record also has the `id` of the provider when the
    * call has one, so the typed output and the raw prediction keep that id.
    */
  def encodeToolCalls(calls: Vector[ToolCall]): DynamicValue =
    DynamicValues.sequence(calls.map(_.toRecord))
