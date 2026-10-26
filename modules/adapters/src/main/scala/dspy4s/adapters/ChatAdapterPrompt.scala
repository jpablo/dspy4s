package dspy4s.adapters

import dspy4s.adapters.contracts.{AdapterInvocation, FormattedPrompt, NativeTools}
import dspy4s.adapters.internal.{ConversationHistory, PromptText, TurnText}
import dspy4s.adapters.internal.PromptText.OutputSchema
import dspy4s.core.contracts.{DspyError, DynamicValues, FieldSpec, PythonText, RuntimeContext, SignatureLayout}
import zio.blocks.schema.DynamicValue

/** Builds ChatAdapter's marker-framed system, demonstration, conversation history, and input messages (upstream
  * `ChatAdapter` in `dspy/adapters/chat_adapter.py`, and `Adapter.format_system_message` in `dspy/adapters/base.py`).
  * The JSON adapter uses the marker form of the input fields too.
  */
private[adapters] object ChatAdapterPrompt:
  def format(invocation: AdapterInvocation, native: NativeTools)(using
      RuntimeContext
  ): Either[DspyError, FormattedPrompt] =
    val schema   = OutputSchema(invocation.outputJsonSchema)
    val messages = ConversationHistory.messages(invocation, native, Turns) { shown =>
      PromptText.systemMessage(invocation.layout, shown, fieldStructure(shown, schema))
    }
    val tools = native.toolOptions(invocation)
    Right(FormattedPrompt(messages = messages, requestOptions = tools.request, removedOptions = tools.removed))

  /** The marker-framed text of a turn. A user turn of a current request ends with the output reminder. An assistant
    * turn ends with the completed marker.
    */
  private object Turns extends TurnText:
    override def user(layout: SignatureLayout, values: DynamicValue.Record, mainRequest: Boolean): String =
      userText(layout, values, Option.when(mainRequest)(outputRequirements(layout)))

    override def assistant(layout: SignatureLayout, values: DynamicValue.Record): String =
      markerFields(layout.outputFields.map(field => field -> valueText(field, values))) + "\n\n" +
        ChatAdapter.CompletedMarker + "\n"

  /** The structure of the system message (upstream `ChatAdapter.format_field_structure`). */
  private def fieldStructure(layout: SignatureLayout, schema: OutputSchema): String =
    PythonText.strip(
      Vector(
        PromptText.StructureHeader,
        markerFields(layout.inputFields.map(field => field -> PromptText.placeholder(field, output = false, schema))),
        markerFields(layout.outputFields.map(field => field -> PromptText.placeholder(field, output = true, schema))),
        ChatAdapter.CompletedMarker + "\n"
      ).mkString("\n\n")
    )

  /** The output reminder of the user turn (upstream `ChatAdapter.user_message_output_requirements`). */
  private def outputRequirements(layout: SignatureLayout): String =
    "Respond with the corresponding output fields, starting with the field " +
      layout.outputFields.map(field => s"`[[ ## ${field.name} ## ]]`${PromptText.reminderHint(field)}").mkString(
        ", then "
      ) +
      s", and then ending with the marker for `${ChatAdapter.CompletedMarker}`."

  /** The text of a user turn (upstream `ChatAdapter.format_user_message_content`): the marker form of each input field
    * that `values` has, then the reminder.
    */
  def userText(layout: SignatureLayout, values: DynamicValue.Record, reminder: Option[String]): String =
    val inputs = layout.inputFields.flatMap { field =>
      DynamicValues.recordGet(values, field.name).map(value =>
        s"[[ ## ${field.name} ## ]]\n${PromptText.fieldValue(field, value)}"
      )
    }
    PythonText.strip(((("" +: inputs) ++ reminder.toVector) :+ "").mkString("\n\n"))

  /** The marker form of `fields` (upstream `ChatAdapter.format_field_with_value`). */
  private def markerFields(fields: Vector[(FieldSpec, String)]): String =
    PythonText.strip(fields.map((field, text) => s"[[ ## ${field.name} ## ]]\n$text").mkString("\n\n"))

  /** The text of the value of `field` in `values`. A field that `values` does not have shows a null, as upstream
    * `outputs.get(k, None)`.
    */
  private def valueText(field: FieldSpec, values: DynamicValue.Record): String =
    PromptText.fieldValue(field, DynamicValues.recordGet(values, field.name).getOrElse(DynamicValue.Null))
