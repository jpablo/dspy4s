package dspy4s.programs.predictors

import dspy4s.core.contracts.IsEq
import dspy4s.core.contracts.Law
import dspy4s.core.contracts.Lens
import dspy4s.core.contracts.<->
import dspy4s.programs.{ChainOfThought, CodeAct, DynamicPredict, MultiChainComparison, Predict, ProgramOfThought, ReAct, RLM}
import dspy4s.typed.OutputAugmentation
import dspy4s.typed.OutputAugmentation.PrependField
import scala.compiletime.error
import scala.compiletime.erasedValue
import scala.compiletime.summonFrom
import scala.deriving.Mirror
import scala.util.NotGiven

/** A program that is one learnable predictor (a leaf of the introspection tree).
  *
  * This is a lawful [[dspy4s.core.contracts.Lens Lens]] onto exactly the program's writable [[PredictorState]]: the
  * Get-Put / Put-Get / Put-Put statements are inherited from the `Lens` trait, and the [[frame]] law added here pins
  * what makes the focus exact — writing state can never change the read-only [[PredictorMetadata]], which is what
  * excludes signature structure and execution resources from optimizer replacement. `PredictorStateSuite` executes
  * all four statements per instance.
  */
trait Predictor[P] extends Lens[P, PredictorState]:
  def metadata(program: P): PredictorMetadata

  final def inspect(program: P): PredictorView = PredictorView(metadata(program), get(program))

  @Law("frame: writing state never changes the read-only metadata")
  def frame(program: P, updated: PredictorState): IsEq[PredictorMetadata] =
    metadata(set(program, updated)) <-> metadata(program)

object Predictor:
  /** A [[DynamicPredict]] is itself a learnable predictor leaf. Defined in the [[Predictor]] companion so it is in
    * implicit scope wherever a `Predictor[DynamicPredict]` (or its `NotGiven`) is sought.
    */
  given Predictor[DynamicPredict] with
    def get(program: DynamicPredict): PredictorState =
      PredictorState(program.layout.instructions, program.demos, program.config)

    def metadata(program: DynamicPredict): PredictorMetadata =
      PredictorMetadata.from(program.layout, program.moduleName)

    def set(program: DynamicPredict, updated: PredictorState): DynamicPredict =
      if updated == get(program) then program
      else
        program.copy(
          layout = program.layout.withInstructions(updated.instructions),
          demos = updated.demos,
          config = updated.config
        )

  /** Leaf [[Predictor]] for the typed single-predictor program [[Predict]]. A `Predict` field inside a user composite
    * resolves here (via [[Predictors.fromPredictor]], 1 element) rather than being structurally torn apart by
    * [[Predictors.derived]], and a standalone `Predict` is introspectable/tunable. Lives in the [[Predictor]] companion
    * so it is in implicit scope without an explicit import.
    *
    * State is exactly instructions, demos, and module config. The signature field structure, output shape, name,
    * runtime, bound LM, and tools remain on the original typed program and are exposed only as read-only metadata.
    */
  given predictPredictor[I, O]: Predictor[Predict[I, O]] with
    def get(program: Predict[I, O]): PredictorState =
      PredictorState(program.signature.layout.instructions, program.demos, program.config)

    def metadata(program: Predict[I, O]): PredictorMetadata =
      PredictorMetadata.from(program.signature.layout, program.moduleName)

    def set(program: Predict[I, O], updated: PredictorState): Predict[I, O] =
      if updated == get(program) then program
      else
        program.copy(
          demos = updated.demos,
          config = updated.config,
          signature = program.signature.withInstructions(updated.instructions)
        )

  /** Leaf [[Predictor]] for the typed single-predictor program [[ChainOfThought]]. Like [[predictPredictor]], but the
    * exposed layout is the **augmented** layout CoT actually runs (a leading `reasoning` output field prepended).
    * `ChainOfThought.augmentLayout` returns an `Either`; it is resolved fail-fast here (consistent with the P3
    * hand-written instances), and only fails for layouts that cannot be augmented.
    *
    * State remains instructions, demos, and config. The augmented signature structure is metadata only; writing a state
    * changes the base signature's instructions, from which the same augmented structure is rebuilt.
    */
  given chainOfThoughtPredictor[I, O](using
      prepend: PrependField.Of["reasoning", String, O]
  ): Predictor[ChainOfThought[I, O]] with
    private def augmented(program: ChainOfThought[I, O]) =
      ChainOfThought
        .augmentLayout(program.signature.layout)
        .fold(
          err =>
            throw new IllegalStateException(
              s"ChainOfThought '${program.moduleName}' has a non-augmentable layout: ${err.message}"
            ),
          identity
        )

    def get(program: ChainOfThought[I, O]): PredictorState =
      PredictorState(program.signature.layout.instructions, program.demos, program.config)

    def metadata(program: ChainOfThought[I, O]): PredictorMetadata =
      PredictorMetadata.from(augmented(program), program.moduleName)

    def set(program: ChainOfThought[I, O], updated: PredictorState): ChainOfThought[I, O] =
      if updated == get(program) then program
      else
        program.copy(
          demos = updated.demos,
          config = updated.config,
          signature = program.signature.withInstructions(updated.instructions)
        )

/** Uniform syntax derived from the lawful [[Predictor]] lens. No predictor class needs to duplicate state/view/update
  * methods; every current and third-party leaf receives the same operations from its typeclass instance.
  */
extension [P](program: P)(using predictor: Predictor[P])
  def predictorState: PredictorState                 = predictor.get(program)
  def predictorView: PredictorView                   = predictor.inspect(program)
  def withPredictorState(updated: PredictorState): P = predictor.set(program, updated)

/** The general optimizer traversal -- the typed analogue of Python's `named_predictors` / `map_named_predictors`.
  *
  * [[inspect]] enumerates non-executable [[PredictorView]] snapshots in stable order. [[read]] projects just their
  * writable states, and [[replace]] writes an arity-matched state vector back while preserving metadata and execution
  * resources. Exact no-op replacement satisfies `replace(p, read(p)) == p`; read-after-write satisfies `read(replace(p,
  * states)) == states`. For override-backed composites, Put-Put is observational through `read` even when two source
  * values use different internal `Option` representations.
  */
trait Predictors[P]:
  def inspect(program: P): Vector[PredictorView]
  final def read(program: P): Vector[PredictorState] = inspect(program).map(_.state)
  def replace(program: P, updates: Vector[PredictorState]): P

  /** Each view paired with a human-readable structural name, analogous to Python's `named_predictors()`. Names are
    * dotted field paths: `"self"` for a standalone leaf, the field label for a composite's leaf field, and
    * `"field.sub"` when nested. They describe the current syntax tree and therefore are not identity: reassociating an
    * anonymous composition node can change its `first`/`second` path. This traversal is aligned with [[inspect]]. The
    * default uses positional names; [[Predictors.DerivedPredictors]] overrides with Mirror field labels.
    */
  def inspectNamed(program: P): Vector[(String, PredictorView)] =
    inspect(program).zipWithIndex.map { case (view, i) => i.toString -> view }

  private final def alignedNamed(program: P): (Vector[PredictorView], Vector[String]) =
    val views = inspect(program)
    val named = inspectNamed(program)
    require(
      named.size == views.size,
      s"Predictors.inspectNamed returned ${named.size} entries but inspect returned ${views.size}"
    )
    require(
      named.map(_._2) == views,
      "Predictors.inspectNamed must preserve the views and order returned by inspect"
    )
    views -> named.map(_._1)

  /** Structural names paired with writable state, in [[read]] order. */
  final def readNamed(program: P): Vector[(String, PredictorState)] =
    val (views, displayNames) = alignedNamed(program)
    displayNames.zip(views.map(_.state))

  /** The canonical optimizer-facing traversal. IDs are derived once at the root from [[read]] order, so nested
    * combinators cannot reset or prefix them. This makes identity unique and invariant under reassociation while
    * retaining [[inspectNamed]]'s useful structural labels for diagnostics and prompts.
    */
  final def readIdentified(program: P): Vector[IdentifiedPredictor] =
    val (views, displayNames) = alignedNamed(program)
    displayNames.zip(views).zipWithIndex.map { case ((displayName, view), ordinal) =>
      IdentifiedPredictor(PredictorId(ordinal), displayName, view)
    }

object Predictors extends LowPriority:

  /** Lifts a single [[Predictor]] leaf to a 1-element [[Predictors]]. Higher priority than the [[LowPriority.derived]]
    * structural instance: a type that is itself a leaf (e.g. [[DynamicPredict]], which is also a `Product`) must
    * resolve here, not be torn into its case-class fields by the structural derivation.
    */
  given fromPredictor[P](using leaf: Predictor[P]): Predictors[P] with
    def inspect(program: P): Vector[PredictorView] = Vector(leaf.inspect(program))
    def replace(program: P, updates: Vector[PredictorState]): P =
      require(updates.size == 1, s"Predictor leaf expects exactly 1 update, got ${updates.size}")
      leaf.set(program, updates.head)
    // A leaf contributes "self" to the name path (the dspy convention for a standalone predict); a composite
    // collapses "self" into just its field label (see DerivedPredictors.inspectNamed).
    override def inspectNamed(program: P): Vector[(String, PredictorView)] = Vector("self" -> leaf.inspect(program))

  /** Hand-written [[Predictors]] instances for the composite typed programs whose learnable sub-predicts are hoisted to
    * stable, `copy`-reachable members ([[ReAct]], [[CodeAct]], [[RLM]], [[ProgramOfThought]], and
    * [[MultiChainComparison]]; the evidence-parameterized wrappers [[BestOfN]] / [[Refine]] carry theirs in their
    * companions). They live in the [[Predictors]] companion so they are in implicit scope without an explicit import
    * (and so a user composite containing such a program resolves them; strict derivation rejects missing field
    * evidence). They are concrete `Predictors[ConcreteType]` instances; being strictly more specific than [[derived]]
    * (and there being no `Predictor` leaf for these types, so [[derived]] is even eligible), the compiler selects them.
    *
    * `replace` writes state through each current executable predictor. An unchanged state preserves the existing
    * override field exactly; a changed state creates an override with the same signature structure and execution
    * bindings. Thus optimizer replacement cannot swap runtimes, LMs, schemas, tools, or names.
    */
  given reactPredictors[I, O]: Predictors[ReAct[I, O]] with
    def inspect(program: ReAct[I, O]): Vector[PredictorView] =
      Vector(program.reactPredict.predictorView, program.extractorPredict.predictorView)

    override def inspectNamed(program: ReAct[I, O]): Vector[(String, PredictorView)] =
      Vector("react" -> program.reactPredict.predictorView, "extractor" -> program.extractorPredict.predictorView)

    def replace(program: ReAct[I, O], updates: Vector[PredictorState]): ReAct[I, O] =
      require(updates.size == 2, s"ReAct expects exactly 2 updates (react, extractor), got ${updates.size}")
      val nextReact = updateOverride(program.reactPredict, program.reactPredictOverride, updates(0))
      val nextExtractor = updateOverride(program.extractorPredict, program.extractorPredictOverride, updates(1))
      program.copy(reactPredictOverride = nextReact, extractorPredictOverride = nextExtractor)

  given codeActPredictors[I, O]: Predictors[CodeAct[I, O]] with
    def inspect(program: CodeAct[I, O]): Vector[PredictorView] =
      Vector(program.codeActPredict.predictorView, program.extractorPredict.predictorView)

    override def inspectNamed(program: CodeAct[I, O]): Vector[(String, PredictorView)] =
      Vector("codeact" -> program.codeActPredict.predictorView, "extractor" -> program.extractorPredict.predictorView)

    def replace(program: CodeAct[I, O], updates: Vector[PredictorState]): CodeAct[I, O] =
      require(updates.size == 2, s"CodeAct expects exactly 2 updates (codeact, extractor), got ${updates.size}")
      val nextCodeAct = updateOverride(program.codeActPredict, program.codeActPredictOverride, updates(0))
      val nextExtractor = updateOverride(program.extractorPredict, program.extractorPredictOverride, updates(1))
      program.copy(codeActPredictOverride = nextCodeAct, extractorPredictOverride = nextExtractor)

  given rlmPredictors[I, O]: Predictors[RLM[I, O]] with
    def inspect(program: RLM[I, O]): Vector[PredictorView] =
      Vector(program.actionPredict.predictorView, program.extractPredict.predictorView)

    override def inspectNamed(program: RLM[I, O]): Vector[(String, PredictorView)] =
      Vector("action" -> program.actionPredict.predictorView, "extract" -> program.extractPredict.predictorView)

    def replace(program: RLM[I, O], updates: Vector[PredictorState]): RLM[I, O] =
      require(updates.size == 2, s"RLM expects exactly 2 updates (action, extract), got ${updates.size}")
      val nextAction = updateOverride(program.actionPredict, program.actionPredictOverride, updates(0))
      val nextExtract = updateOverride(program.extractPredict, program.extractPredictOverride, updates(1))
      program.copy(actionPredictOverride = nextAction, extractPredictOverride = nextExtract)

  given programOfThoughtPredictors[I, O]: Predictors[ProgramOfThought[I, O]] with
    def inspect(program: ProgramOfThought[I, O]): Vector[PredictorView] =
      Vector(
        program.generatorPredict.predictorView,
        program.regeneratorPredict.predictorView,
        program.answererPredict.predictorView
      )

    override def inspectNamed(program: ProgramOfThought[I, O]): Vector[(String, PredictorView)] =
      Vector(
        "generator"   -> program.generatorPredict.predictorView,
        "regenerator" -> program.regeneratorPredict.predictorView,
        "answerer"    -> program.answererPredict.predictorView
      )

    def replace(program: ProgramOfThought[I, O], updates: Vector[PredictorState]): ProgramOfThought[I, O] =
      require(
        updates.size == 3,
        s"ProgramOfThought expects exactly 3 updates (generator, regenerator, answerer), got ${updates.size}"
      )
      if updates == inspect(program).map(_.state) then program
      else
        val nextGenerator = updateOverride(program.generatorPredict, program.generatorPredictOverride, updates(0))
        val nextRegenerator =
          updateOverride(program.regeneratorPredict, program.regeneratorPredictOverride, updates(1))
        val nextAnswerer = updateOverride(program.answererPredict, program.answererPredictOverride, updates(2))
        program.copy(
          generatorPredictOverride = nextGenerator,
          regeneratorPredictOverride = nextRegenerator,
          answererPredictOverride = nextAnswerer
        )

  given multiChainComparisonPredictors[I, O]: Predictors[MultiChainComparison[I, O]] with
    def inspect(program: MultiChainComparison[I, O]): Vector[PredictorView] =
      Vector(program.comparePredict.predictorView)

    override def inspectNamed(program: MultiChainComparison[I, O]): Vector[(String, PredictorView)] =
      Vector("compare" -> program.comparePredict.predictorView)

    def replace(program: MultiChainComparison[I, O], updates: Vector[PredictorState]): MultiChainComparison[I, O] =
      require(updates.size == 1, s"MultiChainComparison expects exactly 1 update (compare), got ${updates.size}")
      val nextCompare = updateOverride(program.comparePredict, program.comparePredictOverride, updates(0))
      program.copy(comparePredictOverride = nextCompare)

  private def updateOverride[P](
      current: P,
      existing: Option[P],
      updated: PredictorState
  )(using Predictor[P]): Option[P] =
    if updated == current.predictorState then existing else Some(current.withPredictorState(updated))

  /** Identity instance for types intentionally known to contain no predictors.
    *
    * Structural derivation does not assume that missing evidence means parameter-free: composites must place an `empty`
    * instance in scope for each deliberately non-learnable field type. This makes an omitted `Predictors` instance a
    * compile error instead of silently hiding a potentially learnable subtree.
    */
  def empty[P]: Predictors[P] = new Predictors[P]:
    def inspect(program: P): Vector[PredictorView] = Vector.empty
    def replace(program: P, updates: Vector[PredictorState]): P =
      require(updates.isEmpty, s"Parameter-free program expects 0 updates, got ${updates.size}")
      program

  /** Named (non-inline) carrier of the derived behaviour. Keeping it a top-level class -- rather than an anonymous
    * class inside `derived` -- avoids `-Werror` rejecting an inline-duplicated anonymous class definition at each use
    * site.
    */
  private[dspy4s] final class DerivedPredictors[P <: Product](
      m: Mirror.ProductOf[P],
      fieldInstances: List[Predictors[Any]],
      labels: List[String]
  ) extends Predictors[P]:
    def inspect(program: P): Vector[PredictorView] =
      fieldInstances.zipWithIndex.foldLeft(Vector.empty[PredictorView]) { case (acc, (inst, i)) =>
        acc ++ inst.inspect(program.productElement(i))
      }

    /** Names each predictor by its case-class field path (P-c). A field whose value is a leaf predict gets just the
      * field label (its leaf name "self" is collapsed); a nested composite field yields `"field.sub"`.
      */
    override def inspectNamed(program: P): Vector[(String, PredictorView)] =
      fieldInstances.zip(labels).zipWithIndex.flatMap { case ((inst, label), i) =>
        inst.inspectNamed(program.productElement(i)).map { case (sub, view) =>
          (if sub == "self" then label else s"$label.$sub") -> view
        }
      }.toVector

    def replace(program: P, updates: Vector[PredictorState]): P =
      val arities = fieldInstances.zipWithIndex.map { case (inst, i) =>
        inst.read(program.productElement(i)).size
      }
      val expected = arities.sum
      require(expected == updates.size, s"Predictors.replace expected $expected updates, got ${updates.size}")
      var cursor = 0
      val rebuiltArgs = fieldInstances.zipWithIndex.map { case (inst, i) =>
        val value = program.productElement(i)
        val arity = arities(i)
        val slice = updates.slice(cursor, cursor + arity)
        cursor += arity
        inst.replace(value, slice)
      }
      m.fromProduct(Tuple.fromArray(rebuiltArgs.toArray))

  /** Recurse over the Mirror's element types, summoning each field's `Predictors`.
    *
    * The widening to `Predictors[Any]` is the single, narrowly-scoped accommodation needed to hold the heterogeneous
    * per-field instances in one homogeneous list. It is type-safe: the i-th instance is only ever applied to
    * `program.productElement(i)`, whose runtime value the Mirror guarantees to be of the corresponding element type. No
    * `asInstanceOf` is used on program values; the cast is confined to the instance witness, which never inspects more
    * than its own field.
    */
  private[dspy4s] inline def summonFieldInstances[Elems <: Tuple]: List[Predictors[Any]] =
    inline erasedValue[Elems] match
      case _: EmptyTuple => Nil
      case _: (head *: tail) =>
        val instance: Predictors[Any] = summonFieldInstance[head]
        instance :: summonFieldInstances[tail]

  private[dspy4s] inline def summonFieldInstance[A]: Predictors[Any] =
    summonFrom {
      case inst: Predictors[A] => widen(inst)
      case _ =>
        error(
          "Cannot derive Predictors: every field must provide Predictors evidence. " +
            "Declare an explicit Predictors.empty instance for intentionally parameter-free field types."
        )
    }

  /** Confines the unavoidable widening of a per-field `Predictors[A]` to a `Predictors[Any]` to one private helper.
    * Safe because the Mirror pairs this instance positionally with a value of type `A` (see [[summonFieldInstances]]);
    * `Predictors` is invariant so the compiler cannot prove the subtype, but the runtime contract holds.
    */
  private[dspy4s] def widen[A](inst: Predictors[A]): Predictors[Any] =
    inst.asInstanceOf[Predictors[Any]]

/** Lowest priority: the structural Mirror derivation over a case class. */
trait LowPriority:

  /** Mirror derivation over a case class: each field's `Predictors` instances are concatenated (left -> right field
    * order) for `read`, and `replace` slices the updates by per-field read-arity, rebuilding via `m.fromProduct`. Every
    * field must provide evidence; intentionally parameter-free field types opt in explicitly through
    * [[Predictors.empty]].
    *
    * The `NotGiven[Predictor[P]]` guard keeps the structural derivation from competing with
    * [[Predictors.fromPredictor]]: a type that is itself a leaf (e.g. [[DynamicPredict]]) must resolve to the 1-element
    * leaf instance, not be torn apart into its case-class fields.
    */
  inline given derived[P <: Product](using
      m: Mirror.ProductOf[P],
      @annotation.unused notLeaf: NotGiven[Predictor[P]]
  ): Predictors[P] =
    new Predictors.DerivedPredictors[P](
      m,
      Predictors.summonFieldInstances[m.MirroredElemTypes],
      scala.compiletime.constValueTuple[m.MirroredElemLabels].toList.map(_.toString)
    )
