package dspy4s.lm.contracts

import dspy4s.core.contracts.{DspyError, Executed, LanguageModelRef, RuntimeContext}
import dspy4s.core.runtime.ContextPropagation
import zio.blocks.schema.DynamicValue

import scala.concurrent.{ExecutionContext, Future}

trait LanguageModel extends LanguageModelRef:
  def id: String
  def mode: LmMode

  /** Whether this model can be invoked with tool/function definitions and may return
    * [[dspy4s.core.contracts.ToolCall]]s. Defaults to `false`; providers that support the chat-completions tool
    * protocol override to `true`.
    */
  def supportsFunctionCalling: Boolean = false

  /** Whether this model can be constrained to a structured/JSON response schema (e.g. OpenAI's `response_format`).
    * Defaults to `false`.
    */
  def supportsResponseSchema: Boolean = false

  /** Whether this model exposes reasoning/thinking output (e.g. reasoning-token models). Defaults to `false`. */
  def supportsReasoning: Boolean = false

  /** The provider options that this model merges into each request (e.g. `temperature`). The options of the request win
    * on a key collision. The cache key covers these options, so a wrapper must forward the value of its delegate.
    * Defaults to an empty record.
    */
  def defaultOptions: DynamicValue.Record = DynamicValue.Record.empty

  def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse]

  def acall(request: LmRequest)(using RuntimeContext, ExecutionContext): Future[Either[DspyError, LmResponse]] =
    acallExecuted(request).map(_.value)(using ExecutionContext.parasitic)

  /** Async writer entry for callers that need to retain the worker's trace/history delta. */
  def acallExecuted(request: LmRequest)(using
      RuntimeContext,
      ExecutionContext
  ): Future[Executed[Either[DspyError, LmResponse]]] =
    ContextPropagation.futureExecuted(call(request))
