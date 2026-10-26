package dspy4s.core.contracts

import zio.blocks.schema.DynamicValue

/** Runtime capabilities supplied by the environment. These are dependencies, not settings or accumulated output.
  *
  * A field is "not set" when it is `None`, and [[fillFrom]] fills it from the defaults. A set value wins, also when it
  * is an empty list.
  */
final case class RuntimeServices(
    lm     : Option[LanguageModelRef] = None,
    adapter: Option[AdapterRef]       = None,
    /** The callback handlers. An empty list is a set value: a scope with it turns off the handlers of the defaults, as
      * upstream `dspy.context(callbacks=[])`.
      */
    callbacks: Option[Vector[CallbackHandler]] = None,
    /** The factory that builds the REPL interpreter of a code program such as `RLM`, when the program has no factory of
      * its own (Python DSPy's `dspy.settings.interpreter_factory`). `None` selects the default of the program.
      */
    interpreterFactory: Option[ReplInterpreterFactory] = None
) derives CanEqual:
  /** Fill each unset field from `defaults`. When `defaults` fills no field, the result is this value itself. */
  def fillFrom(defaults: RuntimeServices): RuntimeServices =
    val complete = (lm.isDefined || defaults.lm.isEmpty) && (adapter.isDefined || defaults.adapter.isEmpty) &&
      (callbacks.isDefined || defaults.callbacks.isEmpty) &&
      (interpreterFactory.isDefined || defaults.interpreterFactory.isEmpty)
    if complete then this
    else
      RuntimeServices(
        lm = lm.orElse(defaults.lm),
        adapter = adapter.orElse(defaults.adapter),
        callbacks = callbacks.orElse(defaults.callbacks),
        interpreterFactory = interpreterFactory.orElse(defaults.interpreterFactory)
      )

  /** `true` when this value filled from `installed` gives `installed`: each service that this value sets is the same
    * object as in `installed`. When this value sets the callback list, `installed` sets a list with the same callbacks
    * in the same order. The check compares references only.
    */
  private[dspy4s] def keptBy(installed: RuntimeServices): Boolean =
    sameObject(lm, installed.lm) && sameObject(adapter, installed.adapter) &&
      sameObject(interpreterFactory, installed.interpreterFactory) &&
      callbacks.forall { requested =>
        installed.callbacks.exists(list => (list eq requested) || list.corresponds(requested)(_ eq _))
      }

  /** `true` when `requested` sets no value, or the same object as `installed`. */
  private def sameObject[A <: AnyRef](requested: Option[A], installed: Option[A]): Boolean =
    requested.forall(value => installed.exists(_ eq value))

/** Immutable runtime policy and tuning values. Unlike [[RuntimeServices]], these fields contain no executable
  * dependencies; unlike [[RuntimeDelta]], they are inputs to execution rather than outputs accumulated by it.
  *
  * A field is "not set" when it is `None`, and [[fillFrom]] fills it from the defaults. A set value wins, also when it
  * is `false` or an empty record.
  */
final case class RuntimeConfig(
    numThreads    : Option[ThreadCount]  = None,
    maxErrors     : Option[ErrorLimit]   = None,
    maxHistorySize: Option[HistoryLimit] = None,
    disableHistory: Option[Boolean]      = None,
    /** `Some(false)`: the usage trackers of the user get no usage (see `dspy4s.core.runtime.UsageScope`). The usage of
      * the program results stays.
      */
    trackUsage: Option[Boolean] = None,
    /** The metadata for the callbacks. An empty record is a set value: it hides the metadata of the defaults. */
    callbackMetadata: Option[DynamicValue.Record] = None,
    /** `Some(true)`: a failed module call records a failure trace entry. `Some(false)` turns off the failure traces of
      * the defaults.
      */
    captureFailureTraces: Option[Boolean] = None
) derives CanEqual:
  /** Fill each unset field from `defaults`. When `defaults` fills no field, the result is this value itself. */
  def fillFrom(defaults: RuntimeConfig): RuntimeConfig =
    val complete =
      (numThreads.isDefined || defaults.numThreads.isEmpty) &&
        (maxErrors.isDefined || defaults.maxErrors.isEmpty) &&
        (maxHistorySize.isDefined || defaults.maxHistorySize.isEmpty) &&
        (disableHistory.isDefined || defaults.disableHistory.isEmpty) &&
        (trackUsage.isDefined || defaults.trackUsage.isEmpty) &&
        (callbackMetadata.isDefined || defaults.callbackMetadata.isEmpty) &&
        (captureFailureTraces.isDefined || defaults.captureFailureTraces.isEmpty)
    if complete then this
    else
      RuntimeConfig(
        numThreads = numThreads.orElse(defaults.numThreads),
        maxErrors = maxErrors.orElse(defaults.maxErrors),
        maxHistorySize = maxHistorySize.orElse(defaults.maxHistorySize),
        disableHistory = disableHistory.orElse(defaults.disableHistory),
        trackUsage = trackUsage.orElse(defaults.trackUsage),
        callbackMetadata = callbackMetadata.orElse(defaults.callbackMetadata),
        captureFailureTraces = captureFailureTraces.orElse(defaults.captureFailureTraces)
      )

  /** `true` when this value filled from `installed` gives `installed`: each number and each flag that this value sets
    * is equal to the value in `installed`, and the callback metadata, when this value sets it, is the same object.
    */
  private[dspy4s] def keptBy(installed: RuntimeConfig): Boolean =
    numThreads.forall(installed.numThreads.contains) &&
      maxErrors.forall(installed.maxErrors.contains) &&
      maxHistorySize.forall(installed.maxHistorySize.contains) &&
      disableHistory.forall(installed.disableHistory.contains) &&
      trackUsage.forall(installed.trackUsage.contains) &&
      captureFailureTraces.forall(installed.captureFailureTraces.contains) &&
      callbackMetadata.forall(requested => installed.callbackMetadata.exists(_ eq requested))

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
  *
  * The accessors [[callbacks]], [[callbackMetadata]], and [[captureFailureTraces]] give the effective value: the set
  * value, or the default value when the field is not set. Read `services` or `config` to know if a field is set.
  */
final class RuntimeContext private (
    val services: RuntimeServices,
    val config  : RuntimeConfig,
    val scope   : RuntimeScope,
    val delta   : RuntimeDelta
) derives CanEqual:

  def lm: Option[LanguageModelRef]                       = services.lm
  def adapter: Option[AdapterRef]                        = services.adapter
  def interpreterFactory: Option[ReplInterpreterFactory] = services.interpreterFactory
  def numThreads: Option[ThreadCount]                    = config.numThreads
  def maxErrors: Option[ErrorLimit]                      = config.maxErrors
  def maxHistorySize: Option[HistoryLimit]               = config.maxHistorySize
  def disableHistory: Option[Boolean]                    = config.disableHistory
  def trackUsage: Option[Boolean]                        = config.trackUsage
  def asyncTaskId: Option[String]                        = scope.asyncTaskId
  def activeCallId: Option[String]                       = scope.activeCallId
  def callStack: Vector[String]                          = scope.callStack
  def trace: Vector[TraceEntry]                          = delta.trace
  def history: Vector[HistoryEntry]                      = delta.history

  /** The callback handlers that run: the set list, or no handler when the list is not set. */
  def callbacks: Vector[CallbackHandler] = services.callbacks.getOrElse(Vector.empty)

  /** The callback metadata: the set record, or an empty record when the metadata is not set. */
  def callbackMetadata: DynamicValue.Record = config.callbackMetadata.getOrElse(DynamicValue.Record.empty)

  /** `true` when a failed module call records a failure trace entry. When the flag is not set, the value is `false`. */
  def captureFailureTraces: Boolean = config.captureFailureTraces.getOrElse(false)

  def withServices(updated: RuntimeServices): RuntimeContext = RuntimeContext.fromParts(updated, config, scope, delta)
  def withConfig(updated  : RuntimeConfig): RuntimeContext   = RuntimeContext.fromParts(services, updated, scope, delta)
  def withScope(updated   : RuntimeScope): RuntimeContext    = RuntimeContext.fromParts(services, config, updated, delta)
  def withDelta(updated   : RuntimeDelta): RuntimeContext    = RuntimeContext.fromParts(services, config, scope, updated)

  /** Set the callback list to `updated`. An empty list is a set value: it turns off the callbacks of the defaults. */
  def withCallbacks(updated: Vector[CallbackHandler]): RuntimeContext =
    withServices(services.copy(callbacks = Some(updated)))
  def withHistory(updated: Vector[HistoryEntry]): RuntimeContext = withDelta(delta.copy(history = updated))
  def appendTrace(entry  : TraceEntry): RuntimeContext           = withDelta(delta.copy(trace = trace :+ entry))
  def appendHistory(entry: HistoryEntry): RuntimeContext         = withDelta(delta.copy(history = history :+ entry))

  /** Compatibility copy over the former flat case-class surface. Each parameter has the type of its field in the
    * partition, as in the flat constructor.
    */
  def copy(
      lm                  : Option[LanguageModelRef]        = this.lm,
      adapter             : Option[AdapterRef]              = this.adapter,
      callbacks           : Option[Vector[CallbackHandler]] = services.callbacks,
      numThreads          : Option[ThreadCount]             = this.numThreads,
      maxErrors           : Option[ErrorLimit]              = this.maxErrors,
      maxHistorySize      : Option[HistoryLimit]            = this.maxHistorySize,
      disableHistory      : Option[Boolean]                 = this.disableHistory,
      trackUsage          : Option[Boolean]                 = this.trackUsage,
      callbackMetadata    : Option[DynamicValue.Record]     = config.callbackMetadata,
      captureFailureTraces: Option[Boolean]                 = config.captureFailureTraces,
      asyncTaskId         : Option[String]                  = this.asyncTaskId,
      callStack           : Vector[String]                  = this.callStack,
      trace               : Vector[TraceEntry]              = this.trace,
      history             : Vector[HistoryEntry]            = this.history,
      interpreterFactory  : Option[ReplInterpreterFactory]  = this.interpreterFactory
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
  /** The flat constructor: one parameter for each field of the four partitions. Each parameter has the type of its
    * field in the partition. Thus a service or a configuration value is `None` when it is not set and `Some` when it is
    * set, also for an empty callback list (`callbacks = Some(Vector.empty)`).
    */
  def apply(
      lm                  : Option[LanguageModelRef]        = None,
      adapter             : Option[AdapterRef]              = None,
      callbacks           : Option[Vector[CallbackHandler]] = None,
      numThreads          : Option[ThreadCount]             = None,
      maxErrors           : Option[ErrorLimit]              = None,
      maxHistorySize      : Option[HistoryLimit]            = None,
      disableHistory      : Option[Boolean]                 = None,
      trackUsage          : Option[Boolean]                 = None,
      callbackMetadata    : Option[DynamicValue.Record]     = None,
      captureFailureTraces: Option[Boolean]                 = None,
      asyncTaskId         : Option[String]                  = None,
      callStack           : Vector[String]                  = Vector.empty,
      trace               : Vector[TraceEntry]              = Vector.empty,
      history             : Vector[HistoryEntry]            = Vector.empty,
      interpreterFactory  : Option[ReplInterpreterFactory]  = None
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
