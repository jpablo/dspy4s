package dspy4s.lm.runtime

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.lm.contracts.ContentPart
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.LmUsage
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.ToolCall

final case class ProviderLanguageModel(
    id: String,
    mode: LmMode,
    invoke: Map[String, Any] => Either[DspyError, Map[String, Any]],
    defaultOptions: Map[String, Any] = Map.empty
) extends LanguageModel:
  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    val effectiveMode = request.mode
    val normalizedRequest = ProviderRequestNormalizer.normalize(request, defaultOptions = defaultOptions)
    invoke(normalizedRequest).flatMap(raw => ProviderResponseParser.parse(raw, effectiveMode))

object ProviderRequestNormalizer:
  def normalize(request: LmRequest, defaultOptions: Map[String, Any] = Map.empty): Map[String, Any] =
    val base = Map("model" -> request.model, "mode" -> request.mode.toString.toLowerCase)
    val options = defaultOptions ++ request.options
    val withRequestId = request.requestId match
      case Some(requestId) => base.updated("request_id", requestId)
      case None            => base

    request.mode match
      case LmMode.Chat | LmMode.Responses =>
        withRequestId ++ options.updated("messages", request.messages.map(encodeMessage))
      case LmMode.Text =>
        val prompt = request.messages.map(messageText).mkString("\n").trim
        withRequestId ++ options.updated("prompt", prompt)

  private def encodeMessage(message: Message): Map[String, Any] =
    val role = message.role.toString.toLowerCase
    if message.parts.nonEmpty then
      Map(
        "role" -> role,
        "content" -> message.parts.map(encodePart)
      )
    else
      Map(
        "role" -> role,
        "content" -> message.text.getOrElse("")
      )

  private def encodePart(part: ContentPart): Map[String, Any] =
    val base = Map("type" -> part.kind, "text" -> part.payload)
    if part.metadata.nonEmpty then base.updated("metadata", part.metadata)
    else base

  private def messageText(message: Message): String =
    message.text.getOrElse {
      message.parts.map(_.payload).mkString("\n")
    }

object ProviderResponseParser:
  def parse(raw: Map[String, Any], mode: LmMode): Either[DspyError, LmResponse] =
    val outputs = mode match
      case LmMode.Chat => parseChatOutputs(raw)
      case LmMode.Text => parseTextOutputs(raw)
      case LmMode.Responses =>
        parseResponsesOutputs(raw).orElse(parseChatOutputs(raw))

    outputs.flatMap { entries =>
      if entries.isEmpty then Left(ParseError("lm", "Provider response does not contain any outputs"))
      else
        Right(
          LmResponse(
            outputs = entries,
            usage = parseUsage(raw),
            modelName = raw.get("model").collect { case model: String => model },
            cacheHit = false
          )
        )
    }

  private def parseChatOutputs(raw: Map[String, Any]): Either[DspyError, Vector[LmOutput]] =
    asVector(raw.get("choices")).map { choices =>
      choices.flatMap { choice =>
        val map = asMap(choice)
        val textFromMessage = map.flatMap(_.get("message")).flatMap(asMap).flatMap(extractText)
        val textFromChoice = map.flatMap(_.get("text")).collect { case text: String => text }
        val text = textFromMessage.orElse(textFromChoice).map(_.trim).filter(_.nonEmpty)
        text.map { value =>
          val metadata = map.map(_.removed("message").removed("text")).getOrElse(Map.empty)
          val toolCalls = map.flatMap(_.get("message")).flatMap(asMap).map(parseToolCalls).getOrElse(Vector.empty)
          LmOutput(text = value, toolCalls = toolCalls, metadata = metadata)
        }
      }
    }

  private def parseTextOutputs(raw: Map[String, Any]): Either[DspyError, Vector[LmOutput]] =
    raw.get("text") match
      case Some(text: String) if text.trim.nonEmpty =>
        Right(Vector(LmOutput(text = text.trim)))
      case _ =>
        parseChatOutputs(raw)

  private def parseResponsesOutputs(raw: Map[String, Any]): Either[DspyError, Vector[LmOutput]] =
    asVector(raw.get("output")).map { output =>
      output.flatMap { item =>
        val map = asMap(item)
        val maybeText = map.flatMap(extractText)
        maybeText.map { text =>
          val toolCalls = map.map(parseToolCalls).getOrElse(Vector.empty)
          LmOutput(text = text.trim, toolCalls = toolCalls, metadata = map.getOrElse(Map.empty))
        }
      }
    }

  private def parseUsage(raw: Map[String, Any]): Option[LmUsage] =
    raw.get("usage").flatMap(asMap).map { usage =>
      val promptTokens = asLong(usage.get("prompt_tokens"))
        .orElse(asLong(usage.get("input_tokens")))
        .getOrElse(0L)
      val completionTokens = asLong(usage.get("completion_tokens"))
        .orElse(asLong(usage.get("output_tokens")))
        .getOrElse(0L)
      val totalTokens = asLong(usage.get("total_tokens")).getOrElse(promptTokens + completionTokens)
      val details = usage.iterator.collect { case (key, value) if asLong(Some(value)).isDefined =>
        key -> asLong(Some(value)).get
      }.toMap
      LmUsage(
        totalTokens = totalTokens,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        details = details
      )
    }

  private def parseToolCalls(message: Map[String, Any]): Vector[ToolCall] =
    asVector(message.get("tool_calls")) match
      case Right(entries) =>
        entries.flatMap { call =>
          asMap(call).flatMap { map =>
            val functionMap = map.get("function").flatMap(asMap).getOrElse(map)
            functionMap.get("name").collect { case name: String =>
              ToolCall(
                name = name,
                args = parseArgs(functionMap.get("arguments"))
              )
            }
          }
        }
      case Left(_) =>
        Vector.empty

  private def parseArgs(raw: Option[Any]): Map[String, Any] =
    raw.flatMap(asMap).getOrElse {
      raw match
        case Some(value: String) if value.trim.nonEmpty => Map("input" -> value)
        case Some(value)                                => Map("value" -> value)
        case None                                       => Map.empty
    }

  private def extractText(node: Map[String, Any]): Option[String] =
    node.get("content") match
      case Some(text: String) => Some(text)
      case Some(items) =>
        val fromParts = asVector(Some(items)) match
          case Right(entries) =>
            val text = entries.flatMap { item =>
              asMap(item).flatMap { map =>
                map.get("text").collect { case value: String => value.trim }
              }
            }.mkString("\n").trim
            Option.when(text.nonEmpty)(text)
          case Left(_) =>
            None
        fromParts.orElse {
          node.get("text").collect { case value: String if value.trim.nonEmpty => value.trim }
        }
      case None =>
        node.get("text").collect { case value: String if value.trim.nonEmpty => value.trim }

  private def asMap(value: Any): Option[Map[String, Any]] =
    value match
      case map: Map[?, ?] =>
        Some(map.iterator.collect { case (key: String, item) => key -> item }.toMap)
      case _ => None

  private def asVector(value: Option[Any]): Either[DspyError, Vector[Any]] =
    value match
      case None => Right(Vector.empty)
      case Some(vector: Vector[?]) =>
        Right(vector)
      case Some(seq: Seq[?]) =>
        Right(seq.toVector)
      case Some(other) =>
        Left(ParseError("lm", s"Expected array-like response field, found: ${other.getClass.getSimpleName}"))

  private def asLong(value: Option[Any]): Option[Long] =
    value match
      case Some(v: Long)       => Some(v)
      case Some(v: Int)        => Some(v.toLong)
      case Some(v: Short)      => Some(v.toLong)
      case Some(v: Byte)       => Some(v.toLong)
      case Some(v: Double)     => Some(v.toLong)
      case Some(v: Float)      => Some(v.toLong)
      case Some(v: BigInt)     => Some(v.toLong)
      case Some(v: BigDecimal) => Some(v.toLong)
      case Some(v: String)     => v.toLongOption
      case _                   => None
