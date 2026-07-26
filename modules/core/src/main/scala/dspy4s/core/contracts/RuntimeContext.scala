package dspy4s.core.contracts

import zio.blocks.schema.DynamicValue

/** Runtime capabilities supplied by the environment. These are dependencies, not settings or accumulated output. */
final case class RuntimeServices(
    lm: Option[LanguageModelRef] = None,
    adapter: Option[AdapterRef] = None,
    callbacks: Vector[CallbackHandler] = Vector.empty
) derives CanEqual:
  def fillFrom(defaults: RuntimeServices): RuntimeServices =
    RuntimeServices(
      lm = lm.orElse(defaults.lm),
      adapter = adapter.orElse(defaults.adapter),
      callbacks = if callbacks.nonEmpty then callbacks else defaults.callbacks
    )

/** Immutable runtime policy and tuning values. Unlike [[RuntimeServices]], these fields contain no executable
  * dependencies; unlike [[RuntimeDelta]], they are inputs to execution rather than outputs accumulated by it.
  */
final case class RuntimeConfig(
    numThreads: Option[ThreadCount] = None,
    maxErrors: Option[ErrorLimit] = None,
    maxHistorySize: Option[HistoryLimit] = None,
    disableHistory: Option[Boolean] = None,
    trackUsage: Option[Boolean] = None,
    callbackMetadata: DynamicValue.Record = DynamicValue.Record.empty,
    captureFailureTraces: Boolean = false
) derives CanEqual:
  def fillFrom(defaults: RuntimeConfig): RuntimeConfig =
    RuntimeConfig(
      numThreads = numThreads.orElse(defaults.numThreads),
      maxErrors = maxErrors.orElse(defaults.maxErrors),
      maxHistorySize = maxHistorySize.orElse(defaults.maxHistorySize),
      disableHistory = disableHistory.orElse(defaults.disableHistory),
      trackUsage = trackUsage.orElse(defaults.trackUsage),
      callbackMetadata = if callbackMetadata.fields.isEmpty then defaults.callbackMetadata else callbackMetadata,
      captureFailureTraces = captureFailureTraces || defaults.captureFailureTraces
    )

/** Dynamic correlation scope for the current execution. Scope is restored when a `with*` boundary exits; it is not part
  * of the observable execution output captured by [[RuntimeDelta]].
  */
final case class RuntimeScope(
    asyncTaskId: Option[String] = None,
    activeCallId: Option[String] = None,
    callStack: Vector[String] = Vector.empty
) derives CanEqual:
  def fillFrom(defaults: RuntimeScope): RuntimeScope =
    RuntimeScope(
      asyncTaskId = asyncTaskId.orElse(defaults.asyncTaskId),
      activeCallId = activeCallId.orElse(defaults.activeCallId),
      callStack = if callStack.nonEmpty then callStack else defaults.callStack
    )

/** The unit of execution context, partitioned into services, configuration, dynamic scope, and accumulated delta. It is
  * threaded as `using RuntimeContext` through modules, adapters, language models, and callbacks.
  *
  * The flat constructor, field accessors, and `copy` method are retained as a compatibility surface. New runtime code
  * should update one partition explicitly with [[withServices]], [[withConfig]], [[withScope]], or [[withDelta]].
  */
final class RuntimeContext private (
    val services: RuntimeServices,
    val config: RuntimeConfig,
    val scope: RuntimeScope,
    val delta: RuntimeDelta
) derives CanEqual:

  def lm: Option[LanguageModelRef]          = services.lm
  def adapter: Option[AdapterRef]           = services.adapter
  def callbacks: Vector[CallbackHandler]    = services.callbacks
  def numThreads: Option[ThreadCount]       = config.numThreads
  def maxErrors: Option[ErrorLimit]         = config.maxErrors
  def maxHistorySize: Option[HistoryLimit]  = config.maxHistorySize
  def disableHistory: Option[Boolean]       = config.disableHistory
  def trackUsage: Option[Boolean]           = config.trackUsage
  def callbackMetadata: DynamicValue.Record = config.callbackMetadata
  def captureFailureTraces: Boolean         = config.captureFailureTraces
  def asyncTaskId: Option[String]           = scope.asyncTaskId
  def activeCallId: Option[String]          = scope.activeCallId
  def callStack: Vector[String]             = scope.callStack
  def trace: Vector[TraceEntry]             = delta.trace
  def history: Vector[HistoryEntry]         = delta.history

  def withServices(updated: RuntimeServices): RuntimeContext = RuntimeContext.fromParts(updated, config, scope, delta)
  def withConfig(updated: RuntimeConfig): RuntimeContext     = RuntimeContext.fromParts(services, updated, scope, delta)
  def withScope(updated: RuntimeScope): RuntimeContext       = RuntimeContext.fromParts(services, config, updated, delta)
  def withDelta(updated: RuntimeDelta): RuntimeContext       = RuntimeContext.fromParts(services, config, scope, updated)

  def withCallbacks(updated: Vector[CallbackHandler]): RuntimeContext =
    withServices(services.copy(callbacks = updated))
  def withHistory(updated: Vector[HistoryEntry]): RuntimeContext = withDelta(delta.copy(history = updated))
  def appendTrace(entry: TraceEntry): RuntimeContext             = withDelta(delta.copy(trace = trace :+ entry))
  def appendHistory(entry: HistoryEntry): RuntimeContext         = withDelta(delta.copy(history = history :+ entry))

  /** Compatibility copy over the former flat case-class surface. */
  def copy(
      lm: Option[LanguageModelRef] = this.lm,
      adapter: Option[AdapterRef] = this.adapter,
      callbacks: Vector[CallbackHandler] = this.callbacks,
      numThreads: Option[ThreadCount] = this.numThreads,
      maxErrors: Option[ErrorLimit] = this.maxErrors,
      maxHistorySize: Option[HistoryLimit] = this.maxHistorySize,
      disableHistory: Option[Boolean] = this.disableHistory,
      trackUsage: Option[Boolean] = this.trackUsage,
      callbackMetadata: DynamicValue.Record = this.callbackMetadata,
      captureFailureTraces: Boolean = this.captureFailureTraces,
      asyncTaskId: Option[String] = this.asyncTaskId,
      activeCallId: Option[String] = this.activeCallId,
      callStack: Vector[String] = this.callStack,
      trace: Vector[TraceEntry] = this.trace,
      history: Vector[HistoryEntry] = this.history
  ): RuntimeContext =
    RuntimeContext(
      lm = lm,
      adapter = adapter,
      callbacks = callbacks,
      numThreads = numThreads,
      maxErrors = maxErrors,
      maxHistorySize = maxHistorySize,
      disableHistory = disableHistory,
      trackUsage = trackUsage,
      callbackMetadata = callbackMetadata,
      captureFailureTraces = captureFailureTraces,
      asyncTaskId = asyncTaskId,
      activeCallId = activeCallId,
      callStack = callStack,
      trace = trace,
      history = history
    )

  /** Fill unset services/config/scope fields from `defaults`. The accumulated delta is intentionally never inherited:
    * it belongs to this execution overlay.
    */
  def fillFrom(defaults: RuntimeContext): RuntimeContext =
    RuntimeContext.fromParts(
      services.fillFrom(defaults.services),
      config.fillFrom(defaults.config),
      scope.fillFrom(defaults.scope),
      delta
    )

  override def equals(other: Any): Boolean = other match
    case that: RuntimeContext =>
      services == that.services && config == that.config && scope == that.scope && delta == that.delta
    case _ => false

  override def hashCode(): Int = (services, config, scope, delta).hashCode()

  override def toString: String = s"RuntimeContext($services,$config,$scope,$delta)"

object RuntimeContext:
  /** Flat compatibility constructor. */
  def apply(
      lm: Option[LanguageModelRef] = None,
      adapter: Option[AdapterRef] = None,
      callbacks: Vector[CallbackHandler] = Vector.empty,
      numThreads: Option[ThreadCount] = None,
      maxErrors: Option[ErrorLimit] = None,
      maxHistorySize: Option[HistoryLimit] = None,
      disableHistory: Option[Boolean] = None,
      trackUsage: Option[Boolean] = None,
      callbackMetadata: DynamicValue.Record = DynamicValue.Record.empty,
      captureFailureTraces: Boolean = false,
      asyncTaskId: Option[String] = None,
      activeCallId: Option[String] = None,
      callStack: Vector[String] = Vector.empty,
      trace: Vector[TraceEntry] = Vector.empty,
      history: Vector[HistoryEntry] = Vector.empty
  ): RuntimeContext =
    fromParts(
      RuntimeServices(lm, adapter, callbacks),
      RuntimeConfig(
        numThreads,
        maxErrors,
        maxHistorySize,
        disableHistory,
        trackUsage,
        callbackMetadata,
        captureFailureTraces
      ),
      RuntimeScope(asyncTaskId, activeCallId, callStack),
      RuntimeDelta(trace, history)
    )

  def fromParts(
      services: RuntimeServices = RuntimeServices(),
      config: RuntimeConfig = RuntimeConfig(),
      scope: RuntimeScope = RuntimeScope(),
      delta: RuntimeDelta = RuntimeDelta.empty
  ): RuntimeContext =
    new RuntimeContext(services, config, scope, delta)
