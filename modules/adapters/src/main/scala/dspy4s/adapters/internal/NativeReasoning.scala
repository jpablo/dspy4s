package dspy4s.adapters.internal

import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.JsonSchemaRefs
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.SignatureOps.*
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.updated
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import zio.blocks.schema.DynamicValue

/** The native reasoning step of an adapter call (upstream `Reasoning.adapt_to_native_lm_feature`, called in
  * `Adapter._call_preprocess`, and `Reasoning.parse_lm_response`, called in `Adapter._call_postprocess`). The step is
  * for the reasoning fields: the output fields of the type [[TypeRef.reasoning]] that are not optional. Upstream also
  * leaves out a `dspy.Reasoning | None` field, because its annotation is not a class.
  *
  * The rules, before the model call ([[planned]]):
  *   - The effort is the `reasoning_effort` of the request, else that of [[LanguageModel.defaultOptions]], else `low`.
  *   - The call uses the native reasoning when the effort is not null, the model supports reasoning
  *     ([[LanguageModel.supportsReasoning]]), and the model is not a `gpt-5` model on the chat API. OpenAI gives no
  *     reasoning on that API (upstream has the same rule, and names a defect of LiteLLM).
  *   - A call that uses the native reasoning has the effort in its request, and the prompt and the schema of the
  *     outputs have no reasoning field. The default instructions still name the field, as upstream.
  *   - Each other call stays as it is: the reasoning field is a text field. A null effort stays in the request, and the
  *     provider layer removes it.
  *
  * The rule, after the model call ([[filled]], for each call): a reasoning field takes the reasoning of the reply when
  * it is not empty, also when the text of the reply has a value for the field. Else it takes the value of the parse,
  * else its default, else the empty text. Upstream gives `None` in the last case, and the port gives a text, because a
  * typed output has the type `Reasoning`, not an `Option`.
  */
private[dspy4s] object NativeReasoning:

  private val EffortKey: String     = "reasoning_effort"
  private val DefaultEffort: String = "low"

  /** The names of the reasoning fields of `layout`. */
  def fieldNames(layout: SignatureLayout): Vector[String] =
    layout.outputFields.filter(isReasoningField).map(_.name)

  /** The layout that the prompt of a request with the options `options` shows: `layout` with no reasoning field, when
    * the call uses the native reasoning. A stream state reads this layout, so the stream follows the parser.
    */
  def shownLayout(model: LanguageModel, options: DynamicValue.Record, layout: SignatureLayout): SignatureLayout =
    if effort(model, options).isDefined then layout.replaceOutputs(layout.outputFields.filterNot(isReasoningField))
    else layout

  /** The invocation of the format and the parse. See the rules in the object documentation. It keeps the layout of
    * `invocation` as [[AdapterInvocation.fullLayout]].
    */
  def planned(model: LanguageModel, invocation: AdapterInvocation): AdapterInvocation =
    val names = fieldNames(invocation.layout).toSet
    val full  = invocation.copy(signatureLayout = Some(invocation.layout))
    effort(model, invocation.request.options).filter(_ => names.nonEmpty) match
      case None        => full
      case Some(found) => full.copy(
          layout = shownLayout(model, invocation.request.options, invocation.layout),
          outputJsonSchema = invocation.outputJsonSchema.map(schema =>
            StrictJsonSchema.withoutProperties(JsonSchemaRefs.inlineRoot(schema), names)
          ),
          request = invocation.request.copy(options = invocation.request.options.updated(EffortKey, found))
        )

  /** `values`, the parsed values of `output`, with each reasoning field of `layout` as the object documentation says.
    * The output fields keep the order of `layout`. A value with no output field stays after them.
    */
  def filled(layout: SignatureLayout, output: LmOutput, values: DynamicValue.Record): DynamicValue.Record =
    if fieldNames(layout).isEmpty then values
    else
      val outputs = layout.outputFields.flatMap { field =>
        if isReasoningField(field) then Some(field.name -> reasoningOf(field, output, values))
        else DynamicValues.recordGet(values, field.name).map(field.name -> _)
      }
      val names = layout.outputFields.map(_.name).toSet
      DynamicValues.recordFromEntries(outputs ++ values.fields.filterNot((name, _) => names.contains(name)))

  private def isReasoningField(field: FieldSpec): Boolean =
    field.typeRef == TypeRef.reasoning && !field.optional

  /** The effort of a call that uses the native reasoning. `None` for each other call. */
  private def effort(model: LanguageModel, options: DynamicValue.Record): Option[DynamicValue] =
    val stated = DynamicValues
      .recordGet(options, EffortKey)
      .orElse(DynamicValues.recordGet(model.defaultOptions, EffortKey))
      .getOrElse(DynamicValues.string(DefaultEffort))
    Option.when(model.supportsReasoning && !isGpt5Chat(model) && !DynamicValues.isNull(stated))(stated)

  private def isGpt5Chat(model: LanguageModel): Boolean =
    model.id.contains("gpt-5") && model.mode == LmMode.Chat

  private def reasoningOf(field: FieldSpec, output: LmOutput, values: DynamicValue.Record): DynamicValue =
    if output.reasoning.nonEmpty then DynamicValues.string(output.reasoning)
    else
      DynamicValues
        .recordGet(values, field.name)
        .filterNot(DynamicValues.isNull)
        .orElse(field.defaultValue)
        .getOrElse(DynamicValues.string(""))
