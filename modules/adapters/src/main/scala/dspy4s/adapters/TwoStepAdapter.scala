package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.FieldRole
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

/** Two-stage adapter (port of Python dspy's `TwoStepAdapter`): the MAIN LM (resolved from the ambient
  * `RuntimeContext`, e.g. a reasoning model that struggles with structured output) is prompted in plain natural
  * language; then a second, usually smaller, `extractionModel` converts that free-form completion into the
  * signature's structured output fields via a `ChatAdapter` over an on-the-fly `text -> <output fields>` signature.
  *
  * It fits the single-call `Adapter` shape cleanly: [[format]] builds the natural prompt (the engine's one main-LM
  * call), and [[parse]] — which receives the `RuntimeContext` — performs the extraction call itself.
  *
  * Delta from Python (carried over): the extractor signature is built fresh with no demos, so the extraction step
  * cannot be optimized/learned (upstream notes the same limitation). */
final case class TwoStepAdapter(
    extractionModel: LanguageModel,
    name: String = "two_step"
) extends Adapter:

  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    val layout       = invocation.layout
    val systemText   = taskDescription(layout)
    val demoMessages = invocation.demos.flatMap { demo =>
      Vector(
        Message(role = MessageRole.User, text = Some(renderFields(layout.inputFields, demo.values))),
        Message(role = MessageRole.Assistant, text = Some(renderFields(layout.outputFields, demo.values)))
      )
    }
    val inputMessage = Message(role = MessageRole.User, text = Some(renderFields(layout.inputFields, invocation.inputs.values)))
    Right(FormattedPrompt(
      messages = Message(role = MessageRole.System, text = Some(systemText)) +: demoMessages :+ inputMessage
    ))

  override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
    val chat = ChatAdapter()
    for
      extractorLayout <- buildExtractorLayout(layout)
      invocation = AdapterInvocation(
        layout  = extractorLayout,
        demos   = Vector.empty,
        inputs  = Example(values = DynamicValues.record("text" := output.text), inputKeys = Set("text")),
        request = LmRequest(model = extractionModel.id)
      )
      prompt   <- chat.format(invocation)
      response <- extractionModel.call(LmRequest(model = extractionModel.id, messages = prompt.messages))
      lmOutput <- response.outputs.headOption.toRight(ParseError(name, "Extraction model returned no output"))
      parsed   <- chat.parse(extractorLayout, lmOutput)
    yield parsed

  /** The on-the-fly extractor signature: `text -> <the original output fields>`. */
  private def buildExtractorLayout(layout: SignatureLayout): Either[DspyError, SignatureLayout] =
    val textInput = FieldSpec(
      name        = "text",
      role        = FieldRole.Input,
      description = Some("The text from which to extract the structured output fields")
    )
    SignatureLayout.create(
      name         = s"${layout.name}Extractor",
      fields       = textInput +: layout.outputFields,
      instructions = Some("Extract the structured output fields from the provided text.")
    )

  /** Natural-language task description for the main LM: the signature instructions plus the fields the second stage
    * will extract from the reply (so the model knows what to cover, without imposing a structured format). */
  private def taskDescription(layout: SignatureLayout): String =
    val inputs  = layout.inputFields.map(_.name).mkString(", ")
    val outputs = layout.outputFields
      .map(f => s"- ${f.name}: ${f.description.filter(_ != s"$${${f.name}}").getOrElse(f.name)}")
      .mkString("\n")
    val instr = layout.instructions.getOrElse(s"Given the fields $inputs, produce the requested information.")
    s"""$instr
       |
       |Answer in natural language; you do not need to follow any structured format. The following will be
       |extracted from your answer:
       |$outputs""".stripMargin

  private def renderFields(fields: Vector[FieldSpec], values: DynamicValue.Record): String =
    fields.flatMap { field =>
      DynamicValues.recordGet(values, field.name)
        .map(DynamicValues.renderText)
        .orElse(field.defaultValue.map(_.toString))
        .map(v => s"${field.prefix.getOrElse(s"${field.name}:")} $v")
    }.mkString("\n")
