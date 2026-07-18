package dspy4s.programs.para

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.AndThen
import dspy4s.programs.DynamicPredict
import dspy4s.programs.Identity
import dspy4s.programs.Predictors
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.Prediction

/** PROTOTYPE (step-6 follow-up): the Para-shaped category with the optimizer built into the categorical
  * interface, rather than bolted on per node via ad-hoc [[Predictors]] instances.
  *
  * Background. Algebra 2's optimizer-addressability layer is an instance of the **Para construction** from
  * categorical learning theory ("Backprop as Functor", Fong/Spivak/Tuyeras; "Categorical Foundations of
  * Gradient-Based Learning", Cruttwell/Gavranovic/Ghani/Wilson/Zanasi): a morphism is a pair (parameters,
  * shape), composition tensors the parameters, and reparameterization is the 2-cell layer optimizers act on.
  * dspy4s's parameters are homogeneous (every learnable is a [[DynamicPredict]]), so the parameter tensor
  * degenerates to the free monoid `Vector[DynamicPredict]` and `Predictors.read` / `Predictors.replace` are
  * exactly Para's projection / reparameterization. This file makes that identity a first-class interface.
  *
  * Laws (checked in `ParaCatLawSuite`):
  * {{{
  *   params(id)        = Vector.empty                          // identity is parameter-free
  *   params(f >>> g)   = params(f) ++ params(g)                // composition concatenates parameters
  *   reparam(f, params(f)) = f                                 // reparameterization round-trip (on params/run)
  *   params(reparam(f, ps)) = ps                               // reparameterization writes back
  * }}}
  * plus the Category laws on the packaged carrier (identity, associativity, on the threaded output value).
  *
  * Encoding note. The morphisms of the underlying category are NOT a uniform binary type: they are
  * concretely-typed case classes (`AndThen[I, X, O, A, B]`, ...) precisely so per-node `Predictors` instances
  * can reach their fields (fork 4). `ParaCat` therefore needs **packaged morphisms**: [[Prog]] is the
  * Sigma-type `(Rep <: Module[...], program: Rep, Predictors[Rep])` that hides the concrete representation
  * behind a binary `Prog[I, O]`, carrying its addressability evidence with it. Packaging is the only way to
  * construct a `Prog`, so a program whose type has no `Predictors` instance cannot enter the category (a
  * compile error at `Prog.of`, versus the ambient `Module` world where addressability is best-effort).
  *
  * Honest limitation: for `Product` program types the Mirror-based `Predictors.derived` still resolves and
  * silently contributes `Predictors.empty` for fields without instances; packaging surfaces the evidence but
  * cannot tighten that fallback. That is a `Predictors`-layer issue, tracked separately.
  *
  * Status: prototype only. Nothing in the library consumes this yet; the candidate adoption point is the CIO
  * substrate phase, where the optimizer entry points get touched anyway (see
  * `docs/refactor/algebra-2-program-composition.md`). */
trait ParaCat[Hom[_, _]]:
  /** The Category unit at `A`; parameter-free (`params(id) = Vector.empty`). */
  def id[A]: Hom[A, A]

  extension [A, B](f: Hom[A, B])
    /** Diagrammatic composition (the library's `>>>` order): run `f`, thread its output into `g`. */
    infix def >>>[C](g: Hom[B, C]): Hom[A, C]
    /** Para projection: the morphism's tunable parameters, in stable address order. */
    def params: Vector[DynamicPredict]
    /** Para reparameterization (the 2-cell optimizers act on): the same shape over new parameters. */
    def reparam(ps: Vector[DynamicPredict]): Hom[A, B]

/** A packaged (existentially-typed) addressable program: the hom-set of the Para-shaped category. Bundles a
  * concrete program representation with its [[Predictors]] evidence so the binary type `Prog[I, O]` supports
  * `params` / `reparam` without knowing the representation. Construct only via [[Prog.of]]. */
sealed trait Prog[I, O]:
  type Rep <: Module[TypedCall[I], Prediction[O]]
  val program: Rep
  val addressable: Predictors[Rep]

  /** Run the packaged program (delegates to the module's wrapped `apply`, so tracing/callbacks apply). */
  def apply(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    program.apply(call)

object Prog:

  /** Package a program with its addressability evidence. This is the gate of the category: no
    * `Predictors[F]`, no `Prog`. */
  def of[I, O, F <: Module[TypedCall[I], Prediction[O]]](f: F)(using
      ev: Predictors[F]
  ): Prog[I, O] { type Rep = F } =
    new Prog[I, O]:
      type Rep = F
      val program: F              = f
      val addressable: Predictors[F] = ev

  /** The Para-shaped category instance over packaged programs. Composition packages an [[AndThen]] node with
    * the structurally-derived evidence of its two children, so `params` distributes by construction. */
  given paraCatProg: ParaCat[Prog] with
    def id[A]: Prog[A, A] = Prog.of(Identity[A]())

    extension [A, B](f: Prog[A, B])
      infix def >>>[C](g: Prog[B, C]): Prog[A, C] =
        Prog.of(AndThen[A, B, C, f.Rep, g.Rep](f.program, g.program))(using
          AndThen.andThenPredictors[A, B, C, f.Rep, g.Rep](using f.addressable, g.addressable)
        )

      def params: Vector[DynamicPredict] = f.addressable.read(f.program)

      def reparam(ps: Vector[DynamicPredict]): Prog[A, B] =
        Prog.of(f.addressable.replace(f.program, ps))(using f.addressable)
