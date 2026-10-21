package dspy4s.lm.runtime

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.updated
import dspy4s.lm.contracts.ContentPart
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.core.contracts.LmUsage
import dspy4s.lm.contracts.Message
import dspy4s.core.contracts.ToolCall
import dspy4s.lm.providers.DynamicJson
import dspy4s.lm.providers.OpenAiUsage
import dspy4s.lm.providers.ResponsesWire
import dspy4s.lm.providers.WireKeys
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

final case class ProviderLanguageModel(
    id                         : String,
    mode                       : LmMode,
    invoke                     : DynamicValue => Either[DspyError, DynamicValue],
    override val defaultOptions: DynamicValue.Record = DynamicValue.Record.empty
) extends LanguageModel:
  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    val effectiveMode     = request.mode
    val normalizedRequest = ProviderRequestNormalizer.normalize(request, defaultOptions = defaultOptions)
    invoke(normalizedRequest).flatMap(raw => ProviderResponseParser.parse(raw, effectiveMode))

object ProviderRequestNormalizer:
  private def str(s: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(s))

  /** The `type` value of a tool call on the wire. Each tool call is a function call. */
  private val functionCallType = "function"

  /** The option bag that the provider sends: `defaultOptions` overlaid by the options of the request, with no option
    * whose value is null. Thus a null option of the request removes a default option (upstream `_canonical` in
    * `dspy/clients/execution.py` drops each `None` after the merge). [[normalize]] and the cache key of
    * `ManagedLanguageModel` share this merge, so the key covers the options on the wire.
    */
  def effectiveOptions(request: LmRequest, defaultOptions: DynamicValue.Record): DynamicValue.Record =
    val merged = DynamicValues.mergeRecords(defaultOptions, request.options)
    DynamicValue.Record(merged.fields.filter {
      case (_, _: DynamicValue.Null.type) => false
      case _                              => true
    })

  /** Build the provider request payload as a `DynamicValue.Record`: the merged option bag (defaults overlaid by the
    * request's options), plus model / mode / request_id and the encoded messages or prompt.
    */
  def normalize(
      request       : LmRequest,
      defaultOptions: DynamicValue.Record = DynamicValue.Record.empty
  ): DynamicValue.Record =
    var rec = effectiveOptions(request, defaultOptions)
    rec = rec.updated(WireKeys.model, str(request.model))
    rec = rec.updated(WireKeys.mode, str(request.mode.toString.toLowerCase))
    request.requestId.foreach(id => rec = rec.updated(WireKeys.requestId, str(id)))
    request.mode match
      case LmMode.Chat | LmMode.Responses =>
        rec.updated(WireKeys.messages, DynamicValue.Sequence(Chunk.from(request.messages.map(encodeMessage))))
      case LmMode.Text =>
        val prompt = request.messages.map(messageText).mkString("\n").trim
        rec.updated(WireKeys.prompt, str(prompt))

  /** The wire form of one message. A message with no tool calls, no tool call id, and no name has the keys `role` and
    * `content` only. The other keys follow the OpenAI chat format (upstream `format_conversation_history` in
    * `dspy/adapters/base.py`):
    *
    *   - `tool_calls`, when the message has tool calls. When such a message has no text and no parts, `content` is JSON
    *     null, not an empty string.
    *   - `tool_call_id` and `name`, when the message has them. A tool message names the call that it answers with them.
    */
  private def encodeMessage(message: Message): DynamicValue =
    val role                  = message.role.toString.toLowerCase
    val content: DynamicValue =
      if message.parts.nonEmpty then DynamicValue.Sequence(Chunk.from(message.parts.map(encodePart)))
      else if message.toolCalls.nonEmpty && !message.text.exists(_.nonEmpty) then DynamicValue.Null
      else str(message.text.getOrElse(""))
    val toolCalls = Option.when(message.toolCalls.nonEmpty)(
      WireKeys.toolCalls -> DynamicValue.Sequence(Chunk.from(message.toolCalls.map(encodeToolCall)))
    )
    val toolCallId = message.toolCallId.map(callId => WireKeys.toolCallId -> str(callId))
    val name       = message.name.map(toolName => WireKeys.name -> str(toolName))
    DynamicValue.Record(
      Chunk.from(Vector(WireKeys.role -> str(role), WireKeys.content -> content) ++ toolCalls ++ toolCallId ++ name)
    )

  /** A tool call in the provider form: `{"id", "type": "function", "function": {"name", "arguments"}}` (upstream
    * `_tool_call_as_openai_message_tool_call`). `arguments` is the JSON text of the arguments. A call with no id has no
    * `id` key.
    */
  private def encodeToolCall(call: ToolCall): DynamicValue =
    val function = DynamicValue.Record(Chunk(
      WireKeys.name      -> str(call.name),
      WireKeys.arguments -> str(DynamicJson.encodeExact(call.args))
    ))
    DynamicValue.Record(Chunk.from(
      call.id.map(callId => WireKeys.id -> str(callId)).toVector ++
        Vector(WireKeys.`type` -> str(functionCallType), WireKeys.function -> function)
    ))

  private def encodePart(part: ContentPart): DynamicValue =
    val base   = Vector(WireKeys.`type` -> str(part.kind), WireKeys.text -> str(part.payload))
    val fields =
      if part.metadata.nonEmpty then
        base :+
          (WireKeys.metadata -> DynamicValue.Record(
            Chunk.from(part.metadata.iterator.map((k, v) => k -> str(v)).toSeq)
          ))
      else base
    DynamicValue.Record(Chunk.from(fields))

  /** The text of one message for the prompt of `LmMode.Text`: the text of the message, or the payloads of its parts.
    *
    * A completion endpoint has no tool protocol, so the prompt has no form for the tool fields. A tool message gives
    * its text (the result of the tool), as each other message does. The tool calls of an assistant message, the tool
    * call id, and the tool name do not go into the prompt. Upstream joins the contents of the messages in the same way
    * (`litellm_text_completion` in `dspy/clients/lm.py`).
    */
  private def messageText(message: Message): String =
    message.text.getOrElse(message.parts.map(_.payload).mkString("\n"))

object ProviderResponseParser:
  import DynamicJson.{field, asRecord, asString}

  /** The `LmResponse` of the provider body `raw` in `mode`. The chat mode gives one output for each choice. The text
    * mode reads `text`, else the choices. The Responses mode gives one output for the whole response
    * (`ResponsesWire.outputs`, upstream `responses_outputs`); a body with no `output` array is a `ParseError`. A
    * response with no output (no text and no tool call) is a `ParseError`.
    */
  def parse(raw: DynamicValue, mode: LmMode): Either[DspyError, LmResponse] =
    val outputs = mode match
      case LmMode.Chat      => parseChatOutputs(raw)
      case LmMode.Text      => parseTextOutputs(raw)
      case LmMode.Responses => ResponsesWire.outputs(raw)

    outputs.flatMap { entries =>
      if entries.isEmpty then Left(ParseError("lm", "Provider response does not contain any outputs"))
      else
        Right(
          LmResponse(
            outputs = entries,
            usage = parseUsage(raw),
            modelName = field(raw, WireKeys.model).flatMap(asString),
            cacheHit = false
          )
        )
    }

  private def parseChatOutputs(raw: DynamicValue): Either[DspyError, Vector[LmOutput]] =
    seqField(raw, WireKeys.choices).map { choices =>
      choices.flatMap { choice =>
        val rec             = asRecord(choice)
        val textFromMessage = rec.flatMap(r => field(r, WireKeys.message)).flatMap(extractText)
        val textFromChoice  = rec.flatMap(r => field(r, WireKeys.text)).flatMap(asString)
        val text            = textFromMessage.orElse(textFromChoice).map(_.trim).filter(_.nonEmpty)
        val toolCalls       =
          rec.flatMap(r => field(r, WireKeys.message)).flatMap(asRecord).map(parseToolCalls).getOrElse(Vector.empty)
        // Emit an output when there is EITHER text OR tool calls. A function-calling response typically has
        // content:null and only a `tool_calls` array, so gating solely on text would drop the call entirely.
        Option.when(text.nonEmpty || toolCalls.nonEmpty) {
          val metadata = rec
            .map(r => DynamicValues.recordFilterKeys(r, k => k != WireKeys.message && k != WireKeys.text))
            .getOrElse(DynamicValue.Record.empty)
          LmOutput(text = text.getOrElse(""), toolCalls = toolCalls, metadata = metadata)
        }
      }
    }

  private def parseTextOutputs(raw: DynamicValue): Either[DspyError, Vector[LmOutput]] =
    field(raw, WireKeys.text).flatMap(asString).map(_.trim).filter(_.nonEmpty) match
      case Some(text) => Right(Vector(LmOutput(text = text)))
      case None       => parseChatOutputs(raw)

  private def parseUsage(raw: DynamicValue): Option[LmUsage] =
    field(raw, WireKeys.usage).flatMap(asRecord).map(usage => OpenAiUsage.fromDynamic(usage).toLmUsage)

  private def parseToolCalls(message: DynamicValue.Record): Vector[ToolCall] =
    seqField(message, WireKeys.toolCalls) match
      case Right(entries) => entries.flatMap { call =>
          asRecord(call).flatMap { rec =>
            val functionRec = field(rec, WireKeys.function).flatMap(asRecord).getOrElse(rec)
            field(functionRec, WireKeys.name).flatMap(asString).map { name =>
              ToolCall(
                name = name,
                args = ToolCallAssembler.decodeArguments(field(functionRec, WireKeys.arguments)),
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
    def text(key: String): Option[String] = field(call, key).flatMap(asString).filter(_.nonEmpty)
    text(WireKeys.callId).orElse(text(WireKeys.id))

  private def extractText(node: DynamicValue): Option[String] =
    field(node, WireKeys.content) match
      case Some(content) => asString(content) match
          case Some(text) => Some(text)
          case None       =>
            val fromParts = DynamicJson.asSequence(content).iterator
              .flatMap(item => asRecord(item).flatMap(r => field(r, WireKeys.text)).flatMap(asString).map(_.trim))
              .mkString("\n").trim
            Option
              .when(fromParts.nonEmpty)(fromParts)
              .orElse(field(node, WireKeys.text).flatMap(asString).map(_.trim).filter(_.nonEmpty))
      case None => field(node, WireKeys.text).flatMap(asString).map(_.trim).filter(_.nonEmpty)

  /** A response field treated as an array: absent or null -> empty; a sequence -> its elements; anything else -> a
    * parse error (mirrors the old `asVector`).
    */
  private def seqField(value: DynamicValue, name: String): Either[DspyError, Vector[DynamicValue]] =
    field(value, name) match
      case None                             => Right(Vector.empty)
      case Some(_: DynamicValue.Null.type)  => Right(Vector.empty)
      case Some(seq: DynamicValue.Sequence) => Right(seq.elements.iterator.toVector)
      case Some(other)                      =>
        Left(ParseError("lm", s"Expected array-like response field '$name', found: ${other.getClass.getSimpleName}"))
