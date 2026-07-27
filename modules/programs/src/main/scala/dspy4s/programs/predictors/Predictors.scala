package dspy4s.programs.predictors

import scala.compiletime.{erasedValue, error, summonFrom}
import scala.deriving.Mirror

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
      IdentifiedPredictor(PredictorId.fromOrdinal(PredictorOrdinal.assume(ordinal)), displayName, view)
    }

object Predictors extends CompositePredictorInstances with LowPriority:

  /** Lifts a single [[Predictor]] leaf to a 1-element [[Predictors]]. Higher priority than the [[LowPriority.derived]]
    * structural instance: a type that is itself a leaf (e.g. [[dspy4s.programs.DynamicPredict]], which is also a
    * `Product`) must
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

  /** Named (non-inline) carrier of the derived behaviour. Keeping it a named class — rather than an anonymous class
    * inside `derived` — avoids `-Werror` rejecting an inline-duplicated anonymous class definition at each use site.
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
