package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.updated
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.Prediction
import zio.blocks.schema.DynamicValue

/** `Mode`: a NON-LEARNABLE middleware over a typed program (Algebra 2's `mode`; see
  * `docs/refactor/algebra-2-program-composition.md`). It rewrites the per-call controls — the provider `config`
  * bag (model / temperature / any provider knob), plus `rolloutId` / `traceEnabled` — before delegating to the
  * wrapped program. It introduces NO learnable predict of its own; that restriction is what lets it stay
  * closure-shaped. Anything with a learnable sub-generation (synthesis, comparison, critique) is a dedicated
  * combinator that holds the predict as a field (`selectBest`, `feedback`, `MultiChainComparison`), never a mode
  * (fork 4).
  *
  * Modes form a **monoid** under `++` (left-to-right control transform) with unit [[Mode.id]]:
  * `mode(m1 ++ m2) = mode(m1) ∘ mode(m2)` and `mode(Mode.id) = id` (on the result; `mode` is trace-transparent,
  * so the law holds on the trace too).
  *
  * Scope: `mode` covers pure control transforms. Execution-wrapping modes (retry, pre/post hooks) are the
  * additive extension — not built until a consumer needs them. */
final case class Mode(transform: Mode.Controls => Mode.Controls):
  /** Sequence two modes: apply `this` to the controls, then `next` (left-to-right). */
  infix def ++(next: Mode): Mode = Mode(transform andThen next.transform)

object Mode:
  /** The per-call controls a mode may rewrite — everything on [[TypedCall]] except the typed input. */
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

/** `mode(m)(p)`: run `p` with its per-call controls rewritten by `m`. Trace-transparent — it records no trace
  * entry of its own (`callTraceEnabled = false`), so a chain of modes collapses to the wrapped program's single
  * event and the monoid law holds on the trace; the wrapped program records its own event as usual. */
final case class Moded[I, O, P <: Module[TypedCall[I], Prediction[O]]](mode: Mode, program: P)
    extends Module[TypedCall[I], Prediction[O]]:
  override val moduleName: String = s"mode(${program.moduleName})"
  override protected def callInputs(call: TypedCall[I]): DynamicValue.Record       = DynamicValue.Record.empty
  override protected def callTraceEnabled(call: TypedCall[I]): Boolean             = false
  override protected def tracePayload(prediction: Prediction[O]): DynamicValue.Record = prediction.raw.values

  override protected def forward(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    val controls = mode.transform(Mode.Controls(call.config, call.traceEnabled, call.rolloutId))
    program.apply(call.copy(
      config       = controls.config,
      traceEnabled = controls.traceEnabled,
      rolloutId    = controls.rolloutId
    ))

object Moded:
  /** `mode` is non-learnable, so addressability passes straight through to the wrapped program (fork 4). */
  given modedPredictors[I, O, P <: Module[TypedCall[I], Prediction[O]]](using
      inner: Predictors[P]
  ): Predictors[Moded[I, O, P]] with
    def read(program: Moded[I, O, P]): Vector[DynamicPredict] = inner.read(program.program)
    def replace(program: Moded[I, O, P], updates: Vector[DynamicPredict]): Moded[I, O, P] =
      program.copy(program = inner.replace(program.program, updates))
    override def readNamed(program: Moded[I, O, P]): Vector[(String, DynamicPredict)] =
      inner.readNamed(program.program)
