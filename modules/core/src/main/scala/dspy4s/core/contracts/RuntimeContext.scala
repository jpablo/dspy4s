package dspy4s.core.contracts

import zio.blocks.schema.DynamicValue

/** Runtime capabilities supplied by the environment. These are dependencies, not settings or accumulated output. */
final case class RuntimeServices(
    lm       : Option[LanguageModelRef] = None,
    adapter  : Option[AdapterRef]       = None,
    callbacks: Vector[CallbackHandler]  = Vector.empty,
    /** The factory that builds the REPL interpreter of a code program such as `RLM`, when the program has no factory of
      * its own (Python DSPy's `dspy.settings.interpreter_factory`). `None` selects the default of the program.
      */
    interpreterFactory: Option[ReplInterpreterFactory] = None
) derives CanEqual:
  /** Fill each unset field from `defaults`. When `defaults` fills no field, the result is this value itself. */
  def fillFrom(defaults: RuntimeServices): RuntimeServices =
    val complete = (lm.isDefined || defaults.lm.isEmpty) && (adapter.isDefined || defaults.adapter.isEmpty) &&
      (callbacks.nonEmpty || defaults.callbacks.isEmpty) &&
      (interpreterFactory.isDefined || defaults.interpreterFactory.isEmpty)
    if complete then this
    else
      RuntimeServices(
        lm = lm.orElse(defaults.lm),
        adapter = adapter.orElse(defaults.adapter),
        callbacks = if callbacks.nonEmpty then callbacks else defaults.callbacks,
        interpreterFactory = interpreterFactory.orElse(defaults.interpreterFactory)
      )

/** Immutable runtime policy and tuning values. Unlike [[RuntimeServices]], these fields contain no executable
  * dependencies; unlike [[RuntimeDelta]], they are inputs to execution rather than outputs accumulated by it.
  */
final case class RuntimeConfig(
    numThreads          : Option[ThreadCount]  = None,
    maxErrors           : Option[ErrorLimit]   = None,
    maxHistorySize      : Option[HistoryLimit] = None,
    disableHistory      : Option[Boolean]      = None,
    trackUsage          : Option[Boolean]      = None,
    callbackMetadata    : DynamicValue.Record  = DynamicValue.Record.empty,
    captureFailureTraces: Boolean              = false
) derives CanEqual:
  /** Fill each unset field from `defaults`. When `defaults` fills no field, the result is this value itself. */
  def fillFrom(defaults: RuntimeConfig): RuntimeConfig =
    val complete =
      (numThreads.isDefined || defaults.numThreads.isEmpty) &&
        (maxErrors.isDefined || defaults.maxErrors.isEmpty) &&
        (maxHistorySize.isDefined || defaults.maxHistorySize.isEmpty) &&
        (disableHistory.isDefined || defaults.disableHistory.isEmpty) &&
        (trackUsage.isDefined || defaults.trackUsage.isEmpty) &&
        (callbackMetadata.fields.nonEmpty || defaults.callbackMetadata.fields.isEmpty) &&
        (captureFailureTraces || !defaults.captureFailureTraces)
    if complete then this
    else
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
    callStack  : Vector[String] = Vector.empty,
    /** The key of the predictor that runs: the outermost module on the call path that owns a [[PredictorKey]]. A module
      * that starts inside that extent records no key in its trace entries, and the decision evidence of the extent
      * belongs to this key.
      */
    predictor: Option[PredictorKey] = None
) derives CanEqual:
  /** The innermost open call scope's id: always the top of [[callStack]]. */
  def activeCallId: Option[String] = callStack.lastOption

  /** `true` while a predictor that owns a [[PredictorKey]] runs. */
  def withinPredictor: Boolean = predictor.isDefined

  /** Fill each unset field from `defaults`. When `defaults` fills no field, the result is this value itself. */
  def fillFrom(defaults: RuntimeScope): RuntimeScope =
    val complete = (asyncTaskId.isDefined || defaults.asyncTaskId.isEmpty) &&
      (callStack.nonEmpty || defaults.callStack.isEmpty) && (predictor.isDefined || defaults.predictor.isEmpty)
    if complete then this
    else
      RuntimeScope(
        asyncTaskId = asyncTaskId.orElse(defaults.asyncTaskId),
        callStack = if callStack.nonEmpty then callStack else defaults.callStack,
        predictor = predictor.orElse(defaults.predictor)
      )

/** The unit of execution context, partitioned into services, configuration, dynamic scope, and accumulated delta. It is
  * threaded as `using RuntimeContext` through modules, adapters, language models, and callbacks.
  *
  * Use the flat constructor to build a context and the field accessors to read one. The flat `copy` changes fields of
  * more than one partition. Runtime code updates one partition with [[withServices]], [[withConfig]], [[withScope]], or
  * [[withDelta]].
  */
final class RuntimeContext private (
    val services: RuntimeServices,
    val config  : RuntimeConfig,
    val scope   : RuntimeScope,
    val delta   : RuntimeDelta
) derives CanEqual:

  def lm: Option[LanguageModelRef]                       = services.lm
  def adapter: Option[AdapterRef]                        = services.adapter
  def callbacks: Vector[CallbackHandler]                 = services.callbacks
  def interpreterFactory: Option[ReplInterpreterFactory] = services.interpreterFactory
  def numThreads: Option[ThreadCount]                    = config.numThreads
  def maxErrors: Option[ErrorLimit]                      = config.maxErrors
  def maxHistorySize: Option[HistoryLimit]               = config.maxHistorySize
  def disableHistory: Option[Boolean]                    = config.disableHistory
  def trackUsage: Option[Boolean]                        = config.trackUsage
  def callbackMetadata: DynamicValue.Record              = config.callbackMetadata
  def captureFailureTraces: Boolean                      = config.captureFailureTraces
  def asyncTaskId: Option[String]                        = scope.asyncTaskId
  def activeCallId: Option[String]                       = scope.activeCallId
  def callStack: Vector[String]                          = scope.callStack
  def trace: Vector[TraceEntry]                          = delta.trace
  def history: Vector[HistoryEntry]                      = delta.history

  def withServices(updated: RuntimeServices): RuntimeContext = RuntimeContext.fromParts(updated, config, scope, delta)
  def withConfig(updated  : RuntimeConfig): RuntimeContext   = RuntimeContext.fromParts(services, updated, scope, delta)
  def withScope(updated   : RuntimeScope): RuntimeContext    = RuntimeContext.fromParts(services, config, updated, delta)
  def withDelta(updated   : RuntimeDelta): RuntimeContext    = RuntimeContext.fromParts(services, config, scope, updated)

  def withCallbacks(updated: Vector[CallbackHandler]): RuntimeContext =
    withServices(services.copy(callbacks = updated))
  def withHistory(updated: Vector[HistoryEntry]): RuntimeContext = withDelta(delta.copy(history = updated))
  def appendTrace(entry  : TraceEntry): RuntimeContext           = withDelta(delta.copy(trace = trace :+ entry))
  def appendHistory(entry: HistoryEntry): RuntimeContext         = withDelta(delta.copy(history = history :+ entry))

  /** Compatibility copy over the former flat case-class surface. */
  def copy(
      lm                  : Option[LanguageModelRef]       = this.lm,
      adapter             : Option[AdapterRef]             = this.adapter,
      callbacks           : Vector[CallbackHandler]        = this.callbacks,
      numThreads          : Option[ThreadCount]            = this.numThreads,
      maxErrors           : Option[ErrorLimit]             = this.maxErrors,
      maxHistorySize      : Option[HistoryLimit]           = this.maxHistorySize,
      disableHistory      : Option[Boolean]                = this.disableHistory,
      trackUsage          : Option[Boolean]                = this.trackUsage,
      callbackMetadata    : DynamicValue.Record            = this.callbackMetadata,
      captureFailureTraces: Boolean                        = this.captureFailureTraces,
      asyncTaskId         : Option[String]                 = this.asyncTaskId,
      callStack           : Vector[String]                 = this.callStack,
      trace               : Vector[TraceEntry]             = this.trace,
      history             : Vector[HistoryEntry]           = this.history,
      interpreterFactory  : Option[ReplInterpreterFactory] = this.interpreterFactory
  ): RuntimeContext =
    // The scope is copied, not built again: it has fields that this flat surface does not show.
    RuntimeContext.fromParts(
      RuntimeServices(lm, adapter, callbacks, interpreterFactory),
      RuntimeConfig(
        numThreads,
        maxErrors,
        maxHistorySize,
        disableHistory,
        trackUsage,
        callbackMetadata,
        captureFailureTraces
      ),
      scope.copy(asyncTaskId = asyncTaskId, callStack = callStack),
      RuntimeDelta(trace, history)
    )

  /** Fill unset services/config/scope fields from `defaults`. The accumulated delta is intentionally never inherited:
    * it belongs to this execution overlay.
    *
    * When `defaults` fills no field, the result is this context itself. Thus [[dspy4s.core.runtime.RuntimeEnvironment]]
    * gives the installed context itself when the global default adds nothing, and the reference check of
    * `RuntimeEnvironment.withInstalled` finds it.
    */
  def fillFrom(defaults: RuntimeContext): RuntimeContext =
    val filledServices = services.fillFrom(defaults.services)
    val filledConfig   = config.fillFrom(defaults.config)
    val filledScope    = scope.fillFrom(defaults.scope)
    if (filledServices eq services) && (filledConfig eq config) && (filledScope eq scope) then this
    else RuntimeContext.fromParts(filledServices, filledConfig, filledScope, delta)

  override def equals(other: Any): Boolean =
    other match
      case that: RuntimeContext => services == that.services && config == that.config && scope == that.scope &&
        delta == that.delta
      case _ => false

  override def hashCode(): Int = (services, config, scope, delta).hashCode()

  override def toString: String = s"RuntimeContext($services,$config,$scope,$delta)"

object RuntimeContext:
  /** The flat constructor: one parameter for each field of the four partitions. */
  def apply(
      lm                  : Option[LanguageModelRef]       = None,
      adapter             : Option[AdapterRef]             = None,
      callbacks           : Vector[CallbackHandler]        = Vector.empty,
      numThreads          : Option[ThreadCount]            = None,
      maxErrors           : Option[ErrorLimit]             = None,
      maxHistorySize      : Option[HistoryLimit]           = None,
      disableHistory      : Option[Boolean]                = None,
      trackUsage          : Option[Boolean]                = None,
      callbackMetadata    : DynamicValue.Record            = DynamicValue.Record.empty,
      captureFailureTraces: Boolean                        = false,
      asyncTaskId         : Option[String]                 = None,
      callStack           : Vector[String]                 = Vector.empty,
      trace               : Vector[TraceEntry]             = Vector.empty,
      history             : Vector[HistoryEntry]           = Vector.empty,
      interpreterFactory  : Option[ReplInterpreterFactory] = None
  ): RuntimeContext =
    fromParts(
      RuntimeServices(lm, adapter, callbacks, interpreterFactory),
      RuntimeConfig(
        numThreads,
        maxErrors,
        maxHistorySize,
        disableHistory,
        trackUsage,
        callbackMetadata,
        captureFailureTraces
      ),
      RuntimeScope(asyncTaskId, callStack),
      RuntimeDelta(trace, history)
    )

  def fromParts(
      services: RuntimeServices = RuntimeServices(),
      config  : RuntimeConfig   = RuntimeConfig(),
      scope   : RuntimeScope    = RuntimeScope(),
      delta   : RuntimeDelta    = RuntimeDelta.empty
  ): RuntimeContext =
    new RuntimeContext(services, config, scope, delta)
