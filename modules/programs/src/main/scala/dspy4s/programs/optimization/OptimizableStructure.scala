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

  /** Each view paired with a human-readable structural name, analogous to Python's `named_predictors()`. Names are
    * dotted field paths: `"self"` for a standalone leaf, the field label for a composite's leaf field, and
    * `"field.sub"` when nested. They describe the current syntax tree and therefore are not identity: reassociating an
    * anonymous composition node can change its `first`/`second` path. The order of the views is the stable order of the
    * structure, and [[inspect]], [[read]], and [[arity]] come from this one list.
    */
  def inspectNamed(program: P): Vector[(String, OptimizableView)]

  def replace(program: P, updates: Vector[OptimizableParameters]): P

  /** Runtime reflection of [[Arity]] for compatibility APIs that still accept an unsized `Vector`. */
  def arity(program: P): Int = inspectNamed(program).size

  def inspect(program: P): Vector[OptimizableView] = inspectNamed(program).map(_._2)

  final def read(program: P): Vector[OptimizableParameters] = inspect(program).map(_.parameters)

  /** Read parameters while retaining the statically known arity. */
  final def readSized(program: P): SizedVector[OptimizableParameters, Arity] =
    SizedVector.assumeSize(read(program))

  /** Replace parameters whose type already proves the correct arity. */
  final def replaceSized(program: P, updates: SizedVector[OptimizableParameters, Arity]): P =
    replace(program, updates)

  @Law("the runtime parameter count agrees with its static arity")
  final def arityAgreement(program: P): IsEq[Int] =
    read(program).size <-> arity(program)

  /** Structural names paired with optimizable parameters, in [[read]] order. */
  final def readNamed(program: P): Vector[(String, OptimizableParameters)] =
    inspectNamed(program).map((displayName, view) => displayName -> view.parameters)

  /** The canonical optimizer-facing structure. IDs are derived once at the root from [[read]] order, so nested
    * combinators cannot reset or prefix them. This makes identity unique and invariant under reassociation while
    * retaining [[inspectNamed]]'s useful structural labels for diagnostics and prompts.
    */
  final def readIdentified(program: P): Vector[IdentifiedOptimizable] =
    inspectNamed(program).zipWithIndex.map { case ((displayName, view), ordinal) =>
      IdentifiedOptimizable(OptimizableId.fromOrdinal(OptimizableOrdinal.assume(ordinal)), displayName, view)
    }

object OptimizableStructure extends LowPriorityOptimizableStructure:

  type WithArity[P, N <: Int] = OptimizableStructure[P] { type Arity = N }

  /** Nominal implementation base for givens whose arity should remain visible in their public result type. */
  trait Of[P, N <: Int] extends OptimizableStructure[P]:
    final type Arity = N

  /** Summon the optimizable structure. */
  def apply[P](using structure: OptimizableStructure[P]): OptimizableStructure[P] = structure

  // The factories of the composite structures. The interpreter keeps them stack-safe.
  export StackSafeOptimizableStructure.{pair, unary}

  /** The full name of the leaf `name` under `prefix`. A standalone leaf has the name `"self"`, so `prefix` alone names
    * it.
    */
  private[optimization] def qualifiedName(prefix: String, name: String): String =
    if name == "self" then prefix else s"$prefix.$name"

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
  given fromOptimizableLeaf[P](using
      leaf: OptimizableLeaf[P]
  ): LeafStructure[P]("OptimizableLeaf", "self", leaf.inspect, leaf.set) with {}

  /** One optimizer leaf with a caller-defined read and write boundary. `label` starts the text of a size error. */
  def leaf[P](
      label: String,
      name : String,
      view : P => OptimizableView,
      write: (P, OptimizableParameters) => P
  ): OptimizableStructure.Of[P, 1] =
    new LeafStructure(label, name, view, write)

  /** The structure of [[leaf]] and of [[fromOptimizableLeaf]]. */
  sealed class LeafStructure[P](
      label: String,
      name : String,
      view : P => OptimizableView,
      write: (P, OptimizableParameters) => P
  ) extends OptimizableStructure.Of[P, 1]:
    override def arity(@annotation.unused program: P): Int = 1

    def inspectNamed(program: P): Vector[(String, OptimizableView)] = Vector(name -> view(program))

    def replace(program: P, updates: Vector[OptimizableParameters]): P =
      require(updates.size == 1, s"$label expects 1 update, got ${updates.size}")
      write(program, updates.head)

  /** A composite with two `copy`-reachable predictors, each with an override field. `replace` writes parameters through
    * each current predictor. Unchanged parameters preserve the existing override field exactly; changed parameters
    * create an override with the same signature structure and execution bindings. Thus optimizer replacement cannot
    * swap runtimes, LMs, schemas, tools, or names. When both predictors are unchanged, the result is the same program.
    */
  private[programs] def overridePair[P, A: OptimizableLeaf, B: OptimizableLeaf](
      label    : String,
      leftName : String,
      rightName: String
  )(
      getLeft      : P => A,
      leftOverride : P => Option[A],
      getRight     : P => B,
      rightOverride: P => Option[B],
      rebuild      : (P, Option[A], Option[B]) => P
  ): OptimizableStructure.Of[P, 2] =
    pair[P, A, B, 1, 1](
      label,
      Some(leftName),
      Some(rightName),
      getLeft,
      getRight,
      (program, left, right) =>
        val leftChanged  = left.optimizableParameters != getLeft(program).optimizableParameters
        val rightChanged = right.optimizableParameters != getRight(program).optimizableParameters
        if !leftChanged && !rightChanged then program
        else
          rebuild(
            program,
            if leftChanged then Some(left) else leftOverride(program),
            if rightChanged then Some(right) else rightOverride(program)
          )
      ,
      fromOptimizableLeaf,
      fromOptimizableLeaf
    )

  /** Identity instance for types intentionally known to contain no optimizable leaves.
    *
    * Structural derivation does not assume that missing evidence means parameter-free: composites must place an `empty`
    * instance in scope for each deliberately non-learnable field type. This makes an omitted `OptimizableStructure`
    * instance a compile error instead of silently hiding a potentially learnable subtree.
    */
  def empty[P]: OptimizableStructure.WithArity[P, 0] =
    new OptimizableStructure.Of[P, 0]:
      override def arity(@annotation.unused program: P): Int = 0

      def inspectNamed(@annotation.unused program: P): Vector[(String, OptimizableView)] = Vector.empty

      def replace(program: P, updates: Vector[OptimizableParameters]): P =
        require(updates.isEmpty, s"Parameter-free program expects 0 updates, got ${updates.size}")
        program

  /** Named (non-inline) carrier of the derived behaviour. Keeping it a named class — rather than an anonymous class
    * inside `derived` — avoids `-Werror` rejecting an inline-duplicated anonymous class definition at each use site.
    *
    * The Mirror converts a program to the typed tuple of its fields and back, so [[FieldStructures]] applies each field
    * instance to a value of its own field type.
    */
  private[dspy4s] final class DerivedOptimizableStructure[P <: Product, Elems <: Tuple, N <: Int](
      m     : Mirror.ProductOf[P] { type MirroredElemTypes = Elems },
      fields: FieldStructures[Elems, N],
      labels: List[String]
  ) extends OptimizableStructure.Of[P, N]:
    private def elements(program: P): Elems = Tuple.fromProductTyped(program)(using m)

    override def arity(program: P): Int = fields.replacement(elements(program))._1

    /** Names each optimizable leaf by its case-class field path (P-c). A field whose value is a leaf predict gets just
      * the field label (its leaf name "self" is collapsed); a nested composite field yields `"field.sub"`.
      */
    def inspectNamed(program: P): Vector[(String, OptimizableView)] =
      fields.inspectNamedByField(elements(program)).zip(labels).flatMap { case (named, label) =>
        named.map { case (sub, view) => qualifiedName(label, sub) -> view }
      }.toVector

    def replace(program: P, updates: Vector[OptimizableParameters]): P =
      val (expected, distribute) = fields.replacement(elements(program))
      require(expected == updates.size, s"OptimizableStructure.replace expected $expected updates, got ${updates.size}")
      m.fromTuple(distribute(updates))

  /** Recurse over the Mirror's element types, summoning each field's fixed-arity optimizable structure.
    *
    * Each operation takes the typed tuple of field values. The instance for `Head *: Tail` applies the head structure
    * to the head value and delegates the tail, so no instance is widened and no value is cast.
    *
    * This compiler evidence retains the sum of field arities in the derived result type.
    */
  @implicitNotFound(
    "Cannot derive OptimizableStructure: every field must provide fixed-arity OptimizableStructure evidence. " +
      "Declare an explicit OptimizableStructure.empty instance for intentionally parameter-free field types."
  )
  sealed trait FieldStructures[Elems <: Tuple, N <: Int]:
    /** The named views of each field, in field order. */
    def inspectNamedByField(fields: Elems): List[Vector[(String, OptimizableView)]]

    /** Measure the fields once. The result is the update count that the fields expect, and the function that
      * distributes that many updates in field order.
      */
    def replacement(fields: Elems): (Int, Vector[OptimizableParameters] => Elems)

  given emptyFieldStructures: FieldStructures[EmptyTuple, 0] with
    def inspectNamedByField(@annotation.unused fields: EmptyTuple): List[Vector[(String, OptimizableView)]] = Nil

    def replacement(fields: EmptyTuple): (Int, Vector[OptimizableParameters] => EmptyTuple) =
      0 -> (_ => fields)

  given consFieldStructures[Head, Tail <: Tuple, NHead <: Int, NTail <: Int](using
      head: OptimizableStructure.WithArity[Head, NHead],
      tail: FieldStructures[Tail, NTail]
  ): FieldStructures[Head *: Tail, NHead + NTail] with
    def inspectNamedByField(fields: Head *: Tail): List[Vector[(String, OptimizableView)]] =
      head.inspectNamed(fields.head) :: tail.inspectNamedByField(fields.tail)

    def replacement(fields: Head *: Tail): (Int, Vector[OptimizableParameters] => Head *: Tail) =
      val headCount                   = head.arity(fields.head)
      val (tailCount, distributeTail) = tail.replacement(fields.tail)
      (headCount + tailCount) -> { updates =>
        val (headUpdates, tailUpdates) = updates.splitAt(headCount)
        head.replace(fields.head, headUpdates) *: distributeTail(tailUpdates)
      }
