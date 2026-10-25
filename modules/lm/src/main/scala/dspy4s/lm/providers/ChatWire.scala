package dspy4s.lm.providers

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.ToolCall
import dspy4s.core.contracts.updated
import dspy4s.lm.contracts.ContentPart
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import zio.blocks.schema.DynamicValue

import java.util.Locale

/** The wire of the OpenAI chat-completions API: the body of a request in the chat mode and in the text mode, and the
  * outputs of a reply. [[OpenAiLanguageModel]] uses this wire in these two modes, and [[ResponsesWire]] in the
  * Responses mode.
  */
private[lm] object ChatWire:
  import DynamicValues.{asRecord, asSequence, asString, recordFromEntries, recordGet, sequence, string}

  /** The `type` value of a tool call on the wire. Each tool call is a function call. */
  private val functionCallType = "function"

  // ── The request ─────────────────────────────────────────────────────────────────────────────────────────────

  /** The chat body of `request`: the option bag of the request over `defaultOptions` ([[LmRequest.effectiveOptions]]),
    * the model, and the encoded messages ([[encodeMessage]]).
    */
  def request(
      request       : LmRequest,
      defaultOptions: DynamicValue.Record = DynamicValue.Record.empty
  ): DynamicValue.Record =
    options(request, defaultOptions)
      .updated(WireKeys.messages, sequence(request.messages.map(encodeMessage)))

  /** The body of `request` in the text mode: the option bag and the model as in [[request]], and the prompt. The prompt
    * is the text of each message ([[messageText]]), one line for each message.
    */
  def textRequest(
      request       : LmRequest,
      defaultOptions: DynamicValue.Record = DynamicValue.Record.empty
  ): DynamicValue.Record =
    val prompt = request.messages.map(messageText).mkString("\n").trim
    options(request, defaultOptions).updated(WireKeys.prompt, string(prompt))

  /** The name of `role` on the wire, in lower case. The Responses wire uses it too. */
  private[providers] def roleName(role: MessageRole): String = role.toString.toLowerCase(Locale.ROOT)

  /** The wire form of one content part: `{"type": <kind>, "text": <payload>}`, and `metadata` when the part has it. The
    * Responses wire sends a part that it does not change in this form too.
    */
  private[providers] def encodePart(part: ContentPart): DynamicValue.Record =
    val base     = Vector(WireKeys.`type` -> string(part.kind), WireKeys.text -> string(part.payload))
    val metadata = Option.when(part.metadata.nonEmpty)(
      WireKeys.metadata -> recordFromEntries(part.metadata.iterator.map((k, v) => k -> string(v)).toSeq)
    )
    recordFromEntries(base ++ metadata)

  private def options(request: LmRequest, defaultOptions: DynamicValue.Record): DynamicValue.Record =
    request.effectiveOptions(defaultOptions).updated(WireKeys.model, string(request.model))

  /** The wire form of one message. A message with no tool calls, no tool call id, and no name has the keys `role` and
    * `content` only. The other keys follow the OpenAI chat format (upstream `format_conversation_history` in
    * `dspy/adapters/base.py`):
    *
    *   - `tool_calls`, when the message has tool calls. When such a message has no text and no parts, `content` is JSON
    *     null, not an empty string.
    *   - `tool_call_id` and `name`, when the message has them. A tool message names the call that it answers with them.
    */
  private def encodeMessage(message: Message): DynamicValue =
    val content: DynamicValue =
      if message.parts.nonEmpty then sequence(message.parts.map(encodePart))
      else if message.toolCalls.nonEmpty && !message.text.exists(_.nonEmpty) then DynamicValue.Null
      else string(message.text.getOrElse(""))
    val toolCalls = Option.when(message.toolCalls.nonEmpty)(
      WireKeys.toolCalls -> sequence(message.toolCalls.map(encodeToolCall))
    )
    val toolCallId = message.toolCallId.map(callId => WireKeys.toolCallId -> string(callId))
    val name       = message.name.map(toolName => WireKeys.name -> string(toolName))
    recordFromEntries(
      Vector(WireKeys.role -> string(roleName(message.role)), WireKeys.content -> content) ++ toolCalls ++ toolCallId ++
        name
    )

  /** A tool call in the provider form: `{"id", "type": "function", "function": {"name", "arguments"}}` (upstream
    * `_tool_call_as_openai_message_tool_call`). `arguments` is the JSON text of the arguments. A call with no id has no
    * `id` key.
    */
  private def encodeToolCall(call: ToolCall): DynamicValue =
    val function = DynamicValues.record(
      WireKeys.name      -> string(call.name),
      WireKeys.arguments -> string(DynamicValues.encodeJson(call.args))
    )
    recordFromEntries(
      call.id.map(callId => WireKeys.id -> string(callId)).toVector ++
        Vector(WireKeys.`type` -> string(functionCallType), WireKeys.function -> function)
    )

  /** The text of one message for the prompt of the text mode: the text of the message, or the payloads of its parts.
    *
    * A completion endpoint has no tool protocol, so the prompt has no form for the tool fields. A tool message gives
    * its text (the result of the tool), as each other message does. The tool calls of an assistant message, the tool
    * call id, and the tool name do not go into the prompt. Upstream joins the contents of the messages in the same way
    * (`litellm_text_completion` in `dspy/clients/lm.py`).
    */
  private def messageText(message: Message): String =
    message.text.getOrElse(message.parts.map(_.payload).mkString("\n"))

  // ── The reply ───────────────────────────────────────────────────────────────────────────────────────────────

  /** The outputs of a chat reply: one output for each choice that has text or tool calls. A function-calling reply
    * usually has `content: null` and only a `tool_calls` array, so a choice with tool calls and no text gives an output
    * too. The metadata of an output has the keys of its choice other than `message` and `text`.
    */
  def outputs(raw: DynamicValue): Either[DspyError, Vector[LmOutput]] =
    seqField(raw, WireKeys.choices).map { choices =>
      choices.flatMap { choice =>
        val rec             = asRecord(choice)
        val textFromMessage = rec.flatMap(r => recordGet(r, WireKeys.message)).flatMap(extractText)
        val textFromChoice  = rec.flatMap(r => recordGet(r, WireKeys.text)).flatMap(asString)
        val text            = textFromMessage.orElse(textFromChoice).map(_.trim).filter(_.nonEmpty)
        val toolCalls       =
          rec.flatMap(r => recordGet(r, WireKeys.message)).flatMap(asRecord).map(parseToolCalls).getOrElse(Vector.empty)
        Option.when(text.nonEmpty || toolCalls.nonEmpty) {
          val metadata = rec
            .map(r => DynamicValues.recordFilterKeys(r, k => k != WireKeys.message && k != WireKeys.text))
            .getOrElse(DynamicValue.Record.empty)
          LmOutput(text = text.getOrElse(""), toolCalls = toolCalls, metadata = metadata)
        }
      }
    }

  /** The outputs of a reply in the text mode: the top-level `text`, else the outputs of the choices ([[outputs]]). */
  def textOutputs(raw: DynamicValue): Either[DspyError, Vector[LmOutput]] =
    recordGet(raw, WireKeys.text).flatMap(asString).map(_.trim).filter(_.nonEmpty) match
      case Some(text) => Right(Vector(LmOutput(text = text)))
      case None       => outputs(raw)

  private def parseToolCalls(message: DynamicValue.Record): Vector[ToolCall] =
    seqField(message, WireKeys.toolCalls) match
      case Right(entries) => entries.flatMap { call =>
          asRecord(call).flatMap { rec =>
            val functionRec = recordGet(rec, WireKeys.function).flatMap(asRecord).getOrElse(rec)
            recordGet(functionRec, WireKeys.name).flatMap(asString).map { name =>
              ToolCall(
                name = name,
                args = ToolCallAssembler.decodeArguments(recordGet(functionRec, WireKeys.arguments)),
                id = toolCallId(rec)
              )
            }
          }
        }
      case Left(_) => Vector.empty

  /** The id of a provider tool call: `call_id` when the entry has one, else `id` (upstream
    * `_provider_tool_call_to_tool_call_dict`). An item of the Responses API has an item `id` and the `call_id` that a
    * tool result must name. An entry with no id, or with an empty id, gives `None`.
    */
  private def toolCallId(call: DynamicValue.Record): Option[String] =
    def text(key: String): Option[String] = recordGet(call, key).flatMap(asString).filter(_.nonEmpty)
    text(WireKeys.callId).orElse(text(WireKeys.id))

  private def extractText(node: DynamicValue): Option[String] =
    recordGet(node, WireKeys.content) match
      case Some(content) => asString(content) match
          case Some(text) => Some(text)
          case None       =>
            val fromParts = asSequence(content).iterator
              .flatMap(item => asRecord(item).flatMap(r => recordGet(r, WireKeys.text)).flatMap(asString).map(_.trim))
              .mkString("\n").trim
            Option
              .when(fromParts.nonEmpty)(fromParts)
              .orElse(recordGet(node, WireKeys.text).flatMap(asString).map(_.trim).filter(_.nonEmpty))
      case None => recordGet(node, WireKeys.text).flatMap(asString).map(_.trim).filter(_.nonEmpty)

  /** A reply field as an array: an absent field or a null gives no element, and a value that is not an array is a
    * `ParseError`.
    */
  private def seqField(value: DynamicValue, name: String): Either[DspyError, Vector[DynamicValue]] =
    recordGet(value, name) match
      case None                             => Right(Vector.empty)
      case Some(_: DynamicValue.Null.type)  => Right(Vector.empty)
      case Some(seq: DynamicValue.Sequence) => Right(seq.elements.iterator.toVector)
      case Some(other)                      =>
        Left(ParseError("lm", s"Expected array-like response field '$name', found: ${other.getClass.getSimpleName}"))
