package dspy4s.programs

import dspy4s.programs.predictors.*
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Monoid
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.updated
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TransparentModule
import dspy4s.programs.contracts.ProgramCall
import dspy4s.typed.Prediction
import zio.blocks.schema.DynamicValue

/** `Mode`: a NON-LEARNABLE middleware over a typed program (Algebra 2's `mode`; see
  * `docs/refactor/algebra-2-program-composition.md`). It rewrites the per-call controls — the provider `config` bag
  * (model / temperature / any provider knob), plus `rolloutId` / `traceEnabled` — before delegating to the wrapped
  * program. It introduces NO learnable predict of its own; that restriction is what lets it stay closure-shaped.
  * Anything with a learnable sub-generation (synthesis, comparison, critique) is a dedicated combinator that holds the
  * predict as a field (`selectBest`, `feedback`, `MultiChainComparison`), never a mode (fork 4).
  *
  * Two separate facts, often conflated:
  *
  *   1. '''`Mode` is a monoid''' under `++` (left-to-right control transform) with unit [[Mode.id]] — concretely the
  *      endomorphism monoid on `Controls` (`++` = function composition, `Mode.id` = the identity transform), witnessed
  *      by the [[Mode.monoid]] `given Monoid[Mode]` (`combine` = `++`, `empty` = `id`). Its laws come from the
  *      [[dspy4s.core.contracts.Monoid]] trait and hold up to '''extensional equality''' of the wrapped transform (`m1
  *      ≡ m2` iff `∀ c. m1.transform(c) == m2.transform(c)`), NOT the case class's structural `==` — `++` allocates a
  *      fresh closure each time, so `==` (reference equality on the function field) would reject even `Mode.id ++ m ≡
  *      m`. `ModeLawSuite` executes the trait's laws through the instance under that extensional equality.
  *      Non-commutative, as an endomorphism monoid is (last write wins: `temperature(0.5)
  *      ++ temperature(0.9)` sets 0.9).
  *
  * 2. '''`mode` is a monoid homomorphism''' from that monoid into program endomorphisms: `mode(m1 ++ m2) = mode(m1) ∘
  * mode(m2)` and `mode(Mode.id) = id` (a monoid ACTION on programs, distinct from fact 1). This holds on the result
  * and, because `mode` is trace-transparent, on the trace too; it is checked observationally via the recorder in
  * `ModeLawSuite`.
  *
  * Scope: `mode` covers pure control transforms. Execution-wrapping modes (retry, pre/post hooks) are the additive
  * extension — not built until a consumer needs them.
  */
final case class Mode(transform: Mode.Controls => Mode.Controls):
  /** Sequence two modes: apply `this` to the controls, then `next` (left-to-right). */
  infix def ++(next: Mode): Mode = Mode(transform andThen next.transform)

object Mode:
  /** The per-call controls a mode may rewrite — everything on [[ProgramCall]] except the typed input. */
  final case class Controls(config: DynamicValue.Record, traceEnabled: Boolean, rolloutId: Option[Int])

  /** The monoid unit: the identity control transform. */
  val id: Mode = Mode(identity)

  /** Upsert a provider-config key — the building block for model / temperature / any provider knob. */
  def setConfig(key: String, value: DynamicValue): Mode =
    Mode(controls => controls.copy(config = controls.config.updated(key, value)))

  /** Set the provider sampling temperature. */
  def temperature(value: Double): Mode = setConfig("temperature", DynamicValues.fromAny(value))

  /** Swap the provider model. */
  def model(name: String): Mode = setConfig("model", DynamicValues.fromAny(name))

  /** Set the framework cache-busting rolloutId. */
  def rolloutId(value: Int): Mode = Mode(controls => controls.copy(rolloutId = Some(value)))

  /** `Mode` as the endomorphism monoid on `Controls`: `combine` = `++` (compose the control transforms), `empty` =
    * [[Mode.id]]. The associativity / identity laws come from [[dspy4s.core.contracts.Monoid]] and are executed by
    * `ModeLawSuite` under extensional equality of the transform (see fact 1 in the class scaladoc). Distinct from the
    * mode-ACTION homomorphism (`mode(m1 ++ m2) = mode(m1) ∘ mode(m2)`), which is a program-level equation observed via
    * the recorder in that suite.
    */
  given monoid: Monoid[Mode] with
    def empty: Mode = Mode.id
    extension (a: Mode) infix def combine(b: Mode): Mode = a ++ b

/** `mode(m)(p)`: run `p` with its per-call controls rewritten by `m`. Lifecycle-transparent — it records no callback,
  * trace, or history event of its own, so a chain of modes collapses to the wrapped program's single lifecycle scope
  * and the monoid law holds on runtime observations.
  */
final case class Moded[I, O, P <: Module[I, O]](mode: Mode, program: P)
    extends TransparentModule[I, O]:
  override val moduleName: String = s"mode(${program.moduleName})"

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    val controls = mode.transform(Mode.Controls(call.config, call.traceEnabled, call.rolloutId))
    program.apply(call.copy(
      config       = controls.config,
      traceEnabled = controls.traceEnabled,
      rolloutId    = controls.rolloutId
    ))

object Moded:
  /** `mode` is non-learnable, so addressability passes straight through to the wrapped program (fork 4). */
  given modedPredictorTraversal[I, O, P <: Module[I, O]](using
      inner: PredictorTraversal[P]
  ): PredictorTraversal[Moded[I, O, P]] with
    def inspect(program: Moded[I, O, P]): Vector[PredictorView] = inner.inspect(program.program)
    def replace(program: Moded[I, O, P], updates: Vector[OptimizableParameters]): Moded[I, O, P] =
      program.copy(program = inner.replace(program.program, updates))
    override def inspectNamed(program: Moded[I, O, P]): Vector[(String, PredictorView)] =
      inner.inspectNamed(program.program)
