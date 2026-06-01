package dspy4s.lm.runtime

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.:=
import dspy4s.lm.contracts.ContentPart
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.LmUsage
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.ToolCall
import dspy4s.lm.providers.DynamicJson
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
    var rec = mergeRecords(defaultOptions, request.options)
    rec = DynamicValues.recordUpdated(rec, "model", str(request.model))
    rec = DynamicValues.recordUpdated(rec, "mode", str(request.mode.toString.toLowerCase))
    request.requestId.foreach(id => rec = DynamicValues.recordUpdated(rec, "request_id", str(id)))
    request.mode match
      case LmMode.Chat | LmMode.Responses =>
        DynamicValues.recordUpdated(
          rec, "messages", DynamicValue.Sequence(Chunk.from(request.messages.map(encodeMessage)))
        )
      case LmMode.Text =>
        val prompt = request.messages.map(messageText).mkString("\n").trim
        DynamicValues.recordUpdated(rec, "prompt", str(prompt))

  private def mergeRecords(base: DynamicValue.Record, overlay: DynamicValue.Record): DynamicValue.Record =
    overlay.fields.iterator.foldLeft(base)((acc, kv) => DynamicValues.recordUpdated(acc, kv._1, kv._2))

  private def encodeMessage(message: Message): DynamicValue =
    val role = message.role.toString.toLowerCase
    val content: DynamicValue =
      if message.parts.nonEmpty then DynamicValue.Sequence(Chunk.from(message.parts.map(encodePart)))
      else str(message.text.getOrElse(""))
    DynamicValue.Record(Chunk("role" -> str(role), "content" -> content))

  private def encodePart(part: ContentPart): DynamicValue =
    val base = Vector("type" -> str(part.kind), "text" -> str(part.payload))
    val fields =
      if part.metadata.nonEmpty then
        base :+ ("metadata" -> DynamicValue.Record(
          Chunk.from(part.metadata.iterator.map((k, v) => k -> str(v)).toSeq)
        ))
      else base
    DynamicValue.Record(Chunk.from(fields))

  private def messageText(message: Message): String =
    message.text.getOrElse(message.parts.map(_.payload).mkString("\n"))

object ProviderResponseParser:
  import DynamicJson.{field, asRecord, asString, asLong}

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
            modelName = field(raw, "model").flatMap(asString),
            cacheHit = false
          )
        )
    }

  private def parseChatOutputs(raw: DynamicValue): Either[DspyError, Vector[LmOutput]] =
    seqField(raw, "choices").map { choices =>
      choices.flatMap { choice =>
        val rec = asRecord(choice)
        val textFromMessage = rec.flatMap(r => field(r, "message")).flatMap(extractText)
        val textFromChoice  = rec.flatMap(r => field(r, "text")).flatMap(asString)
        val text = textFromMessage.orElse(textFromChoice).map(_.trim).filter(_.nonEmpty)
        text.map { value =>
          val metadata = rec
            .map(r => DynamicValues.recordFilterKeys(r, k => k != "message" && k != "text"))
            .getOrElse(DynamicValue.Record.empty)
          val toolCalls = rec.flatMap(r => field(r, "message")).flatMap(asRecord).map(parseToolCalls).getOrElse(Vector.empty)
          LmOutput(text = value, toolCalls = toolCalls, metadata = metadata)
        }
      }
    }

  private def parseTextOutputs(raw: DynamicValue): Either[DspyError, Vector[LmOutput]] =
    field(raw, "text").flatMap(asString).map(_.trim).filter(_.nonEmpty) match
      case Some(text) => Right(Vector(LmOutput(text = text)))
      case None       => parseChatOutputs(raw)

  private def parseResponsesOutputs(raw: DynamicValue): Either[DspyError, Vector[LmOutput]] =
    seqField(raw, "output").map { output =>
      output.flatMap { item =>
        extractText(item).map { text =>
          val toolCalls = asRecord(item).map(parseToolCalls).getOrElse(Vector.empty)
          val metadata  = asRecord(item).getOrElse(DynamicValue.Record.empty)
          LmOutput(text = text.trim, toolCalls = toolCalls, metadata = metadata)
        }
      }
    }

  private def parseUsage(raw: DynamicValue): Option[LmUsage] =
    field(raw, "usage").flatMap(asRecord).map { usage =>
      val promptTokens = longField(usage, "prompt_tokens").orElse(longField(usage, "input_tokens")).getOrElse(0L)
      val completionTokens =
        longField(usage, "completion_tokens").orElse(longField(usage, "output_tokens")).getOrElse(0L)
      val totalTokens = longField(usage, "total_tokens").getOrElse(promptTokens + completionTokens)
      val details = usage.fields.iterator.collect {
        case (key, value) if asLong(value).isDefined => key -> asLong(value).get
      }.toMap
      LmUsage(
        totalTokens = totalTokens,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        details = details
      )
    }

  private def parseToolCalls(message: DynamicValue.Record): Vector[ToolCall] =
    seqField(message, "tool_calls") match
      case Right(entries) =>
        entries.flatMap { call =>
          asRecord(call).flatMap { rec =>
            val functionRec = field(rec, "function").flatMap(asRecord).getOrElse(rec)
            field(functionRec, "name").flatMap(asString).map { name =>
              ToolCall(name = name, args = parseArgs(field(functionRec, "arguments")))
            }
          }
        }
      case Left(_) => Vector.empty

  private def parseArgs(raw: Option[DynamicValue]): DynamicValue.Record =
    raw.flatMap(asRecord) match
      case Some(rec) => rec
      case None =>
        raw.flatMap(asString) match
          case Some(value) if value.trim.nonEmpty => DynamicValues.recordFromEntries(Seq("input" := value))
          case _ =>
            raw match
              case Some(other) => DynamicValues.recordFromEntries(Seq("value" -> other))
              case None        => DynamicValue.Record.empty

  private def extractText(node: DynamicValue): Option[String] =
    field(node, "content") match
      case Some(content) =>
        asString(content) match
          case Some(text) => Some(text)
          case None =>
            val fromParts = DynamicJson.asSequence(content).iterator
              .flatMap(item => asRecord(item).flatMap(r => field(r, "text")).flatMap(asString).map(_.trim))
              .mkString("\n").trim
            Option
              .when(fromParts.nonEmpty)(fromParts)
              .orElse(field(node, "text").flatMap(asString).map(_.trim).filter(_.nonEmpty))
      case None =>
        field(node, "text").flatMap(asString).map(_.trim).filter(_.nonEmpty)

  /** A response field treated as an array: absent or null -> empty; a sequence -> its elements; anything else
    * -> a parse error (mirrors the old `asVector`). */
  private def seqField(value: DynamicValue, name: String): Either[DspyError, Vector[DynamicValue]] =
    field(value, name) match
      case None                              => Right(Vector.empty)
      case Some(_: DynamicValue.Null.type)   => Right(Vector.empty)
      case Some(seq: DynamicValue.Sequence)  => Right(seq.elements.iterator.toVector)
      case Some(other) =>
        Left(ParseError("lm", s"Expected array-like response field '$name', found: ${other.getClass.getSimpleName}"))

  private def longField(rec: DynamicValue.Record, name: String): Option[Long] =
    field(rec, name).flatMap(asLong)
