package dspy4s.adapters

import dspy4s.adapters.contracts.{AdapterInvocation, FormattedPrompt, NativeTools}
import dspy4s.adapters.internal.{ConversationHistory, FramedTurns, PromptText}
import dspy4s.adapters.internal.PromptText.OutputSchema
import dspy4s.core.contracts.{DspyError, PythonText, RuntimeContext, SignatureLayout}
import zio.blocks.schema.DynamicValue

/** Builds ChatAdapter's marker-framed system, demonstration, conversation history, and input messages (upstream
  * `ChatAdapter` in `dspy/adapters/chat_adapter.py`, and `Adapter.format_system_message` in `dspy/adapters/base.py`).
  * The JSON adapter uses the marker form of the input fields too.
  */
private[adapters] object ChatAdapterPrompt:
  def format(invocation: AdapterInvocation, native: NativeTools)(using
      RuntimeContext
  ): Either[DspyError, FormattedPrompt] =
    Right(ConversationHistory.prompt(invocation, native, Turns))

  /** The text of a user turn (upstream `ChatAdapter.format_user_message_content`): the marker form of each input field
    * that `values` has, then the reminder.
    */
  def userText(layout: SignatureLayout, values: DynamicValue.Record, reminder: Option[String]): String =
    Turns.userText(layout, values, reminder)

  /** The marker form of the input fields of `layout` with their placeholders, for the structure. */
  def inputStructure(layout: SignatureLayout, schema: OutputSchema): String =
    Turns.placeholders(layout.inputFields, output = false, schema)

  /** The marker-framed text of a turn (upstream `ChatAdapter.format_field_with_value`). A user turn of a current
    * request ends with the output reminder. An assistant turn ends with the completed marker.
    */
  private object Turns extends FramedTurns:
    override protected def frame(name: String, text: String): String = s"${ChatAdapter.marker(name)}\n$text"

    override def assistant(layout: SignatureLayout, values: DynamicValue.Record): String =
      framed(layout.outputFields.map(field => field -> outputText(field, values))) + "\n\n" +
        ChatAdapter.CompletedMarker + "\n"

    /** The structure of the system message (upstream `ChatAdapter.format_field_structure`). */
    override def structure(layout: SignatureLayout, schema: OutputSchema): String =
      PythonText.strip(
        Vector(
          PromptText.StructureHeader,
          placeholders(layout.inputFields, output = false, schema),
          placeholders(layout.outputFields, output = true, schema),
          ChatAdapter.CompletedMarker + "\n"
        ).mkString("\n\n")
      )

    /** The output reminder of the user turn (upstream `ChatAdapter.user_message_output_requirements`). */
    override protected def outputRequirements(layout: SignatureLayout): String =
      "Respond with the corresponding output fields, starting with the field " +
        layout.outputFields.map(field => s"`${ChatAdapter.marker(field.name)}`${PromptText.reminderHint(field)}")
          .mkString(", then ") +
        s", and then ending with the marker for `${ChatAdapter.CompletedMarker}`."
