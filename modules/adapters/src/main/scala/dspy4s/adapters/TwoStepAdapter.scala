package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.NativeFunctionCalling
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.adapters.internal.AdapterTextSupport
import dspy4s.adapters.internal.DemoMessages
import dspy4s.adapters.internal.PromptText
import dspy4s.adapters.internal.PythonText
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.:=
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
final case class TwoStepAdapter(
    extractionModel: LanguageModel,
    name           : String = "two_step"
) extends Adapter:

  /** The system message, the demos, and the current request (upstream `TwoStepAdapter.format`). A turn shows each field
    * that its values have as `name: value`. An incomplete demo shows the output fields that it has only. The request
    * has no tool options, because this adapter has no native function calling (upstream `Adapter._call_preprocess`).
    */
  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    val layout = invocation.layout
    val demos  = DemoMessages.messages(layout, invocation.demos, missingOutput = None)(
      fieldsText(layout.inputFields, _),
      fieldsText(layout.outputFields, _)
    )
    val inputs = AdapterTextSupport.withInputDefaults(layout.inputFields, invocation.inputs.values)
    Right(FormattedPrompt(
      messages = (Message(role = MessageRole.System, text = Some(taskDescription(layout))) +: demos) :+
        Message(role = MessageRole.User, text = Some(fieldsText(layout.inputFields, inputs))),
      removedOptions = NativeFunctionCalling.ToolOptionKeys
    ))

  /** The input fields as `name: value` blocks (upstream `TwoStepAdapter.format_user_message_content`). */
  override def formatUserMessageContent(layout: SignatureLayout, values: DynamicValue.Record): String =
    fieldsText(layout.inputFields, values)

  /** The extraction step with no output JSON schema. See [[extract]]. */
  override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
    extract(layout, outputJsonSchema = None, output)

  /** The extraction step with the output JSON schema of `invocation`. See [[extract]]. */
  override def parse(invocation: AdapterInvocation, output: LmOutput)(using
      RuntimeContext
  ): Either[DspyError, ParsedOutput] =
    extract(invocation.layout, invocation.outputJsonSchema, output)

  /** The extraction step (upstream `TwoStepAdapter.parse`): the extraction model gets a [[ChatAdapter]] prompt for the
    * extractor signature of `layout`, with `text` as the completion of the main LM. The prompt takes the schema of each
    * structured output field from `outputJsonSchema`, as upstream copies the annotated output fields. After a parse
    * error of the chat format, the step runs one more time with the fallback of the chat adapter, a [[JSONAdapter]]
    * (upstream `ChatAdapter.__call__`). An error of the format step or of the model gives no second call.
    */
  private def extract(layout: SignatureLayout, outputJsonSchema: Option[String], output: LmOutput)(using
      RuntimeContext
  ): Either[DspyError, ParsedOutput] =
    val chat = ChatAdapter()
    for
      extractorLayout <- buildExtractorLayout(layout)
      textField        = extractorLayout.inputFields.head.name
      invocation       = AdapterInvocation(
                     layout = extractorLayout,
                     demos = Vector.empty,
                     inputs =
                       Example(values = DynamicValues.record(textField := output.text), inputKeys = Set(textField)),
                     request = LmRequest(model = extractionModel.id),
                     outputJsonSchema = outputJsonSchema
                   )
      parsed <- attempt(chat, invocation).flatMap {
                  case Left(error: ParseError) => chat.fallback match
                      case Some(next) => attempt(next, invocation).flatten
                      case None       => Left(error)
                  case other => other
                }
    yield parsed

  /** Format with `adapter`, call the extraction model, and parse. The inner result is the result of the parse step, so
    * that the caller can tell a parse error from an error of the other steps. The format step sees the extraction model
    * as the model of the context: the JSON adapter asks that model for response schema support (upstream
    * `JSONAdapter._prepare_response_format` gets the extraction model).
    */
  private def attempt(adapter: Adapter, invocation: AdapterInvocation)(using
      context: RuntimeContext
  ): Either[DspyError, Either[DspyError, ParsedOutput]] =
    val formatContext = context.withServices(context.services.copy(lm = Some(extractionModel)))
    for
      prompt   <- adapter.format(invocation)(using formatContext)
      response <-
        extractionModel.call(
          invocation.request.copy(messages = prompt.messages, options = prompt.options(invocation.request.options))
        )
      lmOutput <- response.outputs.headOption.toRight(ParseError(name, "Extraction model returned no output"))
    yield adapter.parse(invocation, lmOutput)

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
    val instructions = PromptText.instructions(layout)
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
