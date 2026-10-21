package dspy4s.lm.providers

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.ToolCall
import dspy4s.core.contracts.updated
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmToolCallDelta
import dspy4s.lm.runtime.ToolCallAssembler
import zio.blocks.chunk.Chunk
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

/** The wire shapes of the OpenAI Responses API (upstream `chat_to_responses` in `dspy/clients/legacy_requests.py`,
  * `responses_outputs` in `dspy/clients/legacy_outputs.py`, and the stream events of lm15 `OpenAILM` in
  * `dspy/_vendor/lm15/providers/openai.py`).
  */
private[lm] object ResponsesWire:

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
    final val detail              = "detail"
    final val stop                = "stop"
    final val annotations         = "annotations"
    final val citedText           = "cited_text"
    final val supportedText       = "supported_text"
    final val documentTitle       = "document_title"
    final val title               = "title"
    final val reasoningContent    = "reasoning_content"
    final val citations           = "citations"
    final val outputIndex         = "output_index"
    final val item                = "item"
    final val response            = "response"
    final val annotation          = "annotation"

    /** The options that the Responses body does not send as null (upstream: the former config serializer omitted them).
      */
    val nullableOptions: Vector[String] = Vector("temperature", "top_p", "n", "logprobs", reasoning, toolChoice)

  /** The values of the `type` and `role` keys that the translation reads or writes. */
  private object Kinds:
    final val assistant          = "assistant"
    final val tool               = "tool"
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

    /** The block types of a tool message whose text goes into the output of the call. */
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

  /** The Responses body for the chat body `chat` (the payload of `ProviderRequestNormalizer.normalize`, with no
    * internal key). The rules of upstream `chat_to_responses`:
    *
    *   - A body that has `input` already loses `messages` and is otherwise unchanged.
    *   - `messages` becomes `input`, one item or more for each message, in order:
    *     - A `tool` message gives `{"type": "function_call_output", "output": <the text>, "call_id": <tool_call_id>}`.
    *       The text of a list content is the joined `text` of its text blocks. `call_id` is absent when the message has
    *       no `tool_call_id`.
    *     - Each other message gives `{"role", "content": <blocks>, "name"?}`, except an `assistant` message that has
    *       tool calls and no blocks. The blocks of a string content are one `{"type": <t>, "text"}` block, where `<t>`
    *       is `output_text` for an `assistant` message and `input_text` for each other role. A null content gives no
    *       block. In a list content, a `text`, `input_text`, or `output_text` block becomes a `<t>` block; an
    *       `image_url` block becomes `{"type": "input_image", "image_url": <url>}` (the url is the `url` of an object,
    *       else the value itself, else the `text` of the port's part; the `detail` of an object that is not null stays
    *       too); each other block stays.
    *     - After the item of an `assistant` message, each of its tool calls gives
    *       `{"type": "function_call", "name", "arguments", "call_id"?}` (`arguments` is the JSON text; `call_id` is the
    *       `call_id`, else the `id` of the call).
    *   - `max_completion_tokens` becomes `max_tokens` when `max_tokens` is absent. A `max_tokens` that is not null
    *     becomes `max_output_tokens`; a null `max_tokens` is removed.
    *   - `reasoning_effort` becomes `reasoning: {"effort": <value>, "summary": "auto"}`, unless `reasoning` is set.
    *   - A tool `{"type": "function", "function": {...}}` becomes `{"type": "function", ...<the keys of function>}`. A
    *     function tool with a null `description` loses the key. The other keys of the tool stay.
    *   - A `tool_choice` `{"type": "function", "function": {"name": n}}` becomes `{"type": "function", "name": n}`.
    *   - `response_format` moves to `text.format`. A `{"type": "json_schema", "json_schema": {...}}` format is
    *     flattened to `{"type": "json_schema", ...<the keys of json_schema>}`, the form that the Responses API takes
    *     (upstream builds this form from a pydantic model; the port sends a record).
    *   - A null `temperature`, `top_p`, `n`, `logprobs`, `reasoning`, or `tool_choice` is removed, and so is an empty
    *     or null `stop`.
    */
  def request(chat: DynamicValue.Record): DynamicValue.Record =
    if DynamicValues.recordGet(chat, Keys.input).isDefined then without(chat, WireKeys.messages)
    else
      val messages =
        DynamicValues.recordGet(chat, WireKeys.messages).map(DynamicJson.asSequence).getOrElse(Vector.empty)
      val body  = without(chat, WireKeys.messages).updated(Keys.input, sequence(messages.flatMap(inputItems)))
      val steps = Vector(withTokenLimit, withReasoning, withTools, withToolChoice, withTextFormat, withoutEmptyOptions)
      steps.foldLeft(body)((current, step) => step(current))

  /** The outputs of a Responses body (upstream `responses_outputs`): one output for the whole response.
    *
    *   - `text` is the joined text of the `text`, `output_text`, and `input_text` parts of each `message` item.
    *   - Each `function_call` item gives a tool call with its `name`, its `arguments` (a JSON text, decoded as for the
    *     chat path, except that a text that is not JSON gives an empty record), and the id `call_id`.
    *   - The texts of the `content` (else the `summary`) parts of each `reasoning` item, joined, go to the metadata key
    *     `reasoning_content`.
    *   - Each annotation of a message part gives a citation in the metadata key `citations`:
    *     `{"type": "citation", "text", "title", "url", "metadata"}`, with no key for a null value (`text` is
    *     `cited_text`, else `text`, else `supported_text`; `title` is `document_title`, else `title`; `metadata` has
    *     the other keys of the annotation that are not null).
    *
    * The result is an empty vector when the response has no text (or only white space) and no tool call. A body with no
    * `output` array is a `ParseError`.
    */
  def outputs(raw: DynamicValue): Either[DspyError, Vector[LmOutput]] =
    DynamicJson.field(raw, WireKeys.output) match
      case Some(items: DynamicValue.Sequence) =>
        val records   = items.elements.iterator.flatMap(DynamicJson.asRecord).toVector
        val messages  = records.filter(isKind(_, Kinds.message))
        val parts     = messages.flatMap(item => listOf(item, WireKeys.content)).flatMap(DynamicJson.asRecord)
        val text      = parts.filter(isTextBlock).flatMap(part => stringOf(part, WireKeys.text)).mkString
        val toolCalls = records.filter(isKind(_, Kinds.functionCall)).map(toolCall)
        val reasoning = records.filter(isKind(_, Kinds.reasoning)).flatMap(reasoningTexts).mkString
        val citations =
          parts.flatMap(part => listOf(part, Keys.annotations)).flatMap(DynamicJson.asRecord).map(citation)
        Right(
          if text.trim.isEmpty && toolCalls.isEmpty then Vector.empty
          else Vector(LmOutput(text = text, toolCalls = toolCalls, metadata = outputMetadata(reasoning, citations)))
        )
      case _ => Left(ParseError("lm", "The Responses body has no output array"))

  /** The metadata of an output with the reasoning text `reasoning` and the citations `citations`: the key
    * `reasoning_content` when the text is not empty, and the key `citations` when there is a citation. The plain path
    * ([[outputs]]) and the assembly of a stream ([[dspy4s.lm.runtime.StreamAccumulator]]) use it, so the two give the
    * same metadata.
    */
  def outputMetadata(reasoning: String, citations: Vector[DynamicValue.Record]): DynamicValue.Record =
    DynamicValues.recordFromEntries(
      Option.when(reasoning.nonEmpty)(Keys.reasoningContent -> string(reasoning)).toVector ++
        Option.when(citations.nonEmpty)(Keys.citations -> sequence(citations))
    )

  /** The reasoning text of an output metadata (the inverse of [[outputMetadata]]): the `reasoning_content` string, else
    * an empty text.
    */
  def reasoningOf(metadata: DynamicValue.Record): String =
    stringOf(metadata, Keys.reasoningContent).getOrElse("")

  /** The citations of an output metadata (the inverse of [[outputMetadata]]): the objects of the `citations` array. */
  def citationsOf(metadata: DynamicValue.Record): Vector[DynamicValue.Record] =
    listOf(metadata, Keys.citations).flatMap(DynamicJson.asRecord)

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
    val index = DynamicValues.recordGet(event, Keys.outputIndex).flatMap(DynamicJson.asLong).fold(0)(_.toInt)
    def delta = stringOf(event, WireKeys.delta).getOrElse("")
    stringOf(event, WireKeys.`type`).getOrElse("") match
      case Events.textDelta | Events.refusalDelta                   => Right(Some(LmChunk(text = delta)))
      case Events.reasoningSummaryDelta | Events.reasoningTextDelta => Right(Some(LmChunk(reasoning = delta)))
      case Events.annotationAdded                                   =>
        val annotation = DynamicValues.recordGet(event, Keys.annotation).flatMap(DynamicJson.asRecord)
        Right(annotation.map(found => LmChunk(citations = Vector(citation(found)))))
      case Events.itemAdded =>
        val call =
          DynamicValues.recordGet(event, Keys.item).flatMap(DynamicJson.asRecord).filter(isKind(_, Kinds.functionCall))
        Right(call.map { item =>
          val arguments = stringOf(item, WireKeys.arguments).getOrElse("")
          toolCallChunk(LmToolCallDelta(index, idOf(item), nonEmpty(item, WireKeys.name), Some(arguments)))
        })
      case Events.argumentsDelta =>
        Right(Some(toolCallChunk(LmToolCallDelta(index, idOf(event), nonEmpty(event, WireKeys.name), Some(delta)))))
      case Events.completed =>
        val response = DynamicValues.recordGet(event, Keys.response).flatMap(DynamicJson.asRecord)
        val usage    = response.flatMap(DynamicJson.field(_, WireKeys.usage)).flatMap(DynamicJson.asRecord)
        val hasTool  = response.toVector.flatMap(listOf(_, WireKeys.output)).flatMap(DynamicJson.asRecord)
          .exists(isKind(_, Kinds.functionCall))
        Right(Some(LmChunk(
          finishReason = Some(if hasTool then Finish.toolCall else Finish.stop),
          usage = usage.map(OpenAiUsage.fromDynamic(_).toLmUsage)
        )))
      case Events.error | Events.responseError => Left(OpenAiErrors.fromResponsesStreamError(event, model))
      case _                                   => Right(None)

  // ── The request ─────────────────────────────────────────────────────────────────────────────────────────────

  /** The input items of one chat message, in order. A message that is not an object gives no item. */
  private def inputItems(message: DynamicValue): Vector[DynamicValue] =
    DynamicJson.asRecord(message).toVector.flatMap { rec =>
      val role    = DynamicValues.recordGet(rec, WireKeys.role)
      val content = DynamicValues.recordGet(rec, WireKeys.content)
      val kind    = role.flatMap(DynamicJson.asString)
      if kind.contains(Kinds.tool) then Vector(functionCallOutput(rec, content))
      else
        val assistant = kind.contains(Kinds.assistant)
        val calls     = listOf(rec, WireKeys.toolCalls)
        val blocks    = contentBlocks(content, if assistant then Kinds.outputText else Kinds.inputText)
        val item      = Option.when(blocks.nonEmpty || !assistant || calls.isEmpty)(
          record(
            Vector(WireKeys.role -> role.getOrElse(DynamicValue.Null), WireKeys.content -> sequence(blocks)) ++
              present(rec, WireKeys.name).map(WireKeys.name -> _)
          )
        )
        item.toVector ++ (if assistant then calls.flatMap(functionCall) else Vector.empty)
    }

  /** The `function_call_output` item of a tool message. The output of a list content is the joined text of its text
    * blocks. A null content or no content gives an empty output.
    */
  private def functionCallOutput(message: DynamicValue.Record, content: Option[DynamicValue]): DynamicValue =
    val output = content match
      case Some(blocks: DynamicValue.Sequence) =>
        string(blocks.elements.iterator.flatMap(DynamicJson.asRecord).filter(isToolTextBlock).map { block =>
          stringOf(block, WireKeys.text).getOrElse("")
        }.mkString)
      case Some(value) if filled(value) => value
      case _                            => string("")
    record(
      Vector(WireKeys.`type` -> string(Kinds.functionCallOutput), WireKeys.output -> output) ++
        present(message, WireKeys.toolCallId).map(WireKeys.callId -> _)
    )

  /** The blocks of a content (upstream `_content`). `textType` is the type of a text block. */
  private def contentBlocks(content: Option[DynamicValue], textType: String): Vector[DynamicValue] =
    content match
      case Some(DynamicValue.Primitive(PrimitiveValue.String(text))) => Vector(textBlock(textType, string(text)))
      case Some(blocks: DynamicValue.Sequence)                       =>
        blocks.elements.iterator.map(block => contentBlock(block, textType)).toVector
      case _ => Vector.empty

  private def contentBlock(block: DynamicValue, textType: String): DynamicValue =
    DynamicJson.asRecord(block) match
      case Some(rec) if isTextBlock(rec) =>
        textBlock(textType, DynamicValues.recordGet(rec, WireKeys.text).getOrElse(string("")))
      case Some(rec) if isKind(rec, Kinds.imageUrl) => inputImage(rec)
      case _                                        => block

  private def textBlock(textType: String, text: DynamicValue): DynamicValue =
    record(Vector(WireKeys.`type` -> string(textType), WireKeys.text -> text))

  /** The `input_image` block of an `image_url` block. The port writes the url of its part under `text`. */
  private def inputImage(block: DynamicValue.Record): DynamicValue =
    val image         = present(block, Keys.imageUrl).orElse(DynamicValues.recordGet(block, WireKeys.text))
    val (url, detail) = image.flatMap(DynamicJson.asRecord) match
      case Some(rec) => (DynamicValues.recordGet(rec, Keys.url).getOrElse(string("")), present(rec, Keys.detail))
      case None      => (image.getOrElse(string("")), None)
    record(
      Vector(WireKeys.`type` -> string(Kinds.inputImage), Keys.imageUrl -> url) ++ detail.map(Keys.detail -> _)
    )

  /** The `function_call` item of one chat tool call. A tool call that is not an object gives no item. */
  private def functionCall(call: DynamicValue): Option[DynamicValue] =
    DynamicJson.asRecord(call).map { rec =>
      val function = DynamicValues.recordGet(rec, WireKeys.function).flatMap(DynamicJson.asRecord).filter(filled)
        .getOrElse(rec)
      val callId = DynamicValues.recordGet(rec, WireKeys.callId).filter(filled)
        .orElse(present(rec, WireKeys.id))
      record(
        Vector(
          WireKeys.`type`    -> string(Kinds.functionCall),
          WireKeys.name      -> DynamicValues.recordGet(function, WireKeys.name).getOrElse(string("")),
          WireKeys.arguments -> DynamicValues.recordGet(function, WireKeys.arguments).getOrElse(string("{}"))
        ) ++ callId.map(WireKeys.callId -> _)
      )
    }

  private def withTokenLimit(body: DynamicValue.Record): DynamicValue.Record =
    val moved =
      (DynamicValues.recordGet(body, Keys.maxCompletionTokens), DynamicValues.recordGet(body, Keys.maxTokens)) match
        case (Some(limit), None) => without(body, Keys.maxCompletionTokens).updated(Keys.maxTokens, limit)
        case _                   => body
    present(moved, Keys.maxTokens) match
      case Some(limit) => without(moved, Keys.maxTokens).updated(Keys.maxOutputTokens, limit)
      case None        => without(moved, Keys.maxTokens)

  private def withReasoning(body: DynamicValue.Record): DynamicValue.Record =
    DynamicValues.recordGet(body, Keys.reasoningEffort) match
      case Some(effort) =>
        val rest = without(body, Keys.reasoningEffort)
        if present(rest, Keys.reasoning).isDefined then rest
        else rest.updated(Keys.reasoning, record(Vector(Keys.effort -> effort, Keys.summary -> string("auto"))))
      case None => body

  private def withTools(body: DynamicValue.Record): DynamicValue.Record =
    DynamicValues.recordGet(body, Keys.tools) match
      case Some(entries: DynamicValue.Sequence) if entries.elements.nonEmpty =>
        body.updated(Keys.tools, sequence(entries.elements.iterator.map(tool).toVector))
      case _ => body

  /** One tool in the flat form: the keys of `function` move up, after the other keys of the tool. A function tool with
    * no `description`, or a null one, has no `description` key.
    */
  private def tool(entry: DynamicValue): DynamicValue =
    DynamicJson.asRecord(entry) match
      case Some(rec) =>
        val flat = DynamicValues.recordGet(rec, WireKeys.function).flatMap(DynamicJson.asRecord) match
          case Some(function) =>
            val others = DynamicValues.recordFilterKeys(rec, key => key != WireKeys.`type` && key != WireKeys.function)
            val head   = record(Vector(WireKeys.`type` -> string(Kinds.function)))
            DynamicValues.mergeRecords(DynamicValues.mergeRecords(head, others), function)
          case None => rec
        if isKind(flat, Kinds.function) && present(flat, Keys.description).isEmpty then without(flat, Keys.description)
        else flat
      case None => entry

  private def withToolChoice(body: DynamicValue.Record): DynamicValue.Record =
    val name = DynamicValues.recordGet(body, Keys.toolChoice)
      .flatMap(choice => DynamicJson.field(choice, WireKeys.function))
      .flatMap(function => DynamicJson.field(function, WireKeys.name))
    name match
      case Some(value) =>
        body.updated(Keys.toolChoice, record(Vector(WireKeys.`type` -> string(Kinds.function), WireKeys.name -> value)))
      case None => body

  private def withTextFormat(body: DynamicValue.Record): DynamicValue.Record =
    val rest = without(body, Keys.responseFormat)
    present(body, Keys.responseFormat) match
      case Some(format) =>
        val text = present(rest, WireKeys.text).flatMap(DynamicJson.asRecord).getOrElse(DynamicValue.Record.empty)
        rest.updated(WireKeys.text, text.updated(Keys.format, flatFormat(format)))
      case None => rest

  /** A `json_schema` format with the keys of its `json_schema` object one level up. Each other format stays. */
  private def flatFormat(format: DynamicValue): DynamicValue =
    DynamicJson.asRecord(format) match
      case Some(rec) if isKind(rec, Kinds.jsonSchema) =>
        DynamicValues.recordGet(rec, Keys.jsonSchema).flatMap(DynamicJson.asRecord) match
          case Some(schema) => DynamicValues.mergeRecords(without(rec, Keys.jsonSchema), schema)
          case None         => rec
      case _ => format

  private def withoutEmptyOptions(body: DynamicValue.Record): DynamicValue.Record =
    val nulls   = Keys.nullableOptions.filter(key => DynamicValues.recordGet(body, key).exists(isNull))
    val cleared = without(body, nulls*)
    if DynamicValues.recordGet(cleared, Keys.stop).exists(filled) then cleared else without(cleared, Keys.stop)

  // ── The outputs ─────────────────────────────────────────────────────────────────────────────────────────────

  /** The tool call of a `function_call` item. A text of arguments that is not JSON gives an empty record (upstream
    * `responses_outputs`: `json.loads` fails, and the arguments are `{}`). The chat path keeps its own rule for such a
    * text.
    */
  private def toolCall(item: DynamicValue.Record): ToolCall =
    ToolCall(
      name = stringOf(item, WireKeys.name).getOrElse(""),
      args = ToolCallAssembler.decodeArguments(
        DynamicValues.recordGet(item, WireKeys.arguments),
        notJson = _ => DynamicValue.Record.empty
      ),
      id = stringOf(item, WireKeys.callId).filter(_.nonEmpty)
    )

  /** The texts of the parts of a `reasoning` item: the `content` parts, else the `summary` parts. An empty text is
    * skipped.
    */
  private def reasoningTexts(item: DynamicValue.Record): Vector[String] =
    val content = listOf(item, WireKeys.content)
    val parts   = if content.nonEmpty then content else listOf(item, Keys.summary)
    parts.flatMap(part => DynamicJson.field(part, WireKeys.text)).flatMap(DynamicJson.asString).filter(_.nonEmpty)

  private def citation(annotation: DynamicValue.Record): DynamicValue.Record =
    val described = Set(Keys.citedText, WireKeys.text, Keys.supportedText, Keys.documentTitle, Keys.title, Keys.url)
    val metadata  = DynamicValues.recordFilterKeys(annotation, key => !described.contains(key))
    val entries   = Vector(
      WireKeys.`type`   -> Some(string(Kinds.citation)),
      WireKeys.metadata -> Some(record(DynamicValues.recordEntries(metadata).filterNot((_, value) => isNull(value)))),
      WireKeys.text     -> firstFilled(annotation, Keys.citedText, WireKeys.text, Keys.supportedText),
      Keys.title        -> firstFilled(annotation, Keys.documentTitle, Keys.title),
      Keys.url          -> present(annotation, Keys.url)
    )
    record(entries.flatMap((key, value) => value.map(key -> _)))

  /** The value of the first key whose value is [[filled]], else the value of the last key. A null value gives `None`.
    * This is the rule of Python's `a or b`.
    */
  private def firstFilled(rec: DynamicValue.Record, keys: String*): Option[DynamicValue] =
    keys.iterator.flatMap(DynamicValues.recordGet(rec, _)).find(filled)
      .orElse(keys.lastOption.flatMap(DynamicValues.recordGet(rec, _)))
      .filterNot(isNull)

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private def string(text: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(text))

  private def sequence(values: Vector[DynamicValue]): DynamicValue = DynamicValue.Sequence(Chunk.from(values))

  private def record(entries: Vector[(String, DynamicValue)]): DynamicValue.Record =
    DynamicValue.Record(Chunk.from(entries))

  private def without(rec: DynamicValue.Record, keys: String*): DynamicValue.Record =
    DynamicValues.recordFilterKeys(rec, key => !keys.contains(key))

  private def isNull(value: DynamicValue): Boolean =
    value match
      case _: DynamicValue.Null.type => true
      case _                         => false

  /** The value of `key` when it is present and not null. */
  private def present(rec: DynamicValue.Record, key: String): Option[DynamicValue] =
    DynamicValues.recordGet(rec, key).filterNot(isNull)

  /** Whether a value counts as true in Python: not null, not `false`, not an empty string, array, or object. */
  private def filled(value: DynamicValue): Boolean =
    value match
      case _: DynamicValue.Null.type                         => false
      case DynamicValue.Primitive(PrimitiveValue.String(s))  => s.nonEmpty
      case DynamicValue.Primitive(PrimitiveValue.Boolean(b)) => b
      case items: DynamicValue.Sequence                      => items.elements.nonEmpty
      case rec: DynamicValue.Record                          => rec.fields.nonEmpty
      case _                                                 => true

  /** The elements of the array at `key`. An absent key, a null, or a value that is not an array gives no element. */
  private def listOf(value: DynamicValue, key: String): Vector[DynamicValue] =
    DynamicJson.field(value, key).map(DynamicJson.asSequence).getOrElse(Vector.empty)

  private def stringOf(rec: DynamicValue.Record, key: String): Option[String] =
    DynamicValues.recordGet(rec, key).flatMap(DynamicJson.asString)

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

  private def isToolTextBlock(rec: DynamicValue.Record): Boolean =
    stringOf(rec, WireKeys.`type`).exists(Kinds.toolTextBlocks.contains)
