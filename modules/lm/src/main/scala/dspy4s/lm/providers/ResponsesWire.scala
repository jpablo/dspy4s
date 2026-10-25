package dspy4s.lm.providers

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.ToolCall
import dspy4s.core.contracts.updated
import dspy4s.lm.contracts.ContentPart
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmToolCallDelta
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

/** The wire shapes of the OpenAI Responses API (upstream `chat_to_responses` in `dspy/clients/legacy_requests.py`,
  * `responses_outputs` in `dspy/clients/legacy_outputs.py`, and the stream events of lm15 `OpenAILM` in
  * `dspy/_vendor/lm15/providers/openai.py`).
  */
private[lm] object ResponsesWire:
  import DynamicValues.{asRecord, isNull, record, recordFromEntries, recordGet, sequence, sequenceAt, string}

  /** The keys of the Responses wire that the chat wire does not use. The shared keys are in [[WireKeys]]. */
  private object Keys:
    final val input               = "input"
    final val maxTokens           = "max_tokens"
    final val maxCompletionTokens = "max_completion_tokens"
    final val maxOutputTokens     = "max_output_tokens"
    final val reasoningEffort     = "reasoning_effort"
    final val reasoning           = "reasoning"
    final val effort              = "effort"
    final val summary             = "summary"
    final val tools               = "tools"
    final val toolChoice          = "tool_choice"
    final val description         = "description"
    final val responseFormat      = "response_format"
    final val format              = "format"
    final val jsonSchema          = "json_schema"
    final val imageUrl            = "image_url"
    final val url                 = "url"
    final val stop                = "stop"
    final val annotations         = "annotations"
    final val citedText           = "cited_text"
    final val supportedText       = "supported_text"
    final val documentTitle       = "document_title"
    final val title               = "title"
    final val outputIndex         = "output_index"
    final val item                = "item"
    final val response            = "response"
    final val annotation          = "annotation"

  /** The values of the `type` and `role` keys that the translation reads or writes. */
  private object Kinds:
    final val function           = "function"
    final val functionCall       = "function_call"
    final val functionCallOutput = "function_call_output"
    final val message            = "message"
    final val reasoning          = "reasoning"
    final val text               = "text"
    final val inputText          = "input_text"
    final val outputText         = "output_text"
    final val imageUrl           = "image_url"
    final val inputImage         = "input_image"
    final val jsonSchema         = "json_schema"
    final val citation           = "citation"

    /** The block types that hold text, in a request and in a response. */
    val textBlocks: Set[String] = Set(text, inputText, outputText)

    /** The part kinds of a tool message whose text goes into the output of the call. */
    val toolTextBlocks: Set[String] = Set(text, inputText)

  /** The `type` values of the events of a Responses stream that the stream parser reads. */
  private object Events:
    final val textDelta             = "response.output_text.delta"
    final val refusalDelta          = "response.refusal.delta"
    final val reasoningSummaryDelta = "response.reasoning_summary_text.delta"
    final val reasoningTextDelta    = "response.reasoning_text.delta"
    final val annotationAdded       = "response.output_text.annotation.added"
    final val itemAdded             = "response.output_item.added"
    final val argumentsDelta        = "response.function_call_arguments.delta"
    final val completed             = "response.completed"
    final val error                 = "error"
    final val responseError         = "response.error"

  /** The finish reasons of the end chunk of a Responses stream (upstream lm15 `StreamEndEvent`). */
  private object Finish:
    final val toolCall = "tool_call"
    final val stop     = "stop"

  /** The Responses body of `request`: the option bag of the request over `defaultOptions`
    * ([[dspy4s.lm.contracts.LmRequest.effectiveOptions]]), the model, and the input items of the typed messages. The
    * rules of upstream `chat_to_responses`:
    *
    *   - An `input` option stays, and the body then has no other change. A `messages` option is removed: the messages
    *     of the request take its place, as on the chat wire.
    *   - The messages become `input`, one item or more for each message, in order:
    *     - A tool message gives
    *       `{"type": "function_call_output", "output": <the text>, "call_id": <the tool call id>}`. The text of a
    *       message with parts is the joined payload of its `text` and `input_text` parts. `call_id` is absent when the
    *       message has no tool call id.
    *     - Each other message gives `{"role", "content": <blocks>, "name"?}`, except an assistant message that has tool
    *       calls and no blocks. The text of a message with no parts gives one `{"type": <t>, "text"}` block, where
    *       `<t>` is `output_text` for an assistant message and `input_text` for each other role. An assistant message
    *       with tool calls and no text has no block (the chat wire sends a null content). A `text`, `input_text`, or
    *       `output_text` part becomes a `<t>` block, and an `image_url` part becomes
    *       `{"type": "input_image", "image_url": <the payload>}`. Each other part has its chat form
    *       (`ChatWire.encodePart`).
    *     - After the item of an assistant message, each of its tool calls gives
    *       `{"type": "function_call", "name", "arguments", "call_id"?}` (`arguments` is the JSON text of the arguments,
    *       and `call_id` is the id of the call).
    *   - `max_completion_tokens` becomes `max_tokens` when `max_tokens` is absent. Then `max_tokens` becomes
    *     `max_output_tokens`.
    *   - `reasoning_effort` becomes `reasoning: {"effort": <value>, "summary": "auto"}`, unless `reasoning` is set.
    *   - A tool `{"type": "function", "function": {...}}` becomes `{"type": "function", ...<the keys of function>}`. A
    *     function tool with a null `description` loses the key. The other keys of the tool stay.
    *   - A `tool_choice` `{"type": "function", "function": {"name": n}}` becomes `{"type": "function", "name": n}`.
    *   - `response_format` moves to `text.format`. A `{"type": "json_schema", "json_schema": {...}}` format is
    *     flattened to `{"type": "json_schema", ...<the keys of json_schema>}`, the form that the Responses API takes
    *     (upstream builds this form from a pydantic model; the port sends a record).
    *   - An empty `stop` is removed. Each top-level field with a null value is removed too
    *     ([[DynamicJson.withoutNullFields]], the rule of [[DynamicJson.encode]]). Upstream removes a null
    *     `temperature`, `top_p`, `n`, `logprobs`, `reasoning`, and `tool_choice`, and the encode removes each other
    *     null option.
    */
  def request(
      request       : LmRequest,
      defaultOptions: DynamicValue.Record = DynamicValue.Record.empty
  ): DynamicValue.Record =
    val options = without(
      request.effectiveOptions(defaultOptions),
      WireKeys.messages
    ).updated(WireKeys.model, string(request.model))
    if recordGet(options, Keys.input).isDefined then options
    else
      val body  = options.updated(Keys.input, sequence(request.messages.flatMap(inputItems)))
      val steps = Vector(withTokenLimit, withReasoning, withTools, withToolChoice, withTextFormat, withoutEmptyStop)
      DynamicJson.withoutNullFields(steps.foldLeft(body)((current, step) => step(current)))

  /** The outputs of a Responses body (upstream `responses_outputs`): one output for the whole response.
    *
    *   - `text` is the joined text of the `text`, `output_text`, and `input_text` parts of each `message` item.
    *   - Each `function_call` item gives a tool call with its `name`, its `arguments` (a JSON text, decoded as for the
    *     chat path, except that a text that is not JSON gives an empty record), and the id `call_id`.
    *   - The texts of the `content` (else the `summary`) parts of each `reasoning` item, joined, give the `reasoning`
    *     of the output (upstream `reasoning_content`).
    *   - Each annotation of a message part gives a citation in the `citations` of the output:
    *     `{"type": "citation", "text", "title", "url", "metadata"}`, with no key for a null value (`text` is
    *     `cited_text`, else `text`, else `supported_text`; `title` is `document_title`, else `title`; `metadata` has
    *     the other keys of the annotation that are not null).
    *
    * The result is an empty vector when the response has no text (or only white space) and no tool call. A body with no
    * `output` array is a `ParseError`. A body with an `error` object is the error of [[OpenAiErrors.fromReplyError]],
    * also with a success status (upstream `OpenAILM.parse_response`).
    *
    * @param model
    *   the model of the request, for the details of an error
    */
  def outputs(raw: DynamicValue, model: Option[String] = None): Either[DspyError, Vector[LmOutput]] =
    OpenAiErrors.fromReplyError(raw, model).toLeft(()).flatMap(_ => outputItems(raw))

  private def outputItems(raw: DynamicValue): Either[DspyError, Vector[LmOutput]] =
    recordGet(raw, WireKeys.output) match
      case Some(items: DynamicValue.Sequence) =>
        val records   = items.elements.iterator.flatMap(asRecord).toVector
        val messages  = records.filter(isKind(_, Kinds.message))
        val parts     = messages.flatMap(item => sequenceAt(item, WireKeys.content)).flatMap(asRecord)
        val text      = parts.filter(isTextBlock).flatMap(part => stringOf(part, WireKeys.text)).mkString
        val toolCalls = records.filter(isKind(_, Kinds.functionCall)).map(toolCall)
        val reasoning = records.filter(isKind(_, Kinds.reasoning)).flatMap(reasoningTexts).mkString
        val citations = parts.flatMap(part => sequenceAt(part, Keys.annotations)).flatMap(asRecord).map(citation)
        Right(
          if text.trim.isEmpty && toolCalls.isEmpty then Vector.empty
          else Vector(LmOutput(text = text, toolCalls = toolCalls, reasoning = reasoning, citations = citations))
        )
      case _ => Left(ParseError("lm", "The Responses body has no output array"))

  /** The chunk of one event of a Responses stream (upstream lm15 `OpenAILM.parse_stream_events` and
    * `_parse_single_stream_event` in `dspy/_vendor/lm15/providers/openai.py`). The `type` of the event selects the
    * rule:
    *
    *   - `response.output_text.delta` and `response.refusal.delta` give a chunk with the text `delta`.
    *   - `response.reasoning_summary_text.delta` and `response.reasoning_text.delta` give a chunk with the reasoning
    *     text `delta` (upstream `ThinkingDelta`). A reasoning item with content parts and summary parts gives the two
    *     texts here, and [[outputs]] gives the content parts only.
    *   - `response.output_text.annotation.added` with an `annotation` object gives a chunk with the citation of the
    *     annotation (upstream `CitationDelta`). The citation has the form of a citation of [[outputs]], so the
    *     assembled stream has the citations of a plain call. Upstream lm15 also drops an annotation with no url, no
    *     title, and no text. The plain path of the port keeps it, and so does the stream.
    *   - `response.output_item.added` of a `function_call` item gives a chunk with one tool-call delta: the index is
    *     `output_index`, the id is the `call_id` (else the `id`) of the item, and the delta has the `name` and the
    *     `arguments` (else an empty text) of the item.
    *   - `response.function_call_arguments.delta` gives a chunk with one tool-call delta: the index is `output_index`,
    *     and the arguments fragment is `delta`.
    *   - `response.completed` gives the end chunk. Its finish reason is `tool_call` when the `output` of the response
    *     has a `function_call` item, else `stop`. Its usage is the `usage` of the response.
    *   - `error` and `response.error` give a `Left` with the error of [[OpenAiErrors.fromResponsesStreamError]].
    *   - Each other event gives no chunk. The port has no chunk field for audio or images (G-32), so it skips their
    *     events.
    *
    * An `output_index` that is not a number gives the index 0, and a `delta` that is not a string gives an empty text.
    * An empty id or name counts as no id or name.
    */
  def streamChunk(event: DynamicValue.Record, model: Option[String]): Either[DspyError, Option[LmChunk]] =
    // Only the tool-call events read the index, so the parser reads it on demand.
    lazy val index = recordGet(event, Keys.outputIndex).flatMap(DynamicValues.asLong).fold(0)(_.toInt)
    def delta      = stringOf(event, WireKeys.delta).getOrElse("")
    stringOf(event, WireKeys.`type`).getOrElse("") match
      case Events.textDelta | Events.refusalDelta                   => Right(Some(LmChunk(text = delta)))
      case Events.reasoningSummaryDelta | Events.reasoningTextDelta => Right(Some(LmChunk(reasoning = delta)))
      case Events.annotationAdded                                   =>
        val annotation = recordGet(event, Keys.annotation).flatMap(asRecord)
        Right(annotation.map(found => LmChunk(citations = Vector(citation(found)))))
      case Events.itemAdded =>
        val call = recordGet(event, Keys.item).flatMap(asRecord).filter(isKind(_, Kinds.functionCall))
        Right(call.map { item =>
          val arguments = stringOf(item, WireKeys.arguments).getOrElse("")
          toolCallChunk(LmToolCallDelta(index, idOf(item), nonEmpty(item, WireKeys.name), Some(arguments)))
        })
      case Events.argumentsDelta =>
        Right(Some(toolCallChunk(LmToolCallDelta(index, idOf(event), nonEmpty(event, WireKeys.name), Some(delta)))))
      case Events.completed =>
        val response = recordGet(event, Keys.response).flatMap(asRecord)
        val usage    = response.flatMap(recordGet(_, WireKeys.usage)).flatMap(asRecord)
        val hasTool  = response.toVector.flatMap(sequenceAt(_, WireKeys.output)).flatMap(asRecord)
          .exists(isKind(_, Kinds.functionCall))
        Right(Some(LmChunk(
          finishReason = Some(if hasTool then Finish.toolCall else Finish.stop),
          usage = usage.map(OpenAiUsage.fromDynamic(_).toLmUsage)
        )))
      case Events.error | Events.responseError => Left(OpenAiErrors.fromResponsesStreamError(event, model))
      case _                                   => Right(None)

  // ── The request ─────────────────────────────────────────────────────────────────────────────────────────────

  /** The input items of one message, in order (the rules of [[request]]). */
  private def inputItems(message: Message): Vector[DynamicValue] =
    message.role match
      case MessageRole.Tool => Vector(functionCallOutput(message))
      case role             =>
        val assistant = role == MessageRole.Assistant
        val blocks    = contentBlocks(message, if assistant then Kinds.outputText else Kinds.inputText)
        val item      = Option.when(blocks.nonEmpty || !assistant || message.toolCalls.isEmpty)(
          recordFromEntries(
            Vector(WireKeys.role -> string(ChatWire.roleName(role)), WireKeys.content -> sequence(blocks)) ++
              message.name.map(name => WireKeys.name -> string(name))
          )
        )
        item.toVector ++ (if assistant then message.toolCalls.map(functionCall) else Vector.empty)

  /** The `function_call_output` item of a tool message. The output of a message with parts is the joined payload of its
    * text parts. A message with no text gives an empty output.
    */
  private def functionCallOutput(message: Message): DynamicValue =
    val output =
      if message.parts.nonEmpty then
        message.parts.filter(part => Kinds.toolTextBlocks.contains(part.kind)).map(_.payload).mkString
      else message.text.getOrElse("")
    recordFromEntries(
      Vector(WireKeys.`type` -> string(Kinds.functionCallOutput), WireKeys.output -> string(output)) ++
        message.toolCallId.map(callId => WireKeys.callId -> string(callId))
    )

  /** The blocks of the content of a message (upstream `_content`). `textType` is the type of a text block. */
  private def contentBlocks(message: Message, textType: String): Vector[DynamicValue] =
    if message.parts.nonEmpty then message.parts.map(part => contentBlock(part, textType))
    else if message.toolCalls.nonEmpty && !message.text.exists(_.nonEmpty) then Vector.empty
    else Vector(textBlock(textType, message.text.getOrElse("")))

  private def contentBlock(part: ContentPart, textType: String): DynamicValue =
    if Kinds.textBlocks.contains(part.kind) then textBlock(textType, part.payload)
    else if part.kind == Kinds.imageUrl then
      record(WireKeys.`type` -> string(Kinds.inputImage), Keys.imageUrl -> string(part.payload))
    else ChatWire.encodePart(part)

  private def textBlock(textType: String, text: String): DynamicValue =
    record(WireKeys.`type` -> string(textType), WireKeys.text -> string(text))

  /** The `function_call` item of one tool call. A call with no id has no `call_id`. */
  private def functionCall(call: ToolCall): DynamicValue =
    recordFromEntries(
      Vector(
        WireKeys.`type`    -> string(Kinds.functionCall),
        WireKeys.name      -> string(call.name),
        WireKeys.arguments -> string(DynamicValues.encodeJson(call.args))
      ) ++ call.id.map(callId => WireKeys.callId -> string(callId))
    )

  private def withTokenLimit(body: DynamicValue.Record): DynamicValue.Record =
    val moved = (recordGet(body, Keys.maxCompletionTokens), recordGet(body, Keys.maxTokens)) match
      case (Some(limit), None) => without(body, Keys.maxCompletionTokens).updated(Keys.maxTokens, limit)
      case _                   => body
    recordGet(moved, Keys.maxTokens) match
      case Some(limit) => without(moved, Keys.maxTokens).updated(Keys.maxOutputTokens, limit)
      case None        => moved

  private def withReasoning(body: DynamicValue.Record): DynamicValue.Record =
    recordGet(body, Keys.reasoningEffort) match
      case Some(effort) =>
        val rest = without(body, Keys.reasoningEffort)
        if present(rest, Keys.reasoning).isDefined then rest
        else rest.updated(Keys.reasoning, record(Keys.effort -> effort, Keys.summary -> string("auto")))
      case None => body

  private def withTools(body: DynamicValue.Record): DynamicValue.Record =
    recordGet(body, Keys.tools) match
      case Some(entries: DynamicValue.Sequence) if entries.elements.nonEmpty =>
        body.updated(Keys.tools, sequence(entries.elements.iterator.map(tool).toVector))
      case _ => body

  /** One tool in the flat form: the keys of `function` move up, after the other keys of the tool. A function tool with
    * no `description`, or a null one, has no `description` key.
    */
  private def tool(entry: DynamicValue): DynamicValue =
    asRecord(entry) match
      case Some(rec) =>
        val flat = recordGet(rec, WireKeys.function).flatMap(asRecord) match
          case Some(function) =>
            val others = DynamicValues.recordFilterKeys(rec, key => key != WireKeys.`type` && key != WireKeys.function)
            val head   = record(WireKeys.`type` -> string(Kinds.function))
            DynamicValues.mergeRecords(DynamicValues.mergeRecords(head, others), function)
          case None => rec
        if isKind(flat, Kinds.function) && present(flat, Keys.description).isEmpty then without(flat, Keys.description)
        else flat
      case None => entry

  private def withToolChoice(body: DynamicValue.Record): DynamicValue.Record =
    val name = recordGet(body, Keys.toolChoice)
      .flatMap(choice => recordGet(choice, WireKeys.function))
      .flatMap(function => recordGet(function, WireKeys.name))
    name match
      case Some(value) =>
        body.updated(Keys.toolChoice, record(WireKeys.`type` -> string(Kinds.function), WireKeys.name -> value))
      case None => body

  private def withTextFormat(body: DynamicValue.Record): DynamicValue.Record =
    val rest = without(body, Keys.responseFormat)
    present(body, Keys.responseFormat) match
      case Some(format) =>
        val text = present(rest, WireKeys.text).flatMap(asRecord).getOrElse(DynamicValue.Record.empty)
        rest.updated(WireKeys.text, text.updated(Keys.format, flatFormat(format)))
      case None => rest

  /** A `json_schema` format with the keys of its `json_schema` object one level up. Each other format stays. */
  private def flatFormat(format: DynamicValue): DynamicValue =
    asRecord(format) match
      case Some(rec) if isKind(rec, Kinds.jsonSchema) =>
        recordGet(rec, Keys.jsonSchema).flatMap(asRecord) match
          case Some(schema) => DynamicValues.mergeRecords(without(rec, Keys.jsonSchema), schema)
          case None         => rec
      case _ => format

  /** `body` with no `stop` when `stop` is empty (upstream `if not data.get("stop")`). A null `stop` goes with the other
    * null fields.
    */
  private def withoutEmptyStop(body: DynamicValue.Record): DynamicValue.Record =
    if recordGet(body, Keys.stop).forall(filled) then body else without(body, Keys.stop)

  // ── The outputs ─────────────────────────────────────────────────────────────────────────────────────────────

  /** The tool call of a `function_call` item. A text of arguments that is not JSON gives an empty record (upstream
    * `responses_outputs`: `json.loads` fails, and the arguments are `{}`). The chat path keeps its own rule for such a
    * text.
    */
  private def toolCall(item: DynamicValue.Record): ToolCall =
    ToolCall(
      name = stringOf(item, WireKeys.name).getOrElse(""),
      args = ToolCallAssembler.decodeArguments(
        recordGet(item, WireKeys.arguments),
        notJson = _ => DynamicValue.Record.empty
      ),
      id = stringOf(item, WireKeys.callId).filter(_.nonEmpty)
    )

  /** The texts of the parts of a `reasoning` item: the `content` parts, else the `summary` parts. An empty text is
    * skipped.
    */
  private def reasoningTexts(item: DynamicValue.Record): Vector[String] =
    val content = sequenceAt(item, WireKeys.content)
    val parts   = if content.nonEmpty then content else sequenceAt(item, Keys.summary)
    parts.flatMap(part => recordGet(part, WireKeys.text)).flatMap(DynamicValues.asString).filter(_.nonEmpty)

  private def citation(annotation: DynamicValue.Record): DynamicValue.Record =
    val described = Set(Keys.citedText, WireKeys.text, Keys.supportedText, Keys.documentTitle, Keys.title, Keys.url)
    val metadata  = DynamicValues.recordFilterKeys(annotation, key => !described.contains(key))
    val entries   = Vector(
      WireKeys.`type`   -> Some(string(Kinds.citation)),
      WireKeys.metadata ->
        Some(recordFromEntries(DynamicValues.recordEntries(metadata).filterNot((_, value) => isNull(value)))),
      WireKeys.text -> firstFilled(annotation, Keys.citedText, WireKeys.text, Keys.supportedText),
      Keys.title    -> firstFilled(annotation, Keys.documentTitle, Keys.title),
      Keys.url      -> present(annotation, Keys.url)
    )
    recordFromEntries(entries.flatMap((key, value) => value.map(key -> _)))

  /** The value of the first key whose value is [[filled]], else the value of the last key. A null value gives `None`.
    * This is the rule of Python's `a or b`.
    */
  private def firstFilled(rec: DynamicValue.Record, keys: String*): Option[DynamicValue] =
    keys.iterator.flatMap(recordGet(rec, _)).find(filled)
      .orElse(keys.lastOption.flatMap(recordGet(rec, _)))
      .filterNot(isNull)

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private def without(rec: DynamicValue.Record, keys: String*): DynamicValue.Record =
    DynamicValues.recordFilterKeys(rec, key => !keys.contains(key))

  /** The value of `key` when it is present and not null. */
  private def present(rec: DynamicValue.Record, key: String): Option[DynamicValue] =
    recordGet(rec, key).filterNot(isNull)

  /** Whether a value counts as true in Python: not null, not `false`, not an empty string, array, or object. */
  private def filled(value: DynamicValue): Boolean =
    value match
      case _: DynamicValue.Null.type                         => false
      case DynamicValue.Primitive(PrimitiveValue.String(s))  => s.nonEmpty
      case DynamicValue.Primitive(PrimitiveValue.Boolean(b)) => b
      case items: DynamicValue.Sequence                      => items.elements.nonEmpty
      case rec: DynamicValue.Record                          => rec.fields.nonEmpty
      case _                                                 => true

  private def stringOf(rec: DynamicValue.Record, key: String): Option[String] =
    recordGet(rec, key).flatMap(DynamicValues.asString)

  /** The string at `key` when it is not empty. */
  private def nonEmpty(rec: DynamicValue.Record, key: String): Option[String] =
    stringOf(rec, key).filter(_.nonEmpty)

  /** The id of a tool call in a stream event or item: the `call_id`, else the `id`. */
  private def idOf(rec: DynamicValue.Record): Option[String] =
    nonEmpty(rec, WireKeys.callId).orElse(nonEmpty(rec, WireKeys.id))

  private def toolCallChunk(delta: LmToolCallDelta): LmChunk = LmChunk(toolCalls = Vector(delta))

  private def isKind(rec: DynamicValue.Record, kind: String): Boolean =
    stringOf(rec, WireKeys.`type`).contains(kind)

  private def isTextBlock(rec: DynamicValue.Record): Boolean =
    stringOf(rec, WireKeys.`type`).exists(Kinds.textBlocks.contains)
