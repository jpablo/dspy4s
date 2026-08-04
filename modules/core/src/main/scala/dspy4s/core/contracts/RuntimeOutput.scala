package dspy4s.core.contracts

import dspy4s.core.algebra.Monoid
import dspy4s.core.algebra.{Monad, functionCategory}

import zio.blocks.schema.DynamicValue

import java.time.Instant

/** A single observed module call -- one entry per `Module.apply` (or any other module that records itself). `component`
  * is the module's `moduleName` (`"predict"`, `"chain_of_thought"`, ...), `inputs` is the encoded `ProgramCall.input`
  * record, and `outputs` is the successful result projected by the module's lifecycle observation.
  *
  * Appended to [[RuntimeContext.trace]] when the underlying `ProgramCall.traceEnabled` is `true`. A successful call
  * records `failure = None`. A FAILED call is recorded only when [[RuntimeContext.captureFailureTraces]] is set (e.g.
  * by GEPA's reflective evaluation): `failure` carries the error message and `outputs` carries the raw model response
  * (`raw_response`) when the error is a parse failure. See PORT_GAPS G-12 (P-a).
  */
final case class TraceEntry(
    component: String,
    inputs: DynamicValue.Record,
    outputs: DynamicValue.Record,
    failure: Option[String] = None,
    timestamp: Instant = Instant.now()
)

/** A single observed LM (or module) call's payload, kept in [[RuntimeContext.history]] for inspection and -- for LM
  * entries -- usage accounting. `component` is the module name or LM id; `payload` is the caller-defined snapshot. The
  * history ring is capped by [[RuntimeContext.maxHistorySize]] in `RuntimeEnvironment.appendHistory`.
  */
final case class HistoryEntry(component: String, payload: DynamicValue.Record, timestamp: Instant = Instant.now())

/** Observable output accumulated while a program executes. Concatenation is an ordered monoid: it preserves the
  * execution order of trace and history entries, and [[RuntimeDelta.empty]] is its identity.
  */
final case class RuntimeDelta(
    trace: Vector[TraceEntry] = Vector.empty,
    history: Vector[HistoryEntry] = Vector.empty
) derives CanEqual:
  def combine(that: RuntimeDelta): RuntimeDelta =
    RuntimeDelta(trace ++ that.trace, history ++ that.history)

object RuntimeDelta:
  val empty: RuntimeDelta = RuntimeDelta()

  given monoid: Monoid[RuntimeDelta] with
    def empty: RuntimeDelta                                                      = RuntimeDelta.empty
    extension (a: RuntimeDelta) infix def combine(b: RuntimeDelta): RuntimeDelta = a.combine(b)

/** A value paired with the observable runtime output produced while computing it. This is the writer carrier for
  * isolated execution: `map` preserves the delta and `flatMap` combines deltas in execution order. Its companion's
  * [[dspy4s.core.algebra.Monad]] instance states and exposes those composition laws explicitly.
  */
final case class Executed[+A](value: A, delta: RuntimeDelta) derives CanEqual:
  def map[B](f: A => B): Executed[B] = Executed(f(value), delta)

  def flatMap[B](f: A => Executed[B]): Executed[B] =
    val next = f(value)
    Executed(next.value, delta.combine(next.delta))

object Executed:
  def pure[A](value: A): Executed[A] = Executed(value, RuntimeDelta.empty)

  given monad: Monad[Executed] with
    def pure[A](value: A): Executed[A] = Executed.pure(value)

    def flatMap[A, B](value: Executed[A])(f: A => Executed[B]): Executed[B] = value.flatMap(f)
