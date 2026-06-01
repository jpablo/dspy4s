package dspy4s.lm.contracts

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.LanguageModelRef
import dspy4s.core.contracts.RuntimeContext

import scala.annotation.nowarn
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

enum LmMode:
  case Chat
  case Text
  case Responses

enum MessageRole:
  case System
  case User
  case Assistant

final case class ContentPart(kind: String, payload: String, metadata: Map[String, String] = Map.empty)

final case class Message(
    role: MessageRole,
    text: Option[String] = None,
    parts: Vector[ContentPart] = Vector.empty,
    metadata: Map[String, String] = Map.empty
)

final case class LmRequest(
    model: String,
    mode: LmMode = LmMode.Chat,
    messages: Vector[Message] = Vector.empty,
    options: Map[String, Any] = Map.empty,
    requestId: Option[String] = None
)

final case class LmUsage(
    totalTokens: Long = 0L,
    promptTokens: Long = 0L,
    completionTokens: Long = 0L,
    details: Map[String, Long] = Map.empty
)

final case class ToolCall(name: String, args: Map[String, Any])

final case class LmOutput(
    text: String,
    toolCalls: Vector[ToolCall] = Vector.empty,
    metadata: Map[String, Any] = Map.empty
)

final case class LmResponse(
    outputs: Vector[LmOutput],
    usage: Option[LmUsage] = None,
    modelName: Option[String] = None,
    cacheHit: Boolean = false
)

trait LmCache:
  def get(request: LmRequest): Option[LmResponse]
  def put(request: LmRequest, response: LmResponse): Unit

trait RetryPolicy:
  def shouldRetry(attempt: Int, error: DspyError): Boolean
  def delayBeforeNextAttemptMillis(attempt: Int, error: DspyError): Long = 0L

trait LanguageModel extends LanguageModelRef:
  def id: String
  def mode: LmMode

  def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse]

  @nowarn("msg=unused")
  def acall(request: LmRequest)(using RuntimeContext, ExecutionContext): Future[Either[DspyError, LmResponse]] =
    Future.successful(call(request))
