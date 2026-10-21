package dspy4s.adapters.contracts

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.lm.contracts.LanguageModel
import dspy4s.core.contracts.ToolCall
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** Provider `tool_choice` setting for native function-calling, mirroring OpenAI's string-or-object form: `"auto"` /
  * `"required"` / `"none"`, or force a specific tool via `{type:"function", function:{name}}`.
  */
enum ToolChoice derives CanEqual:
  case Auto
  case Required
  case Off
  case Function(name: String)

/** Shared helpers for adapter-level native (provider) function-calling, used by [[dspy4s.adapters.ChatAdapter]] and
  * [[dspy4s.adapters.JSONAdapter]] so the gating, tool-schema injection, and `tool_calls` encoding stay identical.
  * Ported from dspy's `use_native_function_calling` (adapters/base.py). ReAct is intentionally unaffected — it keeps
  * the text protocol, matching upstream. See PORT_GAPS G-7b.
  */
object NativeFunctionCalling:

  /** True for the output field that receives the tool calls (`typeRef == TypeRef.toolCalls`). When native function
    * calling is active ([[isActive]]), the structured response fills such a field, and the prompt does not ask for it.
    * Else the field is a text output field: the prompt asks for a JSON object like `{"tool_calls": [{"name", "args"}]}`
    * (upstream `ToolCalls`), and the parser reads that object, or a JSON list of the calls, from the text.
    */
  def isToolCallsField(field: FieldSpec): Boolean =
    field.typeRef == TypeRef.toolCalls

  /** Whether the ambient LM advertises function-calling support (narrows the opaque `RuntimeContext.lm` ref). */
  def lmSupportsFunctionCalling(using ctx: RuntimeContext): Boolean =
    ctx.lm match
      case Some(lm: LanguageModel) => lm.supportsFunctionCalling
      case _                       => false

  /** Whether native function calling is active for a call: the adapter has `useNative` and the LM of the context
    * supports function calling (upstream `_call_preprocess`).
    */
  def isActive(useNative: Boolean)(using RuntimeContext): Boolean =
    useNative && lmSupportsFunctionCalling

  /** True for the input field that holds the texts of the tools (`typeRef == TypeRef.tools`). */
  def isToolsField(field: FieldSpec): Boolean =
    field.typeRef == TypeRef.tools

  /** The fields of `layout` that the prompt shows. With native function calling active, the prompt shows no tool calls
    * field. When `layout` also has a tool calls field, the prompt does not show the tools input field either: the
    * request has the tools as provider tools (upstream `_call_preprocess` deletes the two fields).
    */
  def promptLayout(layout: SignatureLayout, useNative: Boolean)(using RuntimeContext): SignatureLayout =
    if isActive(useNative) && layout.outputFields.exists(isToolCallsField) then
      layout
        .withInputFields(layout.inputFields.filterNot(isToolsField))
        .withOutputFields(layout.outputFields.filterNot(isToolCallsField))
    else layout

  /** The request options of native function calling (upstream `_call_preprocess`). */
  val ToolOptionKeys: Set[String] = Set("tools", "tool_choice", "parallel_tool_calls")

  /** The request options that the request must not have ([[FormattedPrompt.removedOptions]]), also when the call config
    * has them (upstream `_call_preprocess` pops them from the options of the call): [[ToolOptionKeys]] when the adapter
    * does not have `useNative`, and when `layout` has a tool calls field but the LM does not support function calling.
    * Else none.
    */
  def removedOptions(layout: SignatureLayout, useNative: Boolean)(using RuntimeContext): Set[String] =
    if !useNative || (layout.outputFields.exists(isToolCallsField) && !lmSupportsFunctionCalling) then ToolOptionKeys
    else Set.empty

  /** The provider `tools` (and optional `parallel_tool_calls`) request options to contribute, or an empty record when
    * native calling is not active. Active iff `useNative` is on AND `tools` were supplied AND the layout declares a
    * `tool_calls` output field AND the LM supports function calling (mirrors dspy's gate).
    */
  def toolOptions(
      layout           : SignatureLayout,
      tools            : Vector[ToolSpec],
      useNative        : Boolean,
      parallelToolCalls: Option[Boolean],
      toolChoice       : Option[ToolChoice] = None
  )(using RuntimeContext): DynamicValue.Record =
    val active = useNative && tools.nonEmpty && layout.outputFields.exists(isToolCallsField) &&
      lmSupportsFunctionCalling
    if !active then DynamicValue.Record.empty
    else
      val entries = Vector("tools" -> ToolSchemaBridge.toOpenAiToolsDynamic(tools)) ++
        parallelToolCalls.map(b =>
          "parallel_tool_calls" -> DynamicValue.Primitive(PrimitiveValue.Boolean(b))
        ).toVector ++
        toolChoice.map(tc => "tool_choice" -> encodeToolChoice(tc)).toVector
      DynamicValues.recordFromEntries(entries)

  /** Encode a [[ToolChoice]] as the provider `tool_choice` value: a string for the modes, an object for a forced
    * function.
    */
  private def encodeToolChoice(choice: ToolChoice): DynamicValue =
    choice match
      case ToolChoice.Auto        => str("auto")
      case ToolChoice.Required    => str("required")
      case ToolChoice.Off         => str("none")
      case ToolChoice.Function(n) => DynamicValue.Record(Chunk(
          "type"     -> str("function"),
          "function" -> DynamicValue.Record(Chunk("name" -> str(n)))
        ))

  private def str(s: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(s))

  /** Encode native tool calls as the value of a `tool_calls` output field: a sequence of `{name, args}` records,
    * matching the shape `PredictEngine` attaches to predictions. A record also has the `id` of the provider when the
    * call has one, so the typed output and the raw prediction keep that id.
    */
  def encodeToolCalls(calls: Vector[ToolCall]): DynamicValue =
    DynamicValue.Sequence(Chunk.from(calls.map(_.toRecord)))
