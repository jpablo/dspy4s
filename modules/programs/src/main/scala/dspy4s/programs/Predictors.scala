package dspy4s.programs

import dspy4s.typed.OutputAugmentation
import scala.compiletime.erasedValue
import scala.compiletime.summonFrom
import scala.deriving.Mirror
import scala.util.NotGiven

/** A program that IS one learnable predictor (the leaf of the introspection tree). */
trait Predictor[P]:
  def get(program: P): DynamicPredict
  def set(program: P, updated: DynamicPredict): P

object Predictor:
  /** A [[DynamicPredict]] is itself a learnable predictor leaf. Defined in the [[Predictor]] companion
    * so it is in implicit scope wherever a `Predictor[DynamicPredict]` (or its `NotGiven`) is sought. */
  given Predictor[DynamicPredict] with
    def get(program: DynamicPredict): DynamicPredict                         = program
    def set(program: DynamicPredict, updated: DynamicPredict): DynamicPredict = updated

  /** Leaf [[Predictor]] for the typed single-predictor program [[Predict]]. A `Predict` field inside a user
    * composite resolves here (via [[Predictors.fromPredictor]], 1 element) rather than being structurally torn
    * apart by [[Predictors.derived]], and a standalone `Predict` is introspectable/tunable. Lives in the
    * [[Predictor]] companion so it is in implicit scope without an explicit import.
    *
    * `get` projects the program's learnable state into the [[DynamicPredict]] the program actually runs on:
    * the layout is `signature.layout` (the exact layout the inner [[dspy4s.programs.runtime.PredictEngine]]
    * executes), with the program's `demos`, name, and output JSON schema.
    *
    * `set` writes back the editable learnable state: `demos`, the module-level `config`, and the layout's
    * `instructions` (applied via `signature.withInstructions`, which touches only the instruction string).
    * It deliberately does NOT swap the full layout back into the typed signature — that would desync
    * `signature.outputShape` (which still decodes the original `O`) from `signature.layout`. Editing only the
    * instructions string is shape-safe and is what instruction optimizers (COPRO/MIPRO) need. The invariant
    * `set(p, get(p)) == p` holds (demos/config/instructions are projected by `get` and re-applied unchanged). */
  given predictPredictor[I, O]: Predictor[Predict[I, O]] with
    def get(program: Predict[I, O]): DynamicPredict =
      DynamicPredict(
        layout           = program.signature.layout,
        demos            = program.demos,
        name             = Some(program.moduleName),
        outputJsonSchema = program.signature.outputShape.jsonSchemaString,
        config           = program.config
      )

    def set(program: Predict[I, O], updated: DynamicPredict): Predict[I, O] =
      program.copy(
        demos     = updated.demos,
        config    = updated.config,
        signature = program.signature.withInstructions(updated.layout.instructions)
      )

  /** Leaf [[Predictor]] for the typed single-predictor program [[ChainOfThought]]. Like [[predictPredictor]],
    * but the exposed layout is the **augmented** layout CoT actually runs (a leading `reasoning` output field
    * prepended). `ChainOfThought.augmentLayout` returns an `Either`; it is resolved fail-fast here (consistent
    * with the P3 hand-written instances), and only fails for layouts that cannot be augmented.
    *
    * `set` writes back `demos` and the layout's `instructions` (via `signature.withInstructions`, shape-safe).
    * `ChainOfThought` has no module-level `config` field (G-3 added it only to `Predict`/`DynamicPredict`), so
    * config is not round-tripped here — a minor follow-up. The `prepend` evidence is required to reconstruct the
    * program via `copy`. The invariant `set(p, get(p)) == p` holds. */
  given chainOfThoughtPredictor[I, O](using
      prepend: OutputAugmentation.PrependField.Aux["reasoning", String, O, ChainOfThought.WithReasoning[O]]
  ): Predictor[ChainOfThought[I, O]] with
    def get(program: ChainOfThought[I, O]): DynamicPredict =
      val augmented = ChainOfThought
        .augmentLayout(program.signature.layout)
        .fold(err => throw new IllegalStateException(
          s"ChainOfThought '${program.moduleName}' has a non-augmentable layout: ${err.message}"
        ), identity)
      DynamicPredict(
        layout           = augmented,
        demos            = program.demos,
        name             = Some(program.moduleName),
        outputJsonSchema = program.signature.outputShape.jsonSchemaString
      )

    def set(program: ChainOfThought[I, O], updated: DynamicPredict): ChainOfThought[I, O] =
      program.copy(
        demos     = updated.demos,
        signature = program.signature.withInstructions(updated.layout.instructions)
      )

/** The general introspection contract optimizers consume -- typed analogue of Python's
  * named_predictors()/map_named_predictors(). `read` enumerates contained predictors in a stable
  * order; `replace` swaps them positionally and rebuilds the program immutably. Invariant:
  * read(p).length and indices are stable across replace, and replace(p, read(p)) == p. */
trait Predictors[P]:
  def read(program: P): Vector[DynamicPredict]
  def replace(program: P, updates: Vector[DynamicPredict]): P

  /** Each predictor paired with a stable component NAME — the dspy4s analogue of Python's `named_predictors()`,
    * and the key GEPA / Refine-per-module-advice need to associate a candidate, a trace, and a predictor. Names
    * are dotted field paths: `"self"` for a standalone leaf, the field label for a composite's leaf field, and
    * `"field.sub"` when nested. `readNamed` is aligned with [[read]] order. The default uses positional index
    * names; [[Predictors.DerivedPredictors]] overrides with the Mirror field labels (the latent names, G-12 P-c). */
  def readNamed(program: P): Vector[(String, DynamicPredict)] =
    read(program).zipWithIndex.map { case (predict, i) => i.toString -> predict }

object Predictors extends LowPriority:

  /** Lifts a single [[Predictor]] leaf to a 1-element [[Predictors]]. Higher priority than the
    * [[LowPriority.derived]] structural instance: a type that is itself a leaf (e.g.
    * [[DynamicPredict]], which is also a `Product`) must resolve here, not be torn into its
    * case-class fields by the structural derivation. */
  given fromPredictor[P](using leaf: Predictor[P]): Predictors[P] with
    def read(program: P): Vector[DynamicPredict] = Vector(leaf.get(program))
    def replace(program: P, updates: Vector[DynamicPredict]): P =
      require(updates.size == 1, s"Predictor leaf expects exactly 1 update, got ${updates.size}")
      leaf.set(program, updates.head)
    // A leaf contributes "self" to the name path (the dspy convention for a standalone predict); a composite
    // collapses "self" into just its field label (see DerivedPredictors.readNamed).
    override def readNamed(program: P): Vector[(String, DynamicPredict)] = Vector("self" -> leaf.get(program))

  /** Hand-written [[Predictors]] instances for the composite typed programs whose learnable sub-predicts are
    * hoisted to stable, `copy`-reachable members ([[ReAct]], [[CodeAct]], [[MultiChainComparison]]). They live in
    * the [[Predictors]] companion so they are in implicit scope without an explicit import (and so a user composite
    * containing such a program resolves them rather than silently falling back to [[empty]]). They are concrete
    * `Predictors[ConcreteType]` instances; being strictly more specific than [[derived]] (and there being no
    * `Predictor` leaf for these types, so [[derived]] is even eligible), the compiler selects them.
    *
    * `replace` rebuilds the program immutably via the per-predict `*Override` fields and satisfies
    * `replace(p, read(p)) == p`: `replace` only rewrites an override field when the incoming update is not the
    * program's current effective predict, compared by reference (`eq`). Since `read` returns the exact member
    * objects, `replace(p, read(p))` leaves every override field untouched and yields `p`; an edited `copy` is a
    * fresh object, so it is wrapped into the `*Override` field instead. */
  given reactPredictors[I, O]: Predictors[ReAct[I, O]] with
    def read(program: ReAct[I, O]): Vector[DynamicPredict] =
      Vector(program.reactPredict, program.extractorPredict)

    def replace(program: ReAct[I, O], updates: Vector[DynamicPredict]): ReAct[I, O] =
      require(updates.size == 2, s"ReAct expects exactly 2 updates (react, extractor), got ${updates.size}")
      val nextReact     = if updates(0) eq program.reactPredict then program.reactPredictOverride else Some(updates(0))
      val nextExtractor = if updates(1) eq program.extractorPredict then program.extractorPredictOverride
                          else Some(updates(1))
      program.copy(reactPredictOverride = nextReact, extractorPredictOverride = nextExtractor)

  given codeActPredictors[I, O]: Predictors[CodeAct[I, O]] with
    def read(program: CodeAct[I, O]): Vector[DynamicPredict] =
      Vector(program.codeActPredict, program.extractorPredict)

    def replace(program: CodeAct[I, O], updates: Vector[DynamicPredict]): CodeAct[I, O] =
      require(updates.size == 2, s"CodeAct expects exactly 2 updates (codeact, extractor), got ${updates.size}")
      val nextCodeAct   = if updates(0) eq program.codeActPredict then program.codeActPredictOverride
                          else Some(updates(0))
      val nextExtractor = if updates(1) eq program.extractorPredict then program.extractorPredictOverride
                          else Some(updates(1))
      program.copy(codeActPredictOverride = nextCodeAct, extractorPredictOverride = nextExtractor)

  given multiChainComparisonPredictors[I, O]: Predictors[MultiChainComparison[I, O]] with
    def read(program: MultiChainComparison[I, O]): Vector[DynamicPredict] =
      Vector(program.comparePredict)

    def replace(program: MultiChainComparison[I, O], updates: Vector[DynamicPredict]): MultiChainComparison[I, O] =
      require(updates.size == 1, s"MultiChainComparison expects exactly 1 update (compare), got ${updates.size}")
      val nextCompare = if updates(0) eq program.comparePredict then program.comparePredictOverride
                        else Some(updates(0))
      program.copy(comparePredictOverride = nextCompare)

  /** Identity instance for fields that contain no predictors. */
  def empty[P]: Predictors[P] = new Predictors[P]:
    def read(program: P): Vector[DynamicPredict]                  = Vector.empty
    def replace(program: P, updates: Vector[DynamicPredict]): P = program

  /** Named (non-inline) carrier of the derived behaviour. Keeping it a top-level class -- rather than
    * an anonymous class inside `derived` -- avoids `-Werror` rejecting an inline-duplicated anonymous
    * class definition at each use site. */
  private[dspy4s] final class DerivedPredictors[P <: Product](
      m: Mirror.ProductOf[P],
      fieldInstances: List[Predictors[Any]],
      labels: List[String]
  ) extends Predictors[P]:
    def read(program: P): Vector[DynamicPredict] =
      fieldInstances.zipWithIndex.foldLeft(Vector.empty[DynamicPredict]) { case (acc, (inst, i)) =>
        acc ++ inst.read(program.productElement(i))
      }

    /** Names each predictor by its case-class field path (P-c). A field whose value is a leaf predict gets just
      * the field label (its leaf name "self" is collapsed); a nested composite field yields `"field.sub"`. */
    override def readNamed(program: P): Vector[(String, DynamicPredict)] =
      fieldInstances.zip(labels).zipWithIndex.flatMap { case ((inst, label), i) =>
        inst.readNamed(program.productElement(i)).map { case (sub, predict) =>
          (if sub == "self" then label else s"$label.$sub") -> predict
        }
      }.toVector

    def replace(program: P, updates: Vector[DynamicPredict]): P =
      var cursor      = 0
      val rebuiltArgs = fieldInstances.zipWithIndex.map { case (inst, i) =>
        val value = program.productElement(i)
        val arity = inst.read(value).size
        val slice = updates.slice(cursor, cursor + arity)
        cursor += arity
        inst.replace(value, slice)
      }
      require(cursor == updates.size, s"Predictors.replace expected $cursor updates, got ${updates.size}")
      m.fromProduct(Tuple.fromArray(rebuiltArgs.toArray))

  /** Recurse over the Mirror's element types, summoning each field's `Predictors` (or [[empty]]).
    *
    * The widening to `Predictors[Any]` is the single, narrowly-scoped accommodation needed to hold
    * the heterogeneous per-field instances in one homogeneous list. It is type-safe: the i-th
    * instance is only ever applied to `program.productElement(i)`, whose runtime value the Mirror
    * guarantees to be of the corresponding element type. No `asInstanceOf` is used on program values;
    * the cast is confined to the instance witness, which never inspects more than its own field. */
  private[dspy4s] inline def summonFieldInstances[Elems <: Tuple]: List[Predictors[Any]] =
    inline erasedValue[Elems] match
      case _: EmptyTuple => Nil
      case _: (head *: tail) =>
        val instance: Predictors[Any] = summonFieldInstance[head]
        instance :: summonFieldInstances[tail]

  private[dspy4s] inline def summonFieldInstance[A]: Predictors[Any] =
    summonFrom {
      case inst: Predictors[A] => widen(inst)
      case _                   => empty[Any]
    }

  /** Confines the unavoidable widening of a per-field `Predictors[A]` to a `Predictors[Any]` to one
    * private helper. Safe because the Mirror pairs this instance positionally with a value of type
    * `A` (see [[summonFieldInstances]]); `Predictors` is invariant so the compiler cannot prove the
    * subtype, but the runtime contract holds. */
  private[dspy4s] def widen[A](inst: Predictors[A]): Predictors[Any] =
    inst.asInstanceOf[Predictors[Any]]

/** Lowest priority: the structural Mirror derivation over a case class. */
trait LowPriority:

  /** Mirror derivation over a case class: each field's `Predictors` instances are concatenated
    * (left -> right field order) for `read`, and `replace` slices the updates by per-field read-arity,
    * rebuilding via `m.fromProduct`. Fields with no `Predictors` instance fall back to [[Predictors.empty]].
    *
    * The `NotGiven[Predictor[P]]` guard keeps the structural derivation from competing with
    * [[Predictors.fromPredictor]]: a type that is itself a leaf (e.g. [[DynamicPredict]]) must resolve
    * to the 1-element leaf instance, not be torn apart into its case-class fields. */
  inline given derived[P <: Product](using
      m: Mirror.ProductOf[P],
      @annotation.unused notLeaf: NotGiven[Predictor[P]]
  ): Predictors[P] =
    new Predictors.DerivedPredictors[P](
      m,
      Predictors.summonFieldInstances[m.MirroredElemTypes],
      scala.compiletime.constValueTuple[m.MirroredElemLabels].toList.map(_.toString)
    )
