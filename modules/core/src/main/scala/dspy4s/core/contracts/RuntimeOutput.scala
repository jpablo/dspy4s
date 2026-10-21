package dspy4s.core.contracts

import dspy4s.algebra.Monoid
import dspy4s.algebra.ScalaMonad

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
  *
  * `predictor` is the [[PredictorKey]] of the predictor that recorded the entry. It is `None` for a module that is not
  * a predictor (a composite such as `ReAct`), and for a module that ran inside a predictor (the `Predict` inside
  * `ChainOfThought`). One call of a predictor thus gives exactly one entry with a key. `withinPredictor` is `true` for
  * an entry of that second kind, so a reader can tell it apart from a module that declares no key.
  */
final case class TraceEntry(
    component      : String,
    inputs         : DynamicValue.Record,
    outputs        : DynamicValue.Record,
    failure        : Option[String]       = None,
    timestamp      : Instant              = Instant.now(),
    predictor      : Option[PredictorKey] = None,
    withinPredictor: Boolean              = false
)

/** The identity of a predictor in a trace: its module name and the structure of its signature.
  *
  * A predictor writes this key into each [[TraceEntry]] that it records. An optimizer reads the key to find the entries
  * of one optimizable leaf. `structure` never holds instructions, because an optimizer rewrites them, so the key is the
  * same for each candidate of one program. Build a key with [[PredictorKey.from]].
  *
  * The key is not an object identity. Two predictors with the same module name and the same signature structure have
  * the same key, and an optimizer rejects such a program.
  */
final case class PredictorKey(moduleName: String, structure: SignatureLayout) derives CanEqual:
  require(structure.instructions.isEmpty, "PredictorKey.structure must not contain instructions")

object PredictorKey:
  /** The key of a predictor with this module name and this layout. The instructions of `layout` are dropped. */
  def from(moduleName: String, layout: SignatureLayout): PredictorKey =
    PredictorKey(moduleName, layout.withInstructions(None))

/** A single observed LM (or module) call's payload, kept in [[RuntimeContext.history]] for inspection and -- for LM
  * entries -- usage accounting. `component` is the module name, or `lm:<id>` for an LM call; `payload` is the
  * caller-defined snapshot. The history ring is capped by [[RuntimeContext.maxHistorySize]] in
  * `RuntimeEnvironment.appendHistory`.
  */
final case class HistoryEntry(component: String, payload: DynamicValue.Record, timestamp: Instant = Instant.now())

/** Observable output accumulated while a program executes. Concatenation is an ordered monoid: it preserves the
  * execution order of trace and history entries, and [[RuntimeDelta.empty]] is its identity.
  */
final case class RuntimeDelta(
    trace  : Vector[TraceEntry]   = Vector.empty,
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
  * [[dspy4s.algebra.ScalaMonad]] instance supplies the categorical endofunctor, unit, and multiplication while deriving
  * the usual Scala operations.
  */
final case class Executed[+A](value: A, delta: RuntimeDelta) derives CanEqual:
  def map[B](f: A => B): Executed[B] = Executed(f(value), delta)

  def flatMap[B](f: A => Executed[B]): Executed[B] =
    val next = f(value)
    Executed(next.value, delta.combine(next.delta))

object Executed:
  def pure[A](value: A): Executed[A] = Executed(value, RuntimeDelta.empty)

  given monad: ScalaMonad[Executed] =
    ScalaMonad.fromComponents(
      [A, B] => (f: A => B) => (value: Executed[A]) => value.map(f),
      [A] => (value: A) => Executed.pure(value),
      [A] => (value: Executed[Executed[A]]) => value.flatMap(identity)
    )
