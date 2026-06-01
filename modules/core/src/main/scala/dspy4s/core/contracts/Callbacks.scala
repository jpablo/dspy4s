package dspy4s.core.contracts

import zio.blocks.schema.DynamicValue

import java.time.Instant
import java.util.UUID

/** Observable lifecycle event emitted around one of the four kinds of work dspy4s wraps in an instrumented scope: a
  * module call, an LM call, an adapter format/parse, or a tool invocation. Each scope produces exactly one
  * `*StartEvent` and one `*EndEvent` (even on failure or exception), correlated by a shared [[callId]].
  *
  * '''Correlation model.''' Every scope is given a fresh [[callId]] when it opens and inherits the enclosing scope's
  * id as its [[parentCallId]]. So a top-level `Predict` call produces a tree:
  *
  * {{{
  *   ModuleStart("predict", callId=m1, parent=None)
  *     AdapterStart("chat", callId=a1, parent=m1)   // format phase
  *     AdapterEnd  ("chat", callId=a1, parent=m1)
  *     LmStart    ("gpt-4", callId=l1, parent=m1)
  *     LmEnd      ("gpt-4", callId=l1, parent=m1)
  *     AdapterStart("chat", callId=a2, parent=m1)   // parse phase
  *     AdapterEnd  ("chat", callId=a2, parent=m1)
  *   ModuleEnd  ("predict", callId=m1, parent=None)
  * }}}
  *
  * Tool invocations inside `ReAct` / `CodeAct` produce nested `ToolStart` / `ToolEnd` pairs under their enclosing
  * module.
  *
  * '''Producer.''' [[dspy4s.core.runtime.CallbackDispatcher]] is the sole producer; its `withModule` / `withLm` /
  * `withAdapter` / `withTool` scopes open a call id, push it as the active id for the thread, emit the start event,
  * run the wrapped thunk, and always emit an end event (with `Left(RuntimeError("callback_dispatch", ...))` if the
  * thunk threw, then rethrows). User code does not emit events directly.
  *
  * '''Consumer.''' Implement [[CallbackHandler]] and register it via
  * [[dspy4s.core.runtime.RuntimeEnvironment.withCallbacks]] or by setting the `callbacks` field on a
  * [[RuntimeContext]]. All registered handlers receive every event in registration order.
  *
  * '''Note on default ids.''' The `callId = UUID.randomUUID().toString` default exists only as a fallback for
  * hand-constructed events. The dispatcher always overrides it with a sequential, prefixed id (`module-N`, `lm-N`,
  * `adapter-N`, `tool-N`) for readability.
  */
sealed trait CallbackEvent extends Product with Serializable:
  /** When the event was created. Set by the dispatcher; useful for latency measurements when pairing a Start with
    * its matching End. */
  def timestamp: Instant

  /** Identifier for this scope. Shared by the Start and End events of the same scope; distinct across sibling
    * scopes. */
  def callId: String

  /** Identifier of the enclosing scope, if any. `None` at the top of a call tree (typically a user-initiated
    * `Predict.run`). Use to reconstruct parent/child relationships across the event stream. */
  def parentCallId: Option[String]

/** Opens a module-level scope (a `Predict`, `ChainOfThought`, `ReAct`, etc.). Paired with [[ModuleEndEvent]]. */
final case class ModuleStartEvent(
    moduleName: String,
    inputs: DynamicValue.Record,
    callId: String = UUID.randomUUID().toString,
    parentCallId: Option[String] = None,
    timestamp: Instant = Instant.now()
)
    extends CallbackEvent

/** Closes a module-level scope opened by a [[ModuleStartEvent]] with the same `callId`. `output` is
  * `Left(DspyError)` on failure (including `RuntimeError("callback_dispatch", ...)` if the body threw). */
final case class ModuleEndEvent(
    moduleName: String,
    output: Either[DspyError, Any],
    callId: String = UUID.randomUUID().toString,
    parentCallId: Option[String] = None,
    timestamp: Instant = Instant.now()
) extends CallbackEvent

/** Opens an LM-call scope around `LanguageModel.call`. The `request` payload is a denormalized snapshot of the
  * request fields the dispatcher chose to surface (`model`, `mode`), not the full `LmRequest` value. Paired with
  * [[LmEndEvent]]. */
final case class LmStartEvent(
    modelId: String,
    request: DynamicValue.Record,
    callId: String = UUID.randomUUID().toString,
    parentCallId: Option[String] = None,
    timestamp: Instant = Instant.now()
)
    extends CallbackEvent

/** Closes an LM-call scope opened by an [[LmStartEvent]] with the same `callId`. `response` carries the
  * `LmResponse` on success or a `DspyError` on failure. */
final case class LmEndEvent(
    modelId: String,
    response: Either[DspyError, Any],
    callId: String = UUID.randomUUID().toString,
    parentCallId: Option[String] = None,
    timestamp: Instant = Instant.now()
) extends CallbackEvent

/** Opens an adapter scope around `Adapter.format` or `Adapter.parse`. The dispatcher reuses the same event types
  * for both phases; the `inputs` map's `"phase"` key disambiguates (`"format"` / `"parse"`). Paired with
  * [[AdapterEndEvent]]. */
final case class AdapterStartEvent(
    adapterName: String,
    inputs: DynamicValue.Record,
    callId: String = UUID.randomUUID().toString,
    parentCallId: Option[String] = None,
    timestamp: Instant = Instant.now()
)
    extends CallbackEvent

/** Closes an adapter scope opened by an [[AdapterStartEvent]] with the same `callId`. */
final case class AdapterEndEvent(
    adapterName: String,
    output: Either[DspyError, Any],
    callId: String = UUID.randomUUID().toString,
    parentCallId: Option[String] = None,
    timestamp: Instant = Instant.now()
) extends CallbackEvent

/** Opens a tool-invocation scope. Emitted by `ReAct`, `CodeAct`, and other composite programs when they dispatch a
  * `ToolFunction`. Paired with [[ToolEndEvent]]. */
final case class ToolStartEvent(
    toolName: String,
    args: DynamicValue.Record,
    callId: String = UUID.randomUUID().toString,
    parentCallId: Option[String] = None,
    timestamp: Instant = Instant.now()
) extends CallbackEvent

/** Closes a tool-invocation scope opened by a [[ToolStartEvent]] with the same `callId`. */
final case class ToolEndEvent(
    toolName: String,
    output: Either[DspyError, DynamicValue],
    callId: String = UUID.randomUUID().toString,
    parentCallId: Option[String] = None,
    timestamp: Instant = Instant.now()
) extends CallbackEvent

/** Consumer side of the [[CallbackEvent]] stream. Implementations receive every event from every scope opened in
  * the current `RuntimeContext`, in registration order.
  *
  * The `RuntimeContext` `using` parameter lets a handler read live settings (e.g. the active LM, the call stack via
  * [[dspy4s.core.runtime.ActivePredictContext]]) while reacting. It is the same context that wrapped the
  * originating scope.
  *
  * Handlers run on the producer thread inline with the work being observed. Two consequences:
  *
  *   - Throwing from `onEvent` will propagate into the dispatcher and be reported as
  *     `Left(RuntimeError("callback_dispatch", ...))` on the matching End event. Don't throw unless you mean to
  *     abort the surrounding scope.
  *   - Blocking work in `onEvent` blocks the surrounding scope. Hand off to a queue or async sink if the handler
  *     does I/O.
  *
  * `StatusStreamingCallback` (in the streaming module) is the canonical example: it bridges these events onto a
  * [[dspy4s.streaming.StreamingQueue]] that the consumer drains elsewhere.
  */
trait CallbackHandler:
  def onEvent(event: CallbackEvent)(using RuntimeContext): Unit

object CallbackHandler:
  /** Discards every event. [[RuntimeContext.callbacks]] defaults to an empty `Vector`, so this instance is offered
    * for callers that explicitly want a placeholder handler (tests, dependency injection where `null` is
    * undesired). */
  val noop: CallbackHandler = new CallbackHandler:
    override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = ()
