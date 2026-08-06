package dspy4s.adapters

import dspy4s.adapters.contracts.{AdapterInvocation, FormattedPrompt, NativeFunctionCalling, ToolChoice}
import dspy4s.core.contracts.{DspyError, DynamicValues, FieldSpec, RuntimeContext, SignatureLayout}
import dspy4s.lm.contracts.{Message, MessageRole}
import zio.blocks.schema.DynamicValue

/** Builds ChatAdapter's marker-framed system, demonstration, and input messages. */
private[adapters] object ChatAdapterPrompt:
  def format(
      invocation              : AdapterInvocation,
      useNativeFunctionCalling: Boolean,
      parallelToolCalls       : Option[Boolean],
      toolChoice              : Option[ToolChoice]
  )(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    val layout       = invocation.layout
    val renderLayout = layout.withOutputFields(layout.outputFields.filterNot(NativeFunctionCalling.isToolCallsField))

    val systemMessage = Message(
      role = MessageRole.System,
      text = Some(buildSystemPrompt(renderLayout, invocation.outputJsonSchema))
    )

    val demoMessages = invocation
      .demos
      .flatMap { demo =>
        val userText      = renderInputs(renderLayout.inputFields, demo.values)
        val assistantText = renderOutputs(renderLayout.outputFields, demo.values) + "\n\n" +
          ChatAdapter.CompletedMarker + "\n"
        Vector(
          Message(role = MessageRole.User, text = Some(userText)),
          Message(role = MessageRole.Assistant, text = Some(assistantText))
        )
      }

    val inputMessage = Message(
      role = MessageRole.User,
      text = Some(
        renderInputs(renderLayout.inputFields, invocation.inputs.values) + "\n\n" + outputRequirements(renderLayout)
      )
    )

    Right(
      FormattedPrompt(
        messages = Vector(systemMessage) ++ demoMessages ++ Vector(inputMessage),
        requestOptions = NativeFunctionCalling.toolOptions(
          layout,
          invocation.tools,
          useNativeFunctionCalling,
          parallelToolCalls,
          toolChoice
        )
      )
    )

  private def buildSystemPrompt(layout: SignatureLayout, outputJsonSchema: Option[String]): String =
    val inputBlock  = fieldDescriptionBlock(layout.inputFields, role = "input")
    val outputBlock = fieldDescriptionBlock(layout.outputFields, role = "output")
    val schemaBlock = outputJsonSchema match
      case Some(schema) => s"\n\nYour output fields must conform to this JSON schema:\n$schema"
      case None         => ""
    val structureExample = exampleStructure(layout)
    val instructions     = layout.instructions.getOrElse(defaultInstructions(layout))
    s"""$inputBlock
       |
       |$outputBlock$schemaBlock
       |
       |All interactions will be structured in the following way, with the appropriate values filled in.
       |
       |$structureExample
       |
       |In adhering to this structure, your objective is: $instructions""".stripMargin

  /** Numbered field list mirroring Python's `get_field_description_string`. */
  private def fieldDescriptionBlock(fields: Vector[FieldSpec], role: String): String =
    if fields.isEmpty then
      s"Your $role fields are: (none)."
    else
      val header = s"Your $role fields are:"
      val lines  = fields
        .zipWithIndex
        .map { case (field, idx) =>
          val typeName = ChatAdapter.displayTypeName(field.typeRef)
          val descPart = field.description match
            case Some(desc) if desc != s"$${${field.name}}" && desc.nonEmpty => s": $desc"
            case _                                                           => ""
          val constraintsPart =
            if field.constraints.nonEmpty then
              s" Constraints: ${field.constraints.map(_.render).mkString(", ")}"
            else
              ""
          val enumPart =
            if field.enumValues.nonEmpty then
              s" (must be one of: ${field.enumValues.mkString(", ")})"
            else
              ""
          s"${idx + 1}. `${field.name}` ($typeName)$descPart$constraintsPart$enumPart"
        }
      (header +: lines).mkString("\n")

  private def defaultInstructions(layout: SignatureLayout): String =
    val inputs  = layout.inputFields.map(_.name).mkString(", ")
    val outputs = layout.outputFields.map(_.name).mkString(", ")
    s"Given the fields $inputs, produce the fields $outputs."

  private def exampleStructure(layout: SignatureLayout): String =
    val inputBlock = layout
      .inputFields
      .map { field =>
        s"[[ ## ${field.name} ## ]]\n{${field.name}}"
      }
      .mkString("\n\n")
    val outputBlock = layout
      .outputFields
      .map { field =>
        val note = ChatAdapter
          .structureHint(field.typeRef)
          .fold("") { hint =>
            s"        # note: the value you produce $hint"
          }
        s"[[ ## ${field.name} ## ]]\n{${field.name}}$note"
      }
      .mkString("\n\n")
    Vector(inputBlock, outputBlock, ChatAdapter.CompletedMarker).filter(_.nonEmpty).mkString("\n\n")

  private def outputRequirements(layout: SignatureLayout): String =
    val outputs = layout
      .outputFields
      .map { field =>
        val hint = ChatAdapter.reminderHint(field.typeRef).fold("")(text => s" ($text)")
        s"`[[ ## ${field.name} ## ]]`$hint"
      }
      .mkString(", then ")
    s"Respond with the corresponding output fields, starting with the field $outputs, and then ending with the marker for `${ChatAdapter
        .CompletedMarker}`."

  private def renderInputs(fields: Vector[FieldSpec], values: DynamicValue.Record): String =
    renderFieldBlock(
      fields,
      values
    )

  private def renderOutputs(fields: Vector[FieldSpec], values: DynamicValue.Record): String =
    renderFieldBlock(
      fields,
      values
    )

  private def renderFieldBlock(fields: Vector[FieldSpec], values: DynamicValue.Record): String =
    fields
      .flatMap { field =>
        val resolved = DynamicValues
          .recordGet(values, field.name)
          .orElse(field.defaultValue)
          .map(DynamicValues.renderText)
        resolved.map(rendered => s"[[ ## ${field.name} ## ]]\n$rendered")
      }
      .mkString("\n\n")
