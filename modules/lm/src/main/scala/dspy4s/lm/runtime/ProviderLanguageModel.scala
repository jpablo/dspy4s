package dspy4s.lm.runtime

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.updated
import dspy4s.lm.contracts.ContentPart
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.LmUsage
import dspy4s.lm.contracts.Message
import dspy4s.core.contracts.ToolCall
import dspy4s.lm.providers.DynamicJson
import dspy4s.lm.providers.OpenAiUsage
import dspy4s.lm.providers.WireKeys
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

final case class ProviderLanguageModel(
    id: String,
    mode: LmMode,
    invoke: DynamicValue => Either[DspyError, DynamicValue],
    defaultOptions: DynamicValue.Record = DynamicValue.Record.empty
) extends LanguageModel:
  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    val effectiveMode = request.mode
    val normalizedRequest = ProviderRequestNormalizer.normalize(request, defaultOptions = defaultOptions)
    invoke(normalizedRequest).flatMap(raw => ProviderResponseParser.parse(raw, effectiveMode))

object ProviderRequestNormalizer:
  private def str(s: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(s))

  /** Build the provider request payload as a `DynamicValue.Record`: the merged option bag (defaults overlaid by
    * the request's options), plus model / mode / request_id and the encoded messages or prompt. */
  def normalize(
      request: LmRequest,
      defaultOptions: DynamicValue.Record = DynamicValue.Record.empty
  ): DynamicValue.Record =
    var rec = DynamicValues.mergeRecords(defaultOptions, request.options)
    rec = rec.updated(WireKeys.model, str(request.model))
    rec = rec.updated(WireKeys.mode, str(request.mode.toString.toLowerCase))
    request.requestId.foreach(id => rec = rec.updated(WireKeys.requestId, str(id)))
    request.mode match
      case LmMode.Chat | LmMode.Responses =>
        rec.updated(WireKeys.messages, DynamicValue.Sequence(Chunk.from(request.messages.map(encodeMessage))))
      case LmMode.Text =>
        val prompt = request.messages.map(messageText).mkString("\n").trim
        rec.updated(WireKeys.prompt, str(prompt))

  private def encodeMessage(message: Message): DynamicValue =
    val role = message.role.toString.toLowerCase
    val content: DynamicValue =
      if message.parts.nonEmpty then DynamicValue.Sequence(Chunk.from(message.parts.map(encodePart)))
      else str(message.text.getOrElse(""))
    DynamicValue.Record(Chunk(WireKeys.role -> str(role), WireKeys.content -> content))

  private def encodePart(part: ContentPart): DynamicValue =
    val base = Vector(WireKeys.`type` -> str(part.kind), WireKeys.text -> str(part.payload))
    val fields =
      if part.metadata.nonEmpty then
        base :+ (WireKeys.metadata -> DynamicValue.Record(
          Chunk.from(part.metadata.iterator.map((k, v) => k -> str(v)).toSeq)
        ))
      else base
    DynamicValue.Record(Chunk.from(fields))

  private def messageText(message: Message): String =
    message.text.getOrElse(message.parts.map(_.payload).mkString("\n"))

object ProviderResponseParser:
  import DynamicJson.{field, asRecord, asString}

  def parse(raw: DynamicValue, mode: LmMode): Either[DspyError, LmResponse] =
    val outputs = mode match
      case LmMode.Chat      => parseChatOutputs(raw)
      case LmMode.Text      => parseTextOutputs(raw)
      case LmMode.Responses => parseResponsesOutputs(raw).orElse(parseChatOutputs(raw))

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
        val rec = asRecord(choice)
        val textFromMessage = rec.flatMap(r => field(r, WireKeys.message)).flatMap(extractText)
        val textFromChoice  = rec.flatMap(r => field(r, WireKeys.text)).flatMap(asString)
        val text = textFromMessage.orElse(textFromChoice).map(_.trim).filter(_.nonEmpty)
        val toolCalls = rec.flatMap(r => field(r, WireKeys.message)).flatMap(asRecord).map(parseToolCalls).getOrElse(Vector.empty)
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

  private def parseResponsesOutputs(raw: DynamicValue): Either[DspyError, Vector[LmOutput]] =
    seqField(raw, WireKeys.output).map { output =>
      output.flatMap { item =>
        val text      = extractText(item).map(_.trim).filter(_.nonEmpty)
        val toolCalls = asRecord(item).map(parseToolCalls).getOrElse(Vector.empty)
        // Same EITHER-text-OR-tool-calls rule as the chat path: a function-call output item carries no text.
        Option.when(text.nonEmpty || toolCalls.nonEmpty) {
          val metadata = asRecord(item).getOrElse(DynamicValue.Record.empty)
          LmOutput(text = text.getOrElse(""), toolCalls = toolCalls, metadata = metadata)
        }
      }
    }

  private def parseUsage(raw: DynamicValue): Option[LmUsage] =
    field(raw, WireKeys.usage).flatMap(asRecord).map(usage => OpenAiUsage.fromDynamic(usage).toLmUsage)

  private def parseToolCalls(message: DynamicValue.Record): Vector[ToolCall] =
    seqField(message, WireKeys.toolCalls) match
      case Right(entries) =>
        entries.flatMap { call =>
          asRecord(call).flatMap { rec =>
            val functionRec = field(rec, WireKeys.function).flatMap(asRecord).getOrElse(rec)
            field(functionRec, WireKeys.name).flatMap(asString).map { name =>
              ToolCall(name = name, args = parseArgs(field(functionRec, WireKeys.arguments)))
            }
          }
        }
      case Left(_) => Vector.empty

  private def parseArgs(raw: Option[DynamicValue]): DynamicValue.Record =
    raw.flatMap(asRecord) match
      case Some(rec) => rec
      case None =>
        raw.flatMap(asString) match
          case Some(value) if value.trim.nonEmpty => DynamicValues.recordFromEntries(Seq(WireKeys.input := value))
          case _ =>
            raw match
              case Some(other) => DynamicValues.recordFromEntries(Seq(WireKeys.value -> other))
              case None        => DynamicValue.Record.empty

  private def extractText(node: DynamicValue): Option[String] =
    field(node, WireKeys.content) match
      case Some(content) =>
        asString(content) match
          case Some(text) => Some(text)
          case None =>
            val fromParts = DynamicJson.asSequence(content).iterator
              .flatMap(item => asRecord(item).flatMap(r => field(r, WireKeys.text)).flatMap(asString).map(_.trim))
              .mkString("\n").trim
            Option
              .when(fromParts.nonEmpty)(fromParts)
              .orElse(field(node, WireKeys.text).flatMap(asString).map(_.trim).filter(_.nonEmpty))
      case None =>
        field(node, WireKeys.text).flatMap(asString).map(_.trim).filter(_.nonEmpty)

  /** A response field treated as an array: absent or null -> empty; a sequence -> its elements; anything else
    * -> a parse error (mirrors the old `asVector`). */
  private def seqField(value: DynamicValue, name: String): Either[DspyError, Vector[DynamicValue]] =
    field(value, name) match
      case None                              => Right(Vector.empty)
      case Some(_: DynamicValue.Null.type)   => Right(Vector.empty)
      case Some(seq: DynamicValue.Sequence)  => Right(seq.elements.iterator.toVector)
      case Some(other) =>
        Left(ParseError("lm", s"Expected array-like response field '$name', found: ${other.getClass.getSimpleName}"))

