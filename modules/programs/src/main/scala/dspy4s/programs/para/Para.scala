package dspy4s.programs.para

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ValidationError
import dspy4s.programs.AndThen
import dspy4s.programs.DynamicPredict
import dspy4s.programs.Identity
import dspy4s.programs.Predict
import dspy4s.programs.Predictors
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.Prediction
import zio.blocks.schema.DynamicValue

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
  * Sigma-type `(Rep <: Module[...], program: Rep, Predictors[Rep], decodeInput)` that hides the concrete
  * representation behind a binary `Prog[I, O]`, carrying its addressability AND evaluation evidence with it.
  * Packaging is the only way to construct a `Prog`, so a program whose type has no `Predictors` instance
  * cannot enter the category (a compile error at `Prog.of`, versus the ambient `Module` world where
  * addressability is best-effort).
  *
  * Evaluation capability (the `ParaCompile` experiment's conclusion, closed here). Optimizers need more than
  * the Para structure: they run candidates on data-bag [[dspy4s.core.contracts.Example]]s, which requires
  * decoding a `DynamicValue.Record` into the typed input `I`. [[Prog]] therefore also packages
  * `decodeInput : DynamicValue.Record => Either[DspyError, I]`, captured at `Prog.of` time (from the
  * program's signature via [[ProgInput]], or supplied explicitly) and THREADED through composition (`f >>> g`
  * keeps `f`'s decoder, the composite's input being `f`'s input). Together with the packaged `Predictors`
  * this makes `Prog[I, O]` itself a first-class optimizable program: see [[Prog.progPredictors]] and the
  * uniform `Runnable[Prog[I, O]]` in `dspy4s.optimize.para.ParaCompile`. Composed pipelines get evaluation
  * for free, where bare user composites must hand-write a `Runnable` today.
  *
  * Honest wrinkles, recorded rather than hidden:
  *   - **`id` has no input decoder.** Nothing can decode an arbitrary `A` from a record, so `ParaCat.id`
  *     carries a failing decoder. On the run/params observations the unit laws hold, but on the evaluation
  *     observation `id >>> p` degrades (it threads id's failing decoder). The principled fix is a
  *     constrained category over CODEC-EQUIPPED OBJECTS (the `CategoryTC[P[_], Hom]` object-constraint slot,
  *     with `P[A]` = "A decodes from a record"), deferred to full adoption.
  *   - For `Product` program types the Mirror-based `Predictors.derived` still resolves and silently
  *     contributes empty for fields without instances; packaging surfaces the evidence but cannot tighten
  *     that fallback (a `Predictors`-layer issue, tracked separately).
  *
  * Status: prototype. The optimizer entry point over `Prog` lives in `dspy4s.optimize.para.ParaCompile`;
  * promotion to the public API is deferred to the CIO substrate phase. */
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

/** How to obtain a record-to-`I` input decoder from a program's own structure at packaging time. The
  * `programs`-module home for the per-type knowledge `dspy4s.optimize.Runnable` keeps per optimizer target
  * (decode via the signature's `inputShape`). Prototype scope: only the [[Predict]] instance ships; the
  * other typed leaves (`ChainOfThought`, `ReAct`, `CodeAct`) are the same one-liner over their signatures.
  * Composites never need an instance: composition threads the first leg's packaged decoder. */
trait ProgInput[F, I]:
  def decoder(program: F): DynamicValue.Record => Either[DspyError, I]

object ProgInput:
  given forPredict[I, O]: ProgInput[Predict[I, O], I] with
    def decoder(program: Predict[I, O]): DynamicValue.Record => Either[DspyError, I] =
      program.signature.inputShape.decode

/** A packaged (existentially-typed) addressable program: the hom-set of the Para-shaped category. Bundles a
  * concrete program representation with its [[Predictors]] evidence and its input decoder, so the binary
  * type `Prog[I, O]` supports `params` / `reparam` AND record-based evaluation without knowing the
  * representation. Construct only via [[Prog.of]]. */
sealed trait Prog[I, O]:
  type Rep <: Module[TypedCall[I], Prediction[O]]
  val program: Rep
  val addressable: Predictors[Rep]

  /** Decode a data-bag record into the typed input `I` (the packaged evaluation capability; see the
    * scaladoc on [[ParaCat]]). Captured at packaging time; threaded through composition. */
  val decodeInput: DynamicValue.Record => Either[DspyError, I]

  /** Run the packaged program (delegates to the module's wrapped `apply`, so tracing/callbacks apply). */
  def apply(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    program.apply(call)

object Prog:

  /** Package a program with its addressability evidence and an explicit input decoder. This is the gate of
    * the category: no `Predictors[F]`, no `Prog`. */
  def of[I, O, F <: Module[TypedCall[I], Prediction[O]]](
      f: F,
      decode: DynamicValue.Record => Either[DspyError, I]
  )(using ev: Predictors[F]): Prog[I, O] { type Rep = F } =
    new Prog[I, O]:
      type Rep = F
      val program: F                 = f
      val addressable: Predictors[F] = ev
      val decodeInput: DynamicValue.Record => Either[DspyError, I] = decode

  /** Package a program whose input decoder is derivable from its own structure (a [[ProgInput]] instance,
    * e.g. any `Predict[I, O]` via its signature). */
  def of[I, O, F <: Module[TypedCall[I], Prediction[O]]](f: F)(using
      ev: Predictors[F],
      codec: ProgInput[F, I]
  ): Prog[I, O] { type Rep = F } =
    of(f, codec.decoder(f))

  /** `Prog[I, O]` as a first-class optimizable program: [[Predictors]] delegates to the packaged evidence
    * (`read` = the Para projection; `replace` = reparameterization, preserving shape and decoder). With the
    * uniform `Runnable[Prog[I, O]]` (in `dspy4s.optimize.para.ParaCompile`), any `Teleprompter` can take a
    * packaged program directly, e.g. `new COPRO[Prog[I, O]](config)`. */
  given progPredictors[I, O]: Predictors[Prog[I, O]] with
    def read(program: Prog[I, O]): Vector[DynamicPredict] =
      program.addressable.read(program.program)

    def replace(program: Prog[I, O], updates: Vector[DynamicPredict]): Prog[I, O] =
      Prog.of(program.addressable.replace(program.program, updates), program.decodeInput)(using
        program.addressable
      )

    override def readNamed(program: Prog[I, O]): Vector[(String, DynamicPredict)] =
      program.addressable.readNamed(program.program)

  /** The Para-shaped category instance over packaged programs. Composition packages an [[AndThen]] node with
    * the structurally-derived evidence of its two children (so `params` distributes by construction) and
    * threads the FIRST leg's input decoder (the composite's input is the first leg's input). */
  given paraCatProg: ParaCat[Prog] with
    def id[A]: Prog[A, A] =
      // No decoder exists for an arbitrary A; see the "honest wrinkles" note on the ParaCat scaladoc.
      Prog.of(
        Identity[A](),
        _ =>
          Left(ValidationError(
            "id[A] carries no input decoder (an arbitrary A cannot be decoded from a record); " +
              "record-based evaluation needs a concrete packaged program at the head of the pipeline"
          ))
      )

    extension [A, B](f: Prog[A, B])
      infix def >>>[C](g: Prog[B, C]): Prog[A, C] =
        Prog.of(AndThen[A, B, C, f.Rep, g.Rep](f.program, g.program), f.decodeInput)(using
          AndThen.andThenPredictors[A, B, C, f.Rep, g.Rep](using f.addressable, g.addressable)
        )

      def params: Vector[DynamicPredict] = f.addressable.read(f.program)

      def reparam(ps: Vector[DynamicPredict]): Prog[A, B] =
        Prog.of(f.addressable.replace(f.program, ps), f.decodeInput)(using f.addressable)
