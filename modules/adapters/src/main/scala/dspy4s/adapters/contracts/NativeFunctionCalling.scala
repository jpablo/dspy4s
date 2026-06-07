package dspy4s.adapters.contracts

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.ToolCall
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** Shared helpers for adapter-level native (provider) function-calling, used by [[dspy4s.adapters.ChatAdapter]] and
  * [[dspy4s.adapters.JSONAdapter]] so the gating, tool-schema injection, and `tool_calls` encoding stay identical.
  * Ported from dspy's `use_native_function_calling` (adapters/base.py). ReAct is intentionally unaffected — it keeps
  * the text protocol, matching upstream. See PORT_GAPS G-7b. */
object NativeFunctionCalling:

  /** True for the output field that receives native provider tool calls (`typeRef == TypeRef.toolCalls`). Such a
    * field is filled from the structured response, never rendered/parsed as text. */
  def isToolCallsField(field: FieldSpec): Boolean =
    field.role == FieldRole.Output && field.typeRef == TypeRef.toolCalls

  /** Whether the ambient LM advertises function-calling support (narrows the opaque `RuntimeContext.lm` ref). */
  def lmSupportsFunctionCalling(using ctx: RuntimeContext): Boolean =
    ctx.lm match
      case Some(lm: LanguageModel) => lm.supportsFunctionCalling
      case _                       => false

  /** The provider `tools` (and optional `parallel_tool_calls`) request options to contribute, or an empty record
    * when native calling is not active. Active iff `useNative` is on AND `tools` were supplied AND the layout
    * declares a `tool_calls` output field AND the LM supports function calling (mirrors dspy's gate). */
  def toolOptions(
      layout: SignatureLayout,
      tools: Vector[ToolSpec],
      useNative: Boolean,
      parallelToolCalls: Option[Boolean]
  )(using RuntimeContext): DynamicValue.Record =
    val active =
      useNative && tools.nonEmpty && layout.outputFields.exists(isToolCallsField) && lmSupportsFunctionCalling
    if !active then DynamicValue.Record.empty
    else
      val entries =
        Vector("tools" -> ToolSchemaBridge.toOpenAiToolsDynamic(tools)) ++
          parallelToolCalls.map(b => "parallel_tool_calls" -> DynamicValue.Primitive(PrimitiveValue.Boolean(b))).toVector
      DynamicValues.recordFromEntries(entries)

  /** Encode native tool calls as the value of a `tool_calls` output field: a sequence of `{name, args}` records,
    * matching the shape `PredictEngine` attaches to predictions. */
  def encodeToolCalls(calls: Vector[ToolCall]): DynamicValue =
    DynamicValue.Sequence(Chunk.from(calls.map { call =>
      DynamicValue.Record(Chunk(
        "name" -> DynamicValue.Primitive(PrimitiveValue.String(call.name)),
        "args" -> call.args
      ))
    }))
