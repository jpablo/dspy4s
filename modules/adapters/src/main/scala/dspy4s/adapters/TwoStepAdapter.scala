package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
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
  * cannot be optimized/learned (upstream notes the same limitation). The extraction prompt has no output JSON schema,
  * so a structured field takes the schema note of a bare list or dict.
  *
  * This adapter does not replay a conversation history. As upstream, it shows a `History` input as one field value of
  * the user message.
  */
final case class TwoStepAdapter(
    extractionModel: LanguageModel,
    name           : String = "two_step"
) extends Adapter:

  /** The system message, the demos, and the current request (upstream `TwoStepAdapter.format`). A turn shows each field
    * that its values have as `name: value`. An incomplete demo shows the output fields that it has only.
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
        Message(role = MessageRole.User, text = Some(fieldsText(layout.inputFields, inputs)))
    ))

  override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
    val chat = ChatAdapter()
    for
      extractorLayout <- buildExtractorLayout(layout)
      textField        = extractorLayout.inputFields.head.name
      invocation       = AdapterInvocation(
                     layout = extractorLayout,
                     demos = Vector.empty,
                     inputs =
                       Example(values = DynamicValues.record(textField := output.text), inputKeys = Set(textField)),
                     request = LmRequest(model = extractionModel.id)
                   )
      prompt   <- chat.format(invocation)
      response <- extractionModel.call(LmRequest(model = extractionModel.id, messages = prompt.messages))
      lmOutput <- response.outputs.headOption.toRight(ParseError(name, "Extraction model returned no output"))
      parsed   <- chat.parse(extractorLayout, lmOutput)
    yield parsed

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
