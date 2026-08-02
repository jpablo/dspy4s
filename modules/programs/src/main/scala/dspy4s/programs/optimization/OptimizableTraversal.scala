package dspy4s.programs.optimization

import scala.annotation.implicitNotFound
import scala.compiletime.ops.int.+
import scala.deriving.Mirror

/** The general optimizer traversal -- the typed analogue of Python's `named_predictors` / `map_named_predictors`.
  *
  * [[inspect]] enumerates non-executable [[OptimizableView]] snapshots in stable order. [[read]] projects just their
  * optimizable parameters, and [[replace]] writes an arity-matched parameter vector back while preserving metadata and
  * execution resources. Exact no-op replacement satisfies `replace(p, read(p)) == p`; read-after-write satisfies
  * `read(replace(p, parameters)) == parameters`. For override-backed composites, Put-Put is observational through
  * `read` even when two source values use different internal `Option` representations.
  */
trait OptimizableTraversal[P]:
  def inspect(program: P): Vector[OptimizableView]
  final def read(program: P): Vector[OptimizableParameters] = inspect(program).map(_.parameters)
  def replace(program: P, updates: Vector[OptimizableParameters]): P

  /** Each view paired with a human-readable structural name, analogous to Python's `named_predictors()`. Names are
    * dotted field paths: `"self"` for a standalone leaf, the field label for a composite's leaf field, and
    * `"field.sub"` when nested. They describe the current syntax tree and therefore are not identity: reassociating an
    * anonymous composition node can change its `first`/`second` path. This traversal is aligned with [[inspect]]. The
    * default uses positional names; [[OptimizableTraversal.DerivedOptimizableTraversal]] overrides with Mirror field labels.
    */
  def inspectNamed(program: P): Vector[(String, OptimizableView)] =
    inspect(program).zipWithIndex.map { case (view, i) => i.toString -> view }

  private final def alignedNamed(program: P): (Vector[OptimizableView], Vector[String]) =
    val views = inspect(program)
    val named = inspectNamed(program)
    require(
      named.size == views.size,
      s"OptimizableTraversal.inspectNamed returned ${named.size} entries but inspect returned ${views.size}"
    )
    require(
      named.map(_._2) == views,
      "OptimizableTraversal.inspectNamed must preserve the views and order returned by inspect"
    )
    views -> named.map(_._1)

  /** Structural names paired with optimizable parameters, in [[read]] order. */
  final def readNamed(program: P): Vector[(String, OptimizableParameters)] =
    val (views, displayNames) = alignedNamed(program)
    displayNames.zip(views.map(_.parameters))

  /** The canonical optimizer-facing traversal. IDs are derived once at the root from [[read]] order, so nested
    * combinators cannot reset or prefix them. This makes identity unique and invariant under reassociation while
    * retaining [[inspectNamed]]'s useful structural labels for diagnostics and prompts.
    */
  final def readIdentified(program: P): Vector[IdentifiedOptimizable] =
    val (views, displayNames) = alignedNamed(program)
    displayNames.zip(views).zipWithIndex.map { case ((displayName, view), ordinal) =>
      IdentifiedOptimizable(OptimizableId.fromOrdinal(OptimizableOrdinal.assume(ordinal)), displayName, view)
    }

object OptimizableTraversal extends CompositeOptimizableTraversalInstances with LowPriorityOptimizableTraversal:

  /** Lifts a single [[OptimizableLeaf]] leaf to a 1-element [[OptimizableTraversal]]. A type that is itself a leaf (e.g.
    * [[dspy4s.programs.DynamicPredict]], which is also a `Product`) resolves here and is not torn into its case-class
    * fields by structural derivation.
    */
  given fromOptimizableLeaf[P](using leaf: OptimizableLeaf[P]): FixedArityOptimizableTraversal.Of[P, 1] with
    val arity: Int = 1
    def inspect(program: P): Vector[OptimizableView] = Vector(leaf.inspect(program))
    def replace(program: P, updates: Vector[OptimizableParameters]): P =
      require(updates.size == 1, s"OptimizableLeaf expects exactly 1 update, got ${updates.size}")
      leaf.set(program, updates.head)
    // A leaf contributes "self" to the name path (the dspy convention for a standalone predict); a composite
    // collapses "self" into just its field label (see DerivedOptimizableTraversal.inspectNamed).
    override def inspectNamed(program: P): Vector[(String, OptimizableView)] = Vector("self" -> leaf.inspect(program))

  /** Identity instance for types intentionally known to contain no optimizable leaves.
    *
    * Structural derivation does not assume that missing evidence means parameter-free: composites must place an `empty`
    * instance in scope for each deliberately non-learnable field type. This makes an omitted `OptimizableTraversal`
    * instance a compile error instead of silently hiding a potentially learnable subtree.
    */
  def empty[P]: FixedArityOptimizableTraversal.Aux[P, 0] = new FixedArityOptimizableTraversal.Of[P, 0]:
    val arity: Int = 0
    def inspect(program: P): Vector[OptimizableView] = Vector.empty
    def replace(program: P, updates: Vector[OptimizableParameters]): P =
      require(updates.isEmpty, s"Parameter-free program expects 0 updates, got ${updates.size}")
      program

  /** Named (non-inline) carrier of the derived behaviour. Keeping it a named class — rather than an anonymous class
    * inside `derived` — avoids `-Werror` rejecting an inline-duplicated anonymous class definition at each use site.
    */
  private[dspy4s] final class DerivedOptimizableTraversal[P <: Product, N <: Int](
      m: Mirror.ProductOf[P],
      fieldInstances: List[OptimizableTraversal[Any]],
      labels: List[String],
      val arity: Int
  ) extends FixedArityOptimizableTraversal.Of[P, N]:
    def inspect(program: P): Vector[OptimizableView] =
      fieldInstances.zipWithIndex.foldLeft(Vector.empty[OptimizableView]) { case (acc, (inst, i)) =>
        acc ++ inst.inspect(program.productElement(i))
      }

    /** Names each optimizable leaf by its case-class field path (P-c). A field whose value is a leaf predict gets just the
      * field label (its leaf name "self" is collapsed); a nested composite field yields `"field.sub"`.
      */
    override def inspectNamed(program: P): Vector[(String, OptimizableView)] =
      fieldInstances.zip(labels).zipWithIndex.flatMap { case ((inst, label), i) =>
        inst.inspectNamed(program.productElement(i)).map { case (sub, view) =>
          (if sub == "self" then label else s"$label.$sub") -> view
        }
      }.toVector

    def replace(program: P, updates: Vector[OptimizableParameters]): P =
      val arities = fieldInstances.zipWithIndex.map { case (inst, i) =>
        inst.read(program.productElement(i)).size
      }
      val expected = arities.sum
      require(expected == updates.size, s"OptimizableTraversal.replace expected $expected updates, got ${updates.size}")
      var cursor = 0
      val rebuiltArgs = fieldInstances.zipWithIndex.map { case (inst, i) =>
        val value = program.productElement(i)
        val arity = arities(i)
        val slice = updates.slice(cursor, cursor + arity)
        cursor += arity
        inst.replace(value, slice)
      }
      m.fromProduct(Tuple.fromArray(rebuiltArgs.toArray))

  /** Recurse over the Mirror's element types, summoning each field's fixed-arity traversal.
    *
    * The widening to `OptimizableTraversal[Any]` is the single, narrowly-scoped accommodation needed to hold the
    * heterogeneous per-field instances in one homogeneous list. It is type-safe: the i-th instance is only ever applied to
    * `program.productElement(i)`, whose runtime value the Mirror guarantees to be of the corresponding element type. No
    * `asInstanceOf` is used on program values; the cast is confined to the instance witness, which never inspects more
    * than its own field.
    *
    * This compiler evidence retains the sum of field arities in the derived result type.
    */
  @implicitNotFound(
    "Cannot derive OptimizableTraversal: every field must provide fixed-arity OptimizableTraversal evidence. " +
      "Declare an explicit OptimizableTraversal.empty instance for intentionally parameter-free field types."
  )
  sealed trait FixedFieldTraversals[Elems <: Tuple, N <: Int]:
    def instances: List[OptimizableTraversal[Any]]
    def arity: Int

  given emptyFixedFieldTraversals: FixedFieldTraversals[EmptyTuple, 0] with
    val instances: List[OptimizableTraversal[Any]] = Nil
    val arity: Int = 0

  given consFixedFieldTraversals[Head, Tail <: Tuple, NHead <: Int, NTail <: Int](using
      head: FixedArityOptimizableTraversal.Aux[Head, NHead],
      tail: FixedFieldTraversals[Tail, NTail]
  ): FixedFieldTraversals[Head *: Tail, NHead + NTail] with
    val instances: List[OptimizableTraversal[Any]] = widen(head) :: tail.instances
    val arity: Int = head.arity + tail.arity

  /** Confines the unavoidable widening of a per-field `OptimizableTraversal[A]` to a `OptimizableTraversal[Any]` to one
    * private helper. Safe because the Mirror pairs this instance positionally with a value of type `A` (see
    * [[FixedFieldTraversals]]); `OptimizableTraversal` is invariant so the compiler cannot prove the subtype, but the
    * runtime contract holds.
    */
  private[dspy4s] def widen[A](inst: OptimizableTraversal[A]): OptimizableTraversal[Any] =
    inst.asInstanceOf[OptimizableTraversal[Any]]
