package dspy4s.adapters.contracts

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.ConfigurationError
import dspy4s.core.contracts.DspyError
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
  *   use the function calling of the provider. As upstream, [[dspy4s.adapters.ChatAdapter]] has this flag off by
  *   default, and [[dspy4s.adapters.JSONAdapter]] has it on by default. The request gets the tools when four conditions
  *   are true: this flag is on, the call has tool specs, the layout has a tool calls field, and the LM of the context
  *   supports function calling ([[toolOptions]]). The provider tool calls then fill the tool calls field, and the
  *   prompt does not ask for it. Else the tool calls field is a text output field
  *   ([[NativeFunctionCalling.isToolCallsField]]), and the prompt shows the tool specs as text ([[promptInvocation]]).
  *   With this flag on, a tool calls field with no tools is an error ([[checkTools]]). The flag also sets how the
  *   adapter replays a turn of a conversation history that has tool calls ([[isActive]]).
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

  /** The error for a tool calls field with no tool specs in `invocation`, when [[enabled]] is on (upstream
    * `_call_preprocess`):
    *   - With no input field of the type [[TypeRef.tools]], it is an error on each model. Upstream does this check
    *     before it reads the support of the model.
    *   - With such an input, it is an error when native function calling is active ([[isActive]]). A tools input of the
    *     port holds only text, so it cannot become provider tools, and the prompt hides it ([[promptLayout]]). The
    *     model would see no tools. Upstream makes the provider tools from the `Tool` values of its input.
    *
    * With such an input and native function calling not active, the prompt shows the input, so there is no error. With
    * [[enabled]] off, the model can write tool calls with no tools, as upstream.
    */
  def checkTools(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, Unit] =
    val layout   = invocation.layout
    val hasInput = layout.inputFields.exists(NativeTools.isToolsField)
    layout.outputFields.find(NativeFunctionCalling.isToolCallsField)
      .filter(_ => enabled && invocation.tools.isEmpty && (!hasInput || isActive))
      .map(field => NativeTools.noToolsError(field.name, hasInput, isActive))
      .toLeft(())

  /** The invocation that the prompt shows. Upstream has the tools in an input field of the type `list[dspy.Tool]`, so
    * the prompt shows them when the request has no provider tools. In the port, a call can have the tool specs apart
    * from the layout. Thus the tool specs of `invocation` become one more input field at the end of the inputs, of the
    * type [[TypeRef.tools]], with the text of each tool ([[ToolSpec.text]]), when all these conditions are true:
    *   - `invocation` has tool specs, and its layout has a tool calls field. With no such field, the model has no field
    *     for a call, so the tools do not reach it.
    *   - Native function calling is not active ([[isActive]]). When it is active, the request has the provider tools
    *     ([[toolOptions]]).
    *   - The layout has no input field of the type [[TypeRef.tools]]. Such an input already shows the tools.
    *   - The layout has no field with the name `tools`. Such a field stays as it is, and the tool specs do not show,
    *     because one layout cannot have two fields with one name.
    *
    * Else the result is `invocation`. The result is for the prompt only: the parse reads the output fields, and they do
    * not change.
    */
  def promptInvocation(invocation: AdapterInvocation)(using RuntimeContext): AdapterInvocation =
    val layout = invocation.layout
    val field  = NativeTools.ToolSpecsField
    val shown  = invocation.tools.nonEmpty && !isActive &&
      layout.outputFields.exists(NativeFunctionCalling.isToolCallsField) &&
      !layout.inputFields.exists(NativeTools.isToolsField) && !layout.fields.exists(_.name == field.name)
    if !shown then invocation
    else
      val texts = DynamicValues.sequence(invocation.tools.map(tool => DynamicValues.string(tool.text)))
      invocation.copy(
        layout = layout.withInputFields(layout.inputFields :+ field),
        inputs = invocation.inputs.withValue(field.name, texts)
          .copy(inputKeys = invocation.inputs.inputKeys + field.name)
      )

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

  /** No native function calling: the default of [[dspy4s.adapters.ChatAdapter]]. */
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

  /** The input field that shows the tool specs of a call as text ([[NativeTools.promptInvocation]]). */
  private val ToolSpecsField: FieldSpec = FieldSpec("tools", typeRef = TypeRef.tools)

  /** The error of [[NativeTools.checkTools]] for the tool calls field `fieldName` (the text of upstream
    * `_call_preprocess`, with the ways to give tools in the port). A tools input holds only text, so the text advises
    * it only when native function calling is not active.
    *
    * @param hasInput
    *   the layout has an input field of the type [[TypeRef.tools]]
    * @param active
    *   native function calling is active for the call ([[NativeTools.isActive]])
    */
  private def noToolsError(fieldName: String, hasInput: Boolean, active: Boolean): DspyError =
    val start = s"You provided an output field $fieldName to receive the tool calls information, "
    val rest  =
      if hasInput then
        "but no tools for the provider. Native function calling is active, and an input field of the type `tools` " +
          "holds only text, so it cannot become provider tools. Please set the `tools` of the predictor, or turn " +
          "native function calling off, so that the prompt shows that input."
      else if active then
        "but did not provide any tools as the input. Please set the `tools` of the predictor. To show the tools as " +
          "text instead, turn native function calling off, and add an input field of the type `tools` " +
          "(`TypeRef.tools`)."
      else
        "but did not provide any tools as the input. Please provide the tools by adding an input field of the type " +
          "`tools` (`TypeRef.tools`), or by setting the `tools` of the predictor."
    ConfigurationError(start + rest)

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
  private[dspy4s] def encodeToolChoice(choice: ToolChoice): DynamicValue =
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
