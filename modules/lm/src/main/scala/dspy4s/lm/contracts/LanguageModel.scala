package dspy4s.lm.contracts

import dspy4s.core.contracts.{DspyError, Executed, LanguageModelRef, RuntimeContext}
import dspy4s.core.runtime.{ContextPropagation, UsageScope}
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

  /** Experimental. Whether this model is a System One model: it answers decision requests ([[DecisionRequests]]) with
    * probabilities, and generates no text (upstream `supports_decision_requests`, for example TypeSafe Jev). `Predict`
    * then sends a decision request in place of a prompt. Defaults to `false`; a wrapper forwards the value of its
    * delegate.
    */
  def supportsDecisionRequests: Boolean = false

  /** The provider options that this model merges into each request (e.g. `temperature`). The options of the request win
    * on a key collision. The cache key covers these options, so a wrapper must forward the value of its delegate.
    * Defaults to an empty record.
    */
  def defaultOptions: DynamicValue.Record = DynamicValue.Record.empty

  /** The settings of this model that change its answer but that the request does not have, for example the endpoint.
    * The cache key of [[dspy4s.lm.runtime.ManagedLanguageModel]] covers them, so two models with one id and different
    * identities get different entries. A wrapper must forward the value of its delegate. Defaults to an empty record.
    *
    * Upstream puts the base URL in the key of TypeSafe only (`_request` in `dspy/clients/typesafe.py`). `dspy.LM`
    * leaves the base URL out of its key, so the OpenAI model keeps the empty default.
    */
  def cacheIdentity: DynamicValue.Record = DynamicValue.Record.empty

  def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse]

  /** Release the resources that this model owns, for example the HTTP client of a provider (upstream `LM.close`). The
    * model stays usable: a later call opens new resources. A copy of a model shares its resources, so a `close` of one
    * copy releases them for each copy. A wrapper closes its delegate. The default does nothing.
    */
  def close(): Unit = ()

  def acall(request: LmRequest)(using RuntimeContext, ExecutionContext): Future[Either[DspyError, LmResponse]] =
    acallExecuted(request).map(_.value)(using ExecutionContext.parasitic)

  /** Async writer entry for callers that need to retain the worker's trace/history delta. */
  def acallExecuted(request: LmRequest)(using
      RuntimeContext,
      ExecutionContext
  ): Future[Executed[Either[DspyError, LmResponse]]] =
    ContextPropagation.futureExecuted(call(request))

object LanguageModel:

  /** One model call of the framework: `model.call(request)`, then the usage of a successful reply goes to each open
    * usage scope, with the model name `request.model` ([[UsageScope.record]], decision D9 of UPGRADE-3.2.1-to-3.4.0).
    * This function is the one record point of the LM usage. The user trackers (`UsageTracking`) and the usage of a
    * program result get each record from here. Upstream records the usage of each LM call into `settings.usage_tracker`
    * (`dspy/clients/execution.py`, `finalize`).
    *
    * Each part of the framework that calls a model for a program uses this function. These parts are `Predict` (each
    * attempt, also an attempt whose reply then fails to parse), the extraction step of `TwoStepAdapter`, and the sub-LM
    * tools of `RLM`. A tool or a program of the user that calls a model directly must also use it, else the call does
    * not count. A direct `call` or `stream` of a model counts in no scope, also for a
    * [[dspy4s.lm.runtime.ManagedLanguageModel]].
    *
    * A `Left` gives no record: a call that fails at the provider has no reply, thus no usage. A cache hit of
    * [[dspy4s.lm.runtime.ManagedLanguageModel]] has no usage, thus no record (upstream `finalize` skips a cache hit).
    * With `trackUsage = Some(false)` in the context, the user trackers get nothing, and the program results still get
    * the usage.
    */
  def callAndRecordUsage(model: LanguageModel, request: LmRequest)(using
      RuntimeContext
  ): Either[DspyError, LmResponse] =
    model.call(request).map { response =>
      UsageScope.record(request.model, response.usage)
      response
    }
