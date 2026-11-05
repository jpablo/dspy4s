package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.NativeFunctionCalling
import dspy4s.adapters.contracts.NativeTools
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.adapters.internal.AdapterTextSupport
import dspy4s.adapters.internal.DemoMessages
import dspy4s.adapters.internal.PromptText
import dspy4s.adapters.internal.StrictJsonSchema
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.JsonSchemaRefs
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.PythonText
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.ActivePredictContext
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import zio.blocks.schema.DynamicValue

/** Two-stage adapter (port of Python dspy's `TwoStepAdapter`): the MAIN LM (resolved from the ambient `RuntimeContext`,
  * e.g. a reasoning model that struggles with structured output) is prompted in plain natural language; then a second,
  * usually smaller, `extractionModel` converts that free-form completion into the signature's structured output fields
  * via a `ChatAdapter` over an on-the-fly `text -> <output fields>` signature.
  *
  * It fits the single-call `Adapter` shape cleanly: [[format]] builds the natural prompt (the engine's one main-LM
  * call), and [[parse]] — which receives the `RuntimeContext` — performs the extraction call itself.
  *
  * Delta from Python (carried over): the extractor signature is built fresh with no demos, so the extraction step
  * cannot be optimized/learned (upstream notes the same limitation). The extraction prompt shows the output JSON schema
  * of the invocation, in the form of the port (decision D3). With no such schema, as for `DynamicPredict`, a structured
  * field takes the schema note of a bare list or dict.
  *
  * This adapter does not replay a conversation history. As upstream, it shows a `History` input as one field value of
  * the user message.
  */
final case class TwoStepAdapter(extractionModel: LanguageModel) extends Adapter:

  override def name: String = "two_step"

  /** The system message, the demos, and the current request (upstream `TwoStepAdapter.format`). A turn shows each field
    * that its values have as `name: value`. An incomplete demo shows the output fields that it has only. The request
    * has no tool options, because this adapter has no native function calling (upstream `Adapter._call_preprocess`).
    * The prompt shows the tool specs of `invocation` as an input field when the layout has a tool calls field
    * ([[NativeTools.promptInvocation]]). A tool calls field with no tools is no error, because native function calling
    * is off in this adapter, as upstream.
    */
  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    val prompted = NativeTools.Off.promptInvocation(invocation)
    val layout   = prompted.layout
    val demos    = DemoMessages.messages(layout, prompted.demos, missingOutput = None)(
      fieldsText(layout.inputFields, _),
      fieldsText(layout.outputFields, _)
    )
    val inputs = AdapterTextSupport.withInputDefaults(layout.inputFields, prompted.inputs.values)
    Right(FormattedPrompt(
      messages = (Message(role = MessageRole.System, text = Some(taskDescription(layout))) +: demos) :+
        Message(role = MessageRole.User, text = Some(fieldsText(layout.inputFields, inputs))),
      removedOptions = NativeTools.OptionKeys
    ))

  /** The input fields as `name: value` blocks (upstream `TwoStepAdapter.format_user_message_content`). */
  override def formatUserMessageContent(layout: SignatureLayout, values: DynamicValue.Record): String =
    fieldsText(layout.inputFields, values)

  /** The extraction step (upstream `TwoStepAdapter.parse`): the extraction model gets a [[ChatAdapter]] prompt for the
    * extractor signature of the layout of `invocation`, with `text` as the completion of the main LM. The prompt takes
    * the schema of each structured output field from the output JSON schema of `invocation`, as upstream copies the
    * annotated output fields. [[AdapterCall.run]] does the format, the model call, the parse, and the fallback of the
    * chat adapter, as upstream `ChatAdapter()(lm=extraction_model, ...)` does. Thus the extraction step sends the
    * callback events inside the parse scope of this adapter, and its model call goes to the usage scope of the call.
    *
    * The result is the first parsed output (upstream `parsed_result[0]`). A reply with no outputs is a [[ParseError]]
    * ("Extraction model returned no output"), with no second call: the chat adapter parses each output, so a reply with
    * no outputs is no parse error of the chat adapter. An output with an empty text is such a parse error, and it gets
    * the fallback of the chat adapter ([[AdapterCall.run]]). Each [[ParseError]] of the step becomes the error of
    * [[originalCompletionError]]. Each other error stays as it is, as upstream catches only `AdapterParseError`.
    *
    * A tool turn is a reply with provider tool calls for a layout with a tool calls field. It has the rule of upstream
    * `Adapter._call_postprocess`. A reply with no text, or a layout with no other output field, gets no extraction.
    * Else the extraction gives only the other output fields, because the model has nothing to extract for the calls. A
    * [[ParseError]] of the extraction gives no values (upstream `except AdapterParseError: value = {}`), and each other
    * error stays an error. The calls then fill the tool calls field ([[AdapterTextSupport.toolTurnValues]]), and the
    * evidence of the extraction stays.
    *
    * The model call of the extraction runs in the scope of [[withActiveLayout]].
    */
  override def parse(invocation: AdapterInvocation, output: LmOutput)(using
      RuntimeContext
  ): Either[DspyError, ParsedOutput] =
    val layout = invocation.layout
    if output.toolCalls.isEmpty || !layout.outputFields.exists(NativeFunctionCalling.isToolCallsField) then
      extract(invocation, output.text)
    else
      val fields = AdapterTextSupport.textFields(layout, providerCalls = true)
      val parsed =
        if output.text.isEmpty || fields.isEmpty then Right(ParsedOutput(DynamicValue.Record.empty))
        else
          extract(withOutputs(invocation, fields), output.text) match
            case Left(_: ParseError) => Right(ParsedOutput(DynamicValue.Record.empty))
            case other               => other
      parsed.map(found =>
        found.copy(values = AdapterTextSupport.toolTurnValues(layout, output.toolCalls, found.values))
      )

  /** `invocation` with only the output fields `fields`, in its layout and in its output JSON schema. Thus a response
    * schema of the extraction asks for no other field.
    */
  private def withOutputs(invocation: AdapterInvocation, fields: Vector[FieldSpec]): AdapterInvocation =
    val kept    = fields.map(_.name).toSet
    val removed = invocation.layout.outputFields.map(_.name).filterNot(kept.contains).toSet
    invocation.copy(
      layout = invocation.layout.withOutputFields(fields),
      outputJsonSchema = invocation.outputJsonSchema.map(schema =>
        StrictJsonSchema.withoutProperties(JsonSchemaRefs.inlineRoot(schema), removed)
      )
    )

  /** The first parsed output of the extraction model for the output fields of `invocation`, with `completion` as the
    * text of the main LM.
    */
  private def extract(invocation: AdapterInvocation, completion: String)(using
      RuntimeContext
  ): Either[DspyError, ParsedOutput] =
    for
      extractorLayout <- buildExtractorLayout(invocation.layout)
      textField        = extractorLayout.inputFields.head.name
      extraction       = AdapterInvocation(
                     layout = extractorLayout,
                     demos = Vector.empty,
                     inputs =
                       Example(values = DynamicValues.record(textField := completion), inputKeys = Set(textField)),
                     request = LmRequest(model = extractionModel.id),
                     outputJsonSchema = invocation.outputJsonSchema
                   )
      result <- withActiveLayout(extractorLayout)(AdapterCall.run(ChatAdapter(), extractionModel, extraction))
                  .flatMap(_.parsed.headOption.toRight(ParseError(name, "Extraction model returned no output")))
                  .left
                  .map(originalCompletionError(_, completion))
    yield result

  /** `body` with `layout` as the layout of the active predictor, under the same predictor name. With no active
    * predictor, `body` gets no scope. Under `streamify`, the stream wrapper of the extraction model reads this layout
    * ([[ActivePredictContext]]). Thus the reasoning of that model gives a token only when the extractor layout has a
    * reasoning field, and the extraction then fills the final value of that field.
    */
  private def withActiveLayout[A](layout: SignatureLayout)(body: => A): A =
    ActivePredictContext.current match
      case Some(active) => ActivePredictContext.withActive(active.name, layout)(body)
      case None         => body

  /** The error of the extraction step for the parse error `error` (upstream `TwoStepAdapter.parse`): the text "Failed
    * to parse response from the original completion: " and the message of `error`, with the completion of the main LM
    * `completion` as the raw response (upstream `lm_response=completion`). Each other error stays as it is.
    */
  private def originalCompletionError(error: DspyError, completion: String): DspyError =
    error match
      case parse: ParseError => ParseError(
          name,
          s"Failed to parse response from the original completion: ${parse.message}",
          raw = Some(completion)
        )
      case other => other

  /** The on-the-fly extractor signature: `text -> <the original output fields>` (upstream
    * `_create_extractor_signature`). When the original signature already has an output field named `text`, the
    * synthetic input is renamed (`_text`, `__text`, ...) — SignatureLayout.create rejects duplicate names, which would
    * otherwise fail every parse deterministically.
    */
  private def buildExtractorLayout(layout: SignatureLayout): Either[DspyError, SignatureLayout] =
    val reserved  = layout.outputFields.map(_.name).toSet
    val inputName = Iterator.iterate("text")("_" + _).dropWhile(reserved.contains).next()
    // The spaces in the middle come from a line continuation in the upstream source.
    val outputs      = layout.outputFields.map(field => s"`${field.name}`").mkString(", ")
    val instructions = s"The input is a text that should contain all the necessary information to produce the fields " +
      s"$outputs.             Your job is to extract the fields from the text verbatim. Extract precisely the " +
      "appropriate value (content) for each field."
    SignatureLayout.create(
      name = s"${layout.name}Extractor",
      inputFields = Vector(FieldSpec(name = inputName)),
      outputFields = layout.outputFields,
      instructions = Some(instructions)
    )

  /** The system message for the main LM (upstream `TwoStepAdapter.format_task_description`): the input and output
    * fields, and the instructions of the signature.
    */
  private def taskDescription(layout: SignatureLayout): String =
    val instructions = layout.effectiveInstructions
    (Vector(
      "You are a helpful assistant that can solve tasks based on user input.",
      "As input, you will be provided with:\n" + PromptText.descriptions(layout.inputFields),
      "Your outputs must contain:\n" + PromptText.descriptions(layout.outputFields),
      "You should lay out your outputs in detail so that your answer can be understood by another agent"
    ) ++ Option.when(instructions.nonEmpty)(s"Specific instructions: $instructions").toVector).mkString("\n")

  /** The fields of `fields` that `values` has, as `name: value` blocks (upstream `format_user_message_content` and
    * `format_assistant_message_content`). A value is its Python `str` text. A structured value is JSON text, where
    * upstream writes the Python `repr`.
    */
  private def fieldsText(fields: Vector[FieldSpec], values: DynamicValue.Record): String =
    PythonText.strip(
      fields.flatMap(field =>
        DynamicValues.recordGet(values, field.name).map(value => s"${field.name}: ${PythonText.str(value)}")
      )
        .mkString("\n\n")
    )
