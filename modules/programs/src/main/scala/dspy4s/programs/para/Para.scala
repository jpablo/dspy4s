package dspy4s.programs.para

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.IsEq
import dspy4s.core.contracts.Law
import dspy4s.core.contracts.Monoid
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.<->
import dspy4s.programs.AndThen
import dspy4s.programs.Both
import dspy4s.programs.Copy
import dspy4s.programs.Discard
import dspy4s.programs.DynamicPredict
import dspy4s.programs.Identity
import dspy4s.programs.Predict
import dspy4s.programs.Predictors
import dspy4s.programs.Swap
import dspy4s.programs.Tensor
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.Prediction
import dspy4s.typed.Shape
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.Schema

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
  * Laws are stated ON the structures as `@Law` methods returning [[IsEq]] (the math-with-scala statement
  * style) and EXECUTED by `ParaCatLawSuite` under the observation honest for each law: structural `==` for
  * parameter vectors, observational equality (run output / params / decode) for morphisms. The categorical
  * inventory:
  *
  *   - [[Cat]]: the base category (id at codec-equipped objects, `>>>`), instantiated by [[Prog]] AND by the
  *     [[paramsDeloop]] delooping of the parameter monoid.
  *   - [[ParaCat]]: [[Cat]] plus the Para operations (`params` / `reparam`) and the fan-out `parallel`.
  *   - [[ReadFunctor]]: `params` as a FUNCTOR VALUE `Prog --> B(Vector[DynamicPredict])`; its functor laws
  *     are precisely the Para projection laws.
  *
  * Encoding note. The morphisms of the underlying category are NOT a uniform binary type: they are
  * concretely-typed case classes (`AndThen[I, X, O, A, B]`, ...) precisely so per-node `Predictors` instances
  * can reach their fields (fork 4). `ParaCat` therefore needs **packaged morphisms**: [[Prog]] is the
  * Sigma-type `(Rep <: Module[...], program: Rep, Predictors[Rep], decodeInput)` that hides the concrete
  * representation behind a binary `Prog[I, O]`, carrying its addressability AND evaluation evidence with it.
  * Packaging is the only way to construct a `Prog`, so a program whose type has no `Predictors` instance
  * cannot enter the category (a compile error at `Prog.of`).
  *
  * Evaluation capability. Optimizers run candidates on data-bag [[dspy4s.core.contracts.Example]]s, which
  * requires decoding a `DynamicValue.Record` into the typed input `I`. [[Prog]] therefore also packages
  * `decodeInput`, captured at `Prog.of` time (from the program's signature via [[ProgInput]], from the input
  * type's [[RecordCodec]], or supplied explicitly) and THREADED through composition (`f >>> g` keeps `f`'s
  * decoder). Together with the packaged `Predictors` this makes `Prog[I, O]` a first-class optimizable
  * program: see [[Prog.progPredictors]] and `Runnable[Prog[I, O]]` in `dspy4s.optimize.para.ParaCompile`.
  *
  * Codec-equipped objects (the `CategoryTC[P[_], Hom]` object-constraint slot). [[Cat]] is parameterized by
  * an object constraint `P[_]`, instantiated for [[Prog]] at `P = RecordCodec` ("the object decodes from a
  * record"). Unlike the blanket Ok-style constrained category, the constraint appears ONLY where evaluation
  * evidence must be SYNTHESIZED rather than threaded: `id[A: P]` builds its decoder from the object's codec,
  * while `>>>` needs nothing (both legs already carry packaged decoders). Over codec-equipped objects the
  * structure is a genuine category (unit laws hold on all observations under coherent packaging, which
  * [[RecordCodec.fromSchema]] guarantees for signature-derived programs by sharing `Signature.derived`'s
  * decode path); morphisms touching non-codec objects still compose, but `id` does not exist there (a
  * semicategory), pinned as a compile error in the law suite.
  *
  * `parallel` and the copy non-law. [[ParaCat.parallel]] runs BOTH legs on the SAME input and tuples the
  * outputs: categorically the fan-out (pairing) of copy-then-tensor, the CD/Markov-category shape, NOT a
  * plain monoidal tensor. Copy is deliberately NOT natural here: for an effectful `h`,
  * `h >>> parallel(f, g)` runs `h` once (shared) while `parallel(h >>> f, h >>> g)` runs it twice, and the
  * two differ both distributionally and in `params` (the optimizer sees `h` twice on the right — genuinely a
  * different program). The law suite pins this as executable evidence, not a footnote.
  *
  * Honest limitation retained: for `Product` program types the Mirror-based `Predictors.derived` still
  * resolves and silently contributes empty for fields without instances; packaging surfaces the evidence but
  * cannot tighten that fallback (a `Predictors`-layer issue, tracked separately).
  *
  * Status: prototype. The optimizer entry point over `Prog` lives in `dspy4s.optimize.para.ParaCompile`;
  * promotion to the public API is deferred to the CIO substrate phase. */
trait Cat[P[_], Hom[_, _]]:
  /** The Category unit at a `P`-equipped object (for [[Prog]], `P` synthesizes the evaluation evidence). */
  def id[A: P]: Hom[A, A]

  extension [A, B](f: Hom[A, B])
    /** Diagrammatic composition: run `f`, thread its output into `g`. Not object-constrained: packaged
      * morphisms carry their own evidence. */
    infix def >>>[C](g: Hom[B, C]): Hom[A, C]

  @Law("left unit")
  def identityLeft[A: P, B](f: Hom[A, B]): IsEq[Hom[A, B]] =
    (id[A] >>> f) <-> f

  @Law("right unit")
  def identityRight[A, B: P](f: Hom[A, B]): IsEq[Hom[A, B]] =
    (f >>> id[B]) <-> f

  @Law("associativity")
  def associativity[A, B, C, D](f: Hom[A, B], g: Hom[B, C], h: Hom[C, D]): IsEq[Hom[A, D]] =
    ((f >>> g) >>> h) <-> (f >>> (g >>> h))

/** [[Cat]] plus the Para structure (projection / reparameterization) and the fan-out `parallel`. */
trait ParaCat[P[_], Hom[_, _]] extends Cat[P, Hom]:
  extension [A, B](f: Hom[A, B])
    /** Para projection: the morphism's tunable parameters, in stable address order. */
    def params: Vector[DynamicPredict]
    /** Para reparameterization (the 2-cell optimizers act on): the same shape over new parameters. */
    def reparam(ps: Vector[DynamicPredict]): Hom[A, B]

  /** Fan-out (pairing): run both legs on the SAME input, tuple the outputs. Copy-then-tensor fused (the
    * CD/Markov shape); copy is NOT natural for effectful morphisms — see the file scaladoc and the law
    * suite's counterexample. */
  def parallel[I, A, B](f: Hom[I, A], g: Hom[I, B]): Hom[I, (A, B)]

  @Law("the identity is parameter-free")
  def paramsId[A: P]: IsEq[Vector[DynamicPredict]] =
    id[A].params <-> Vector.empty

  @Law("composition concatenates parameters")
  def paramsCompose[A, B, C](f: Hom[A, B], g: Hom[B, C]): IsEq[Vector[DynamicPredict]] =
    (f >>> g).params <-> (f.params ++ g.params)

  @Law("fan-out concatenates parameters")
  def paramsParallel[I, A, B](f: Hom[I, A], g: Hom[I, B]): IsEq[Vector[DynamicPredict]] =
    parallel(f, g).params <-> (f.params ++ g.params)

  @Law("reparameterization round-trip")
  def reparamRoundTrip[A, B](f: Hom[A, B]): IsEq[Vector[DynamicPredict]] =
    f.reparam(f.params).params <-> f.params

  @Law("reparameterization writes back (arity-matched ps)")
  def reparamWriteBack[A, B](f: Hom[A, B], ps: Vector[DynamicPredict]): IsEq[Vector[DynamicPredict]] =
    f.reparam(ps).params <-> ps

/** The trivial object constraint, for categories whose Hom ignores its object indices (the delooping). */
type AnyObject[A] = DummyImplicit

/** The '''parameter monoid''': `Vector[DynamicPredict]` under concatenation — the free monoid on the
  * homogeneous Para parameters, and the codomain of the [[Predictors]] homomorphism. Stated as an explicit
  * `given Monoid[Vector[DynamicPredict]]` so the delooping below is literally "this monoid, delooped" rather
  * than an ad-hoc `Cat` whose composition happens to be `++`. */
given paramsMonoid: Monoid[Vector[DynamicPredict]] with
  def empty: Vector[DynamicPredict] = Vector.empty
  extension (a: Vector[DynamicPredict])
    infix def combine(b: Vector[DynamicPredict]): Vector[DynamicPredict] = a ++ b

/** The delooping B(M) of ANY monoid M: the one-object category whose morphisms are the monoid's elements,
  * composition is `combine`, identity is `empty` — the classic "a monoid is a one-object category". A plain
  * `def` (not a `given`) so it does not compete with more specific `Cat` instances during resolution; the
  * parameter-monoid delooping is exposed as the [[paramsDeloop]] `given` below. */
type Delooped[M] = [A, B] =>> M

def delooping[M](using M: Monoid[M]): Cat[AnyObject, Delooped[M]] =
  new Cat[AnyObject, Delooped[M]]:
    def id[A: AnyObject]: M = M.empty
    extension [A, B](f: M) infix def >>>[C](g: M): M = f.combine(g)

/** The delooping of the [[paramsMonoid]] — one object, morphisms are parameter vectors, composition is `++`,
  * id is the empty vector. The target of [[ReadFunctor]]. */
type ParamsHom = Delooped[Vector[DynamicPredict]]

given paramsDeloop: Cat[AnyObject, ParamsHom] = delooping[Vector[DynamicPredict]]

/** The plain (un-packaged) program morphisms — the carrier of the CD/Markov structure. Unlike `Prog` these
  * carry no optimizer / decoder evidence (the CD laws are observational on outputs, not about `params`), which
  * is also why the tensor lives here and not on `ParaCat`. */
type ModuleHom[I, O] = Module[TypedCall[I], Prediction[O]]

/** A **CD category** (copy-discard): a symmetric monoidal category in which every object carries a commutative
  * comonoid — copy `Δ` and discard `!` — with NEITHER copy nor discard required natural. dspy4s's program
  * category is one (see `docs/refactor/algebra.md`, "The program category is a Markov category").
  *
  * Equality-dependence, stated honestly: under OUTPUT-observational equality (all maps `A → Unit` collapse to
  * "output `()`") discard becomes natural and the unit terminal, so the structure is a **Markov** category
  * under that observation; on the nose (observing cost / trace / effects) it is only CD. Copy is natural
  * exactly on the **deterministic** morphisms — that is the classifier [[copyNaturality]], whose failure for an
  * effect-observing morphism is the non-degeneracy witness that the category is properly Markov, not cartesian.
  *
  * Scope (deliberate). The positive laws that need no unitors/associators are stated as `@Law` and executed by
  * `CDCategoryLawSuite` under output-observational equality. The comonoid **counit** / **coassociativity** and
  * the monoidal **pentagon/triangle** coherence require unitor/associator morphisms and are DEFERRED (no
  * consumer). Fixed over the trivial object constraint `AnyObject` (every type is an object), so `id` needs no
  * evidence; instantiated only over [[ModuleHom]], not `Prog` (the tensor's `(A, B)` input has no single-record
  * decoder). */
trait CDCategory[Hom[_, _]] extends Cat[AnyObject, Hom]:
  /** Monoidal tensor `⊗` on morphisms. */
  def tensor[A, B, C, D](f: Hom[A, C], g: Hom[B, D]): Hom[(A, B), (C, D)]
  /** Symmetry `σ`. */
  def swap[A, B]: Hom[(A, B), (B, A)]
  /** Comonoid copy `Δ`. */
  def copy[A]: Hom[A, (A, A)]
  /** Comonoid counit / discard `!`. */
  def discard[A]: Hom[A, Unit]

  @Law("tensor is a bifunctor (interchange)")
  def tensorInterchange[A, B, C, D, E, F2](
      f1: Hom[A, C], g1: Hom[B, D], f2: Hom[C, E], g2: Hom[D, F2]
  ): IsEq[Hom[(A, B), (E, F2)]] =
    (tensor(f1, g1) >>> tensor(f2, g2)) <-> tensor(f1 >>> f2, g1 >>> g2)

  @Law("tensor preserves identities")
  def tensorIdentity[A, B]: IsEq[Hom[(A, B), (A, B)]] =
    tensor(id[A], id[B]) <-> id[(A, B)]

  @Law("swap is involutive (symmetry)")
  def swapInvolution[A, B]: IsEq[Hom[(A, B), (A, B)]] =
    (swap[A, B] >>> swap[B, A]) <-> id[(A, B)]

  @Law("copy is cocommutative")
  def cocommutativity[A]: IsEq[Hom[A, (A, A)]] =
    (copy[A] >>> swap[A, A]) <-> copy[A]

  @Law("discard is natural (unit terminal) — under output-observational equality")
  def discardNatural[A, B](f: Hom[A, B]): IsEq[Hom[A, Unit]] =
    (f >>> discard[B]) <-> discard[A]

  /** NOT a `@Law`: the determinism **classifier**. `copy` commutes with `f` iff `f` is deterministic — holds
    * for a pure `f` (checked positively) and FAILS for an effect-observing `f` (the non-degeneracy witness).
    * `f >>> copy` runs `f` once and duplicates; `copy >>> tensor(f, f)` runs it twice. */
  def copyNaturality[A, B](f: Hom[A, B]): IsEq[Hom[A, (B, B)]] =
    (f >>> copy[B]) <-> (copy[A] >>> tensor(f, f))

/** dspy4s's program category as a CD category, over the plain [[ModuleHom]] carrier. `id`/`>>>` reuse the
  * Category combinators; `tensor`/`copy`/`discard`/`swap` are the `Compose` generators, typed abstractly (the
  * CD laws are observational, so per-node `Predictors` evidence is not needed here). */
given cdProgram: CDCategory[ModuleHom] with
  def id[A: AnyObject]: ModuleHom[A, A] = Identity[A]()

  extension [A, B](f: ModuleHom[A, B])
    infix def >>>[C](g: ModuleHom[B, C]): ModuleHom[A, C] =
      AndThen[A, B, C, ModuleHom[A, B], ModuleHom[B, C]](f, g)

  def tensor[A, B, C, D](f: ModuleHom[A, C], g: ModuleHom[B, D]): ModuleHom[(A, B), (C, D)] =
    Tensor[A, B, C, D, ModuleHom[A, C], ModuleHom[B, D]](f, g)

  def swap[A, B]: ModuleHom[(A, B), (B, A)] = Swap[A, B]()
  def copy[A]: ModuleHom[A, (A, A)]         = Copy[A]()
  def discard[A]: ModuleHom[A, Unit]        = Discard[A]()

/** A functor between two `Hom`-indexed categories, identity-on-objects (the delooping target ignores objects,
  * so an object map would be inert). Carries the functor laws ON the trait — like `Cat` / `ParaCat` / `Monoid`
  * / `CDCategory` — stated against the source and target `Cat` instances it is given (the math-with-scala
  * `Functor[F, Source: Category, Target: Category]` shape). `PS` / `PT` are the two categories' object
  * constraints (see `Cat`); a law over an object `A` needs both `PS[A]` and `PT[A]` so it can name `id` in
  * each category. */
trait CatFunctor[PS[_], Source[_, _], PT[_], Target[_, _]](using
    source: Cat[PS, Source],
    target: Cat[PT, Target]
):
  def map[A, B](f: Source[A, B]): Target[A, B]

  @Law("functor preserves identities")
  def identities[A: PS: PT]: IsEq[Target[A, A]] =
    map(source.id[A]) <-> target.id[A]

  @Law("functor preserves composition")
  def composition[A, B, C](f: Source[A, B], g: Source[B, C]): IsEq[Target[A, C]] =
    map(f >>> g) <-> (map(f) >>> map(g))

/** The object constraint of the Para category over [[Prog]] (the `CategoryTC` `P[_]` slot): `A` decodes
  * from a data-bag record. Supplies `id`'s decoder and (via [[ProgInput]]'s low-priority instance) coherent
  * default packaging for any typed program whose input type is codec-equipped. */
trait RecordCodec[A]:
  def decode(record: DynamicValue.Record): Either[DspyError, A]

object RecordCodec:
  /** Named carrier (precedent: `Predictors.DerivedPredictors`) so the given below does not mint an anonymous
    * class per summon site. */
  private final class ShapeBacked[A](shape: Shape[A]) extends RecordCodec[A]:
    def decode(record: DynamicValue.Record): Either[DspyError, A] = shape.decode(record)

  /** Any product with a zio-blocks `Schema`, decoded via the typed layer's INPUT `Shape` — the SAME
    * `Shape.derivedWithRole(FieldRole.Input)` path `Signature.derived` gives `inputShape`, so codec-derived
    * decoders cohere definitionally with signature-packaged programs (the left-unit coherence condition). */
  given fromSchema[A <: Product](using Schema[A]): RecordCodec[A] =
    ShapeBacked(Shape.derivedWithRole[A](FieldRole.Input))

/** How to obtain a record-to-`I` input decoder from a program's own structure at packaging time. The
  * `programs`-module home for the per-type knowledge `dspy4s.optimize.Runnable` keeps per optimizer target
  * (decode via the signature's `inputShape`). Prototype scope: the [[Predict]] instance ships (the other
  * typed leaves are the same one-liner over their signatures), plus the low-priority [[RecordCodec]]-based
  * fallback covering ANY typed program whose input type is codec-equipped. Composites never need an
  * instance: composition threads the first leg's packaged decoder. */
trait ProgInput[F, I]:
  def decoder(program: F): DynamicValue.Record => Either[DspyError, I]

trait LowPriorityProgInput:
  /** Coherent default packaging from the object codec: any typed program whose input type has a
    * [[RecordCodec]]. Lower priority than a program's own signature-derived instance. `F` is deliberately
    * unconstrained here (the codec-based decoder never inspects the program, and constraining `F` by a
    * bound mentioning `I` defeats implicit inference); `Prog.of`'s own bound polices what `F` may be. */
  given fromRecordCodec[F, I](using codec: RecordCodec[I]): ProgInput[F, I] with
    def decoder(program: F): DynamicValue.Record => Either[DspyError, I] = codec.decode

object ProgInput extends LowPriorityProgInput:
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

  /** Decode a data-bag record into the typed input `I` (the packaged evaluation capability). Captured at
    * packaging time; threaded through composition. */
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

  /** Package a program whose input decoder is derivable ([[ProgInput]]: from its signature for `Predict`, or
    * from the input type's [[RecordCodec]] for any typed program with a codec-equipped input). */
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

  /** The Para-shaped category instance over packaged programs, with objects constrained by [[RecordCodec]].
    * `id` synthesizes its decoder from the object's codec; composition packages an [[AndThen]] node with the
    * structurally-derived evidence of its two children (so `params` distributes by construction) and threads
    * the FIRST leg's input decoder; `parallel` packages a [[Both]] the same way (both legs share the input,
    * so the first leg's decoder serves the pair). */
  given paraCatProg: ParaCat[RecordCodec, Prog] with
    def id[A: RecordCodec]: Prog[A, A] =
      Prog.of(Identity[A](), summon[RecordCodec[A]].decode)

    def parallel[I, A, B](f: Prog[I, A], g: Prog[I, B]): Prog[I, (A, B)] =
      Prog.of(Both[I, A, B, f.Rep, g.Rep](f.program, g.program), f.decodeInput)(using
        Both.bothPredictors[I, A, B, f.Rep, g.Rep](using f.addressable, g.addressable)
      )

    extension [A, B](f: Prog[A, B])
      infix def >>>[C](g: Prog[B, C]): Prog[A, C] =
        Prog.of(AndThen[A, B, C, f.Rep, g.Rep](f.program, g.program), f.decodeInput)(using
          AndThen.andThenPredictors[A, B, C, f.Rep, g.Rep](using f.addressable, g.addressable)
        )

      def params: Vector[DynamicPredict] = f.addressable.read(f.program)

      def reparam(ps: Vector[DynamicPredict]): Prog[A, B] =
        Prog.of(f.addressable.replace(f.program, ps), f.decodeInput)(using f.addressable)

/** [[ParaCat.params]] as a FUNCTOR VALUE: the (object-collapsing) functor from the [[Prog]] category to the
  * [[paramsDeloop]] delooping of the parameter monoid. Its functor laws are precisely the Para projection
  * laws ([[ParaCat.paramsId]] / [[ParaCat.paramsCompose]]), restated here in functor vocabulary — the
  * categorical name for what `Predictors.read` is. */
object ReadFunctor extends CatFunctor[RecordCodec, Prog, AnyObject, ParamsHom]:
  def map[A, B](f: Prog[A, B]): ParamsHom[A, B] = f.params
  // `identities` / `composition` are inherited from CatFunctor (source = paraCatProg, target = paramsDeloop).
