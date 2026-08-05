package dspy4s.programs.optimization

import dspy4s.algebra.{IsEq, Law, Lens, <->}
import dspy4s.core.collections.SizedVector

import scala.annotation.implicitNotFound
import scala.compiletime.ops.int.+
import scala.deriving.Mirror

/** The lawful, ordered optimizable structure of a program -- the dspy4s analogue of Python's `named_predictors` /
  * `map_named_predictors`.
  *
  * [[inspect]] enumerates non-executable [[OptimizableView]] snapshots in stable order. [[read]] projects just their
  * optimizable parameters, and [[replace]] writes an arity-matched parameter vector back while preserving metadata and
  * execution resources. Exact no-op replacement satisfies `replace(p, read(p)) == p`; read-after-write satisfies
  * `read(replace(p, parameters)) == parameters`. For override-backed composites, Put-Put is observational through
  * `read` even when two source values use different internal `Option` representations.
  */
trait OptimizableStructure[P]:
  type Arity <: Int

  /** Runtime reflection of [[Arity]] for compatibility APIs that still accept an unsized `Vector`. */
  def arity(program: P): Int

  def inspect(program   : P): Vector[OptimizableView]
  final def read(program: P): Vector[OptimizableParameters] = inspect(program).map(_.parameters)
  def replace(program   : P, updates: Vector[OptimizableParameters]): P

  /** Read parameters while retaining the statically known arity. */
  final def readSized(program: P): SizedVector[OptimizableParameters, Arity] =
    SizedVector.assumeSize(read(program))

  /** Replace parameters whose type already proves the correct arity. */
  final def replaceSized(program: P, updates: SizedVector[OptimizableParameters, Arity]): P =
    replace(program, updates)

  @Law("the runtime parameter count agrees with its static arity")
  final def arityAgreement(program: P): IsEq[Int] =
    read(program).size <-> arity(program)

  /** Each view paired with a human-readable structural name, analogous to Python's `named_predictors()`. Names are
    * dotted field paths: `"self"` for a standalone leaf, the field label for a composite's leaf field, and
    * `"field.sub"` when nested. They describe the current syntax tree and therefore are not identity: reassociating an
    * anonymous composition node can change its `first`/`second` path. This named view is aligned with [[inspect]]. The
    * default uses positional names; [[OptimizableStructure.DerivedOptimizableStructure]] overrides with Mirror field
    * labels.
    */
  def inspectNamed(program: P): Vector[(String, OptimizableView)] =
    inspect(program).zipWithIndex.map { case (view, i) => i.toString -> view }

  private final def alignedNamed(program: P): (Vector[OptimizableView], Vector[String]) =
    val views = inspect(program)
    val named = inspectNamed(program)
    require(
      named.size == views.size,
      s"OptimizableStructure.inspectNamed returned ${named.size} entries but inspect returned ${views.size}"
    )
    require(
      named.map(_._2) == views,
      "OptimizableStructure.inspectNamed must preserve the views and order returned by inspect"
    )
    views -> named.map(_._1)

  /** Structural names paired with optimizable parameters, in [[read]] order. */
  final def readNamed(program: P): Vector[(String, OptimizableParameters)] =
    val (views, displayNames) = alignedNamed(program)
    displayNames.zip(views.map(_.parameters))

  /** The canonical optimizer-facing structure. IDs are derived once at the root from [[read]] order, so nested
    * combinators cannot reset or prefix them. This makes identity unique and invariant under reassociation while
    * retaining [[inspectNamed]]'s useful structural labels for diagnostics and prompts.
    */
  final def readIdentified(program: P): Vector[IdentifiedOptimizable] =
    val (views, displayNames) = alignedNamed(program)
    displayNames.zip(views).zipWithIndex.map { case ((displayName, view), ordinal) =>
      IdentifiedOptimizable(OptimizableId.fromOrdinal(OptimizableOrdinal.assume(ordinal)), displayName, view)
    }

object OptimizableStructure extends CompositeOptimizableStructureInstances with LowPriorityOptimizableStructure:

  type WithArity[P, N <: Int] = OptimizableStructure[P] { type Arity = N }

  /** Nominal implementation base for givens whose arity should remain visible in their public result type. */
  trait Of[P, N <: Int] extends OptimizableStructure[P]:
    final type Arity = N

  /** Summon the optimizable structure. */
  def apply[P](using structure: OptimizableStructure[P]): OptimizableStructure[P] = structure

  /** The structure induces the canonical lawful lens onto its complete parameter vector. */
  given parameterLens[P, N <: Int](using
      structure: WithArity[P, N]
  ): Lens[P, SizedVector[OptimizableParameters, N]] with
    def get(program: P): SizedVector[OptimizableParameters, N]             = structure.readSized(program)
    def set(program: P, updates: SizedVector[OptimizableParameters, N]): P =
      structure.replaceSized(program, updates)

  /** Lifts a single [[OptimizableLeaf]] leaf to a 1-element [[OptimizableStructure]]. A type that is itself a leaf
    * (e.g. [[dspy4s.programs.strategies.DynamicPredict]], which is also a `Product`) resolves here and is not torn into
    * its case-class fields by structural derivation.
    */
  given fromOptimizableLeaf[P](using leaf: OptimizableLeaf[P]): OptimizableStructure.Of[P, 1] with
    def arity(@annotation.unused program: P): Int                      = 1
    def inspect(program                 : P): Vector[OptimizableView]  = Vector(leaf.inspect(program))
    def replace(program: P, updates: Vector[OptimizableParameters]): P =
      require(updates.size == 1, s"OptimizableLeaf expects exactly 1 update, got ${updates.size}")
      leaf.set(program, updates.head)
    // A leaf contributes "self" to the name path (the dspy convention for a standalone predict); a composite
    // collapses "self" into just its field label (see DerivedOptimizableStructure.inspectNamed).
    override def inspectNamed(program: P): Vector[(String, OptimizableView)] = Vector("self" -> leaf.inspect(program))

  /** Identity instance for types intentionally known to contain no optimizable leaves.
    *
    * Structural derivation does not assume that missing evidence means parameter-free: composites must place an `empty`
    * instance in scope for each deliberately non-learnable field type. This makes an omitted `OptimizableStructure`
    * instance a compile error instead of silently hiding a potentially learnable subtree.
    */
  def empty[P]: OptimizableStructure.WithArity[P, 0] =
    new OptimizableStructure.Of[P, 0]:
      def arity(@annotation.unused program: P): Int                      = 0
      def inspect(program                 : P): Vector[OptimizableView]  = Vector.empty
      def replace(program: P, updates: Vector[OptimizableParameters]): P =
        require(updates.isEmpty, s"Parameter-free program expects 0 updates, got ${updates.size}")
        program

  /** Named (non-inline) carrier of the derived behaviour. Keeping it a named class — rather than an anonymous class
    * inside `derived` — avoids `-Werror` rejecting an inline-duplicated anonymous class definition at each use site.
    */
  private[dspy4s] final class DerivedOptimizableStructure[P <: Product, N <: Int](
      m             : Mirror.ProductOf[P],
      fieldInstances: List[OptimizableStructure[Any]],
      labels        : List[String]
  ) extends OptimizableStructure.Of[P, N]:
    def arity(program: P): Int =
      fieldInstances.zipWithIndex.map { case (instance, index) => instance.arity(program.productElement(index)) }.sum

    def inspect(program: P): Vector[OptimizableView] =
      fieldInstances.zipWithIndex.foldLeft(Vector.empty[OptimizableView]) { case (acc, (inst, i)) =>
        acc ++ inst.inspect(program.productElement(i))
      }

    /** Names each optimizable leaf by its case-class field path (P-c). A field whose value is a leaf predict gets just
      * the field label (its leaf name "self" is collapsed); a nested composite field yields `"field.sub"`.
      */
    override def inspectNamed(program: P): Vector[(String, OptimizableView)] =
      fieldInstances.zip(labels).zipWithIndex.flatMap { case ((inst, label), i) =>
        inst.inspectNamed(program.productElement(i)).map { case (sub, view) =>
          (if sub == "self" then label else s"$label.$sub") -> view
        }
      }.toVector

    def replace(program: P, updates: Vector[OptimizableParameters]): P =
      val arities  = fieldInstances.zipWithIndex.map { case (inst, i) => inst.read(program.productElement(i)).size }
      val expected = arities.sum
      require(expected == updates.size, s"OptimizableStructure.replace expected $expected updates, got ${updates.size}")
      var cursor      = 0
      val rebuiltArgs = fieldInstances.zipWithIndex.map { case (inst, i) =>
        val value = program.productElement(i)
        val arity = arities(i)
        val slice = updates.slice(cursor, cursor + arity)
        cursor += arity
        inst.replace(value, slice)
      }
      m.fromProduct(Tuple.fromArray(rebuiltArgs.toArray))

  /** Recurse over the Mirror's element types, summoning each field's fixed-arity optimizable structure.
    *
    * The widening to `OptimizableStructure[Any]` is the single, narrowly-scoped accommodation needed to hold the
    * heterogeneous per-field instances in one homogeneous list. It is type-safe: the i-th instance is only ever applied
    * to `program.productElement(i)`, whose runtime value the Mirror guarantees to be of the corresponding element type.
    * No `asInstanceOf` is used on program values; the cast is confined to the instance witness, which never inspects
    * more than its own field.
    *
    * This compiler evidence retains the sum of field arities in the derived result type.
    */
  @implicitNotFound(
    "Cannot derive OptimizableStructure: every field must provide fixed-arity OptimizableStructure evidence. " +
      "Declare an explicit OptimizableStructure.empty instance for intentionally parameter-free field types."
  )
  sealed trait FieldStructures[Elems <: Tuple, N <: Int]:
    def instances: List[OptimizableStructure[Any]]

  given emptyFieldStructures: FieldStructures[EmptyTuple, 0] with
    val instances: List[OptimizableStructure[Any]] = Nil

  given consFieldStructures[Head, Tail <: Tuple, NHead <: Int, NTail <: Int](using
      head: OptimizableStructure.WithArity[Head, NHead],
      tail: FieldStructures[Tail, NTail]
  ): FieldStructures[Head *: Tail, NHead + NTail] with
    val instances: List[OptimizableStructure[Any]] = widen(head) :: tail.instances

  /** Confines the unavoidable widening of a per-field `OptimizableStructure[A]` to a `OptimizableStructure[Any]` to one
    * private helper. Safe because the Mirror pairs this instance positionally with a value of type `A` (see
    * [[FieldStructures]]); `OptimizableStructure` is invariant so the compiler cannot prove the subtype, but the
    * runtime contract holds.
    */
  private[dspy4s] def widen[A](inst: OptimizableStructure[A]): OptimizableStructure[Any] =
    inst.asInstanceOf[OptimizableStructure[Any]]
