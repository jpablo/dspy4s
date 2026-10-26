package dspy4s.adapters.internal

import dspy4s.adapters.contracts.AdapterDemos
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.NativeFunctionCalling
import dspy4s.adapters.contracts.NativeTools
import dspy4s.adapters.internal.PromptText.OutputSchema
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.History
import dspy4s.core.contracts.PythonText
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ToolCall
import dspy4s.core.contracts.TypeRef
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import zio.blocks.schema.DynamicValue

/** The text that one adapter gives for the turns of a conversation. [[ConversationHistory]] uses the same text for a
  * demo, for a replayed turn of the history, and for the current request.
  */
private[adapters] trait TurnText:

  /** The text of a user turn: the values of the input fields of `layout`. With `mainRequest`, the text also has the
    * output reminder of a current request, when the adapter has one.
    */
  def user(layout: SignatureLayout, values: DynamicValue.Record, mainRequest: Boolean): String

  /** The text of an assistant turn: the values of the output fields of `layout`, in the form of a demo. */
  def assistant(layout: SignatureLayout, values: DynamicValue.Record): String

  /** The structure of the system message for `layout` (upstream `format_field_structure`): the form of each field, with
    * the placeholder of its value ([[PromptText.placeholder]]). `schema` gives the schema note of an output field.
    */
  def structure(layout: SignatureLayout, schema: OutputSchema): String

/** The turn text of an adapter that puts each field in a frame: the Chat adapter frames a field with its marker, and
  * the XML adapter frames it with its tag. The two adapters make a user turn and the fields of the structure with the
  * same steps, each with its frame.
  */
private[adapters] abstract class FramedTurns extends TurnText:

  /** The text `text` of the field `name` in the frame of the adapter. */
  protected def frame(name: String, text: String): String

  /** The output reminder of the user turn of a current request (upstream `user_message_output_requirements`). */
  protected def outputRequirements(layout: SignatureLayout): String

  final override def user(layout: SignatureLayout, values: DynamicValue.Record, mainRequest: Boolean): String =
    userText(layout, values, Option.when(mainRequest)(outputRequirements(layout)))

  /** The text of a user turn (upstream `format_user_message_content`): the frame of each input field that `values` has,
    * then the reminder.
    */
  final def userText(layout: SignatureLayout, values: DynamicValue.Record, reminder: Option[String]): String =
    val inputs = layout.inputFields.flatMap { field =>
      DynamicValues.recordGet(values, field.name).map(value => frame(field.name, PromptText.fieldValue(field, value)))
    }
    PythonText.strip(((("" +: inputs) ++ reminder.toVector) :+ "").mkString("\n\n"))

  /** The frame of each field of `fields` with its text, with a blank line between two fields, stripped (upstream
    * `format_field_with_value`).
    */
  protected final def framed(fields: Vector[(FieldSpec, String)]): String =
    PythonText.strip(fields.map((field, text) => frame(field.name, text)).mkString("\n\n"))

  /** The frame of each field of `fields` with its placeholder ([[PromptText.placeholder]]), for the structure. */
  final def placeholders(fields: Vector[FieldSpec], output: Boolean, schema: OutputSchema): String =
    framed(fields.map(field => field -> PromptText.placeholder(field, output, schema)))

  /** The text of the value of the output field `field` in `values`. A field that `values` does not have shows a null,
    * as upstream `outputs.get(k, None)`.
    */
  protected final def outputText(field: FieldSpec, values: DynamicValue.Record): String =
    PromptText.fieldValue(field, DynamicValues.recordGet(values, field.name).getOrElse(DynamicValue.Null))

/** The message sequence of a request, with the replay of a [[History]] input field as earlier turns (PORT_GAPS G-28;
  * upstream `Adapter.format` and `Adapter.format_conversation_history`). The Chat, JSON, and XML adapters share this
  * logic. Each adapter supplies only its [[TurnText]].
  */
private[adapters] object ConversationHistory:

  /** The prompt of the Chat, JSON, and XML adapters (upstream `Adapter.format`): the [[messages]] of the request, with
    * the system message of [[PromptText.systemMessage]] and the structure of `turns`, and the tool options of `native`
    * ([[NativeTools.toolOptions]]).
    *
    * @param options
    *   the request options of the adapter, for example the response format of the JSON adapter. The tool options come
    *   after them.
    */
  def prompt(
      invocation: AdapterInvocation,
      native    : NativeTools,
      turns     : TurnText,
      options   : DynamicValue.Record = DynamicValue.Record.empty
  )(using RuntimeContext): FormattedPrompt =
    val schema = OutputSchema(invocation.outputJsonSchema)
    val tools  = native.toolOptions(invocation)
    FormattedPrompt(
      messages = messages(invocation, native, turns) { shown =>
        PromptText.systemMessage(invocation.layout, shown, turns.structure(shown, schema))
      },
      requestOptions = DynamicValues.mergeRecords(options, tools.request),
      removedOptions = tools.removed
    )

  /** The input field that holds the conversation history: the first input field with [[TypeRef.history]]. */
  def historyField(layout: SignatureLayout): Option[FieldSpec] =
    layout.inputFields.find(_.typeRef == TypeRef.history)

  /** The messages of one request, in this order: system, demos, history turns, current request.
    *
    *   - The prompt shows the fields of the render layout, `native.promptLayout(invocation.layout)`
    *     ([[NativeTools.promptLayout]]). It has the history field.
    *   - A demo shows each field of the render layout. A demo that has a value for the history field shows that value
    *     as a field. The history of a demo is not replayed. [[DemoMessages]] gives the order of the demos and the text
    *     of an incomplete demo.
    *   - The current request, and each replayed turn, use the render layout without the history field. Only the current
    *     request takes the default value of an input field that it does not have.
    *
    * @param native
    *   the native function calling options of the adapter. A turn with tool calls becomes an assistant message with
    *   `toolCalls` and one [[MessageRole.Tool]] message for each result only when native function calling is active
    *   ([[NativeTools.isActive]]).
    * @param systemText
    *   the text of the system message for the render layout
    */
  private def messages(invocation: AdapterInvocation, native: NativeTools, turns: TurnText)(
      systemText: SignatureLayout => String
  )(using RuntimeContext): Vector[Message] =
    val renderLayout  = native.promptLayout(invocation.layout)
    val historyName   = historyField(invocation.layout).map(_.name)
    val requestLayout = historyName.fold(renderLayout) { name =>
      renderLayout.withInputFields(renderLayout.inputFields.filterNot(_.name == name))
    }
    val demos = DemoMessages.messages(renderLayout, invocation.demos, Some(AdapterDemos.NotSupplied))(
      turns.user(renderLayout, _, mainRequest = false),
      turns.assistant(renderLayout, _)
    )
    val history     = historyName.flatMap(History.fromInput(invocation.inputs.values, _)).getOrElse(History.empty)
    val nativeCalls = native.isActive
    val earlier     = history.messages.flatMap(replay(invocation.layout, requestLayout, _, nativeCalls, turns))
    val inputs      = AdapterTextSupport.withInputDefaults(requestLayout.inputFields, invocation.inputs.values)
    val current     = Message(role = MessageRole.User, text = Some(turns.user(requestLayout, inputs, mainRequest = true)))
    (Message(role = MessageRole.System, text = Some(systemText(renderLayout))) +: demos) ++ earlier :+ current

  /** The turns of one history message. A key of the message that is not a field of the signature is ignored.
    *
    * @param layout
    *   the full layout of the signature. It gives the tool calls field, which `requestLayout` can lack.
    * @param requestLayout
    *   the layout of a request: no history field
    */
  private def replay(
      layout       : SignatureLayout,
      requestLayout: SignatureLayout,
      message      : DynamicValue.Record,
      nativeCalls  : Boolean,
      turns        : TurnText
  ): Vector[Message] =
    // An output field with a null value is absent (upstream `message.get(name) is None`).
    def has(field: FieldSpec): Boolean = DynamicValues.present(message, field.name)

    // A replayed user turn keeps the output reminder of its request (upstream f207d35cf), so the messages of one call
    // are the start of the messages of the next call. A message with no input field gets no user turn: a turn of
    // outputs only must not get a user turn that holds the reminder alone. An input key with a null value counts, and
    // the turn shows `None` for it (upstream `format_conversation_history` tests `name in message`).
    val inputs   = requestLayout.inputFields.filter(field => DynamicValues.recordGet(message, field.name).isDefined)
    val userTurn = Option
      .when(inputs.nonEmpty)(turns.user(requestLayout.withInputFields(inputs), message, mainRequest = true))
      .filter(_.nonEmpty)
      .map(text => Message(role = MessageRole.User, text = Some(text)))

    val toolCallsField = layout.outputFields.find(NativeFunctionCalling.isToolCallsField).filter(has)
    val calls          =
      toolCallsField.flatMap(field => DynamicValues.recordGet(message, field.name)).flatMap(ToolCall.fromDynamic)
    val results = History.toolCallResults(message)

    def assistantText(fields: Vector[FieldSpec], values: DynamicValue.Record): Option[String] =
      Option.when(fields.nonEmpty)(turns.assistant(requestLayout.withOutputFields(fields), values)).filter(_.nonEmpty)

    val answerTurns = calls match
      case Some(toolCalls) if nativeCalls =>
        val content = assistantText(
          layout.outputFields.filter(field => has(field) && !NativeFunctionCalling.isToolCallsField(field)),
          message
        )
        // A provider matches a tool message with its call by the id. Results that do not match the calls are dropped.
        val matched = toolCalls.nonEmpty && toolCalls.forall(_.id.isDefined) &&
          toolCalls.map(_.id) == results.map(_.callId)
        if matched then
          Message(role = MessageRole.Assistant, text = content, toolCalls = toolCalls) +: results.map { result =>
            Message(
              role = MessageRole.Tool,
              text = Some(toolResultText(result.value)),
              toolCallId = result.callId,
              name = Some(result.name)
            )
          }
        else content.map(text => Message(role = MessageRole.Assistant, text = Some(text))).toVector
      case _ =>
        // Each output field of the request shows, with a null for a field that the message does not have, so that a
        // message with inputs only also gets an assistant turn (upstream `format_assistant_message_content` with the
        // signature of the request). The calls go as a field value with no ids and no results (`PromptText.jsonable`).
        // The results go in one more user turn, as the object of upstream `ToolCallResults`.
        val assistantTurn = assistantText(requestLayout.outputFields, message)
          .map(text => Message(role = MessageRole.Assistant, text = Some(text)))
        val resultsTurn = Option.when(results.nonEmpty) {
          val shown = DynamicValues.record(
            History.ToolCallResultsKey -> History.withToolCallResults(DynamicValue.Record.empty, results)
          )
          Message(role = MessageRole.User, text = Some(turns.user(ToolCallResultsLayout, shown, mainRequest = false)))
        }
        assistantTurn.toVector ++ resultsTurn.toVector

    userTurn.toVector ++ answerTurns

  /** The signature of the user turn that shows the results of text tool calls (upstream
    * `_TOOL_CALL_RESULTS_SIGNATURE`). The field has no prefix, so each adapter labels it with its name.
    */
  private val ToolCallResultsLayout: SignatureLayout = SignatureLayout.of(
    name = "ToolCallResults",
    inputFields = Vector(FieldSpec(History.ToolCallResultsKey)),
    outputFields = Vector.empty,
    instructions = None
  )

  /** The text of a tool message (upstream `_tool_result_content`): the string itself, else the JSON text of the value.
    */
  private def toolResultText(value: DynamicValue): String =
    DynamicValues.asString(value).getOrElse(PythonText.json(value))
