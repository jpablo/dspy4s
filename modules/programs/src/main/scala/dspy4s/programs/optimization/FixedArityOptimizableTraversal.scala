package dspy4s.programs.optimization

import dspy4s.core.algebra.{IsEq, Law, Lens, <->}
import dspy4s.core.collections.SizedVector

/** An [[OptimizableTraversal]] whose number of writable leaves is fixed by the program type.
  *
  * The ordinary `Vector` API remains available through [[OptimizableTraversal]]. [[readSized]] and [[replaceSized]]
  * expose the stronger contract used by the lawful parameter lens: after a runtime vector has crossed a checked
  * boundary, replacement cannot fail because of arity.
  */
trait FixedArityOptimizableTraversal[P] extends OptimizableTraversal[P]:
  type Arity <: Int

  /** Runtime reflection of [[Arity]], used by compatibility APIs that still accept an unsized `Vector`. */
  def arity: Int

  /** Read parameters while retaining the statically known arity. */
  final def readSized(program: P): SizedVector[OptimizableParameters, Arity] =
    SizedVector.assumeSize(read(program))

  /** Replace parameters whose type already proves the correct arity. */
  final def replaceSized(program: P, updates: SizedVector[OptimizableParameters, Arity]): P =
    replace(program, updates)

  /** The lawful focus from a fixed-shape program onto all of its writable parameters. */
  final def parameterLens: Lens[P, SizedVector[OptimizableParameters, Arity]] =
    new Lens[P, SizedVector[OptimizableParameters, Arity]]:
      def get(program: P): SizedVector[OptimizableParameters, Arity]             = readSized(program)
      def set(program: P, updates: SizedVector[OptimizableParameters, Arity]): P = replaceSized(program, updates)

  @Law("the runtime traversal cardinality agrees with its static arity")
  final def arityAgreement(program: P): IsEq[Int] =
    read(program).size <-> arity

object FixedArityOptimizableTraversal:
  type WithArity[P, N <: Int] = FixedArityOptimizableTraversal[P] { type Arity = N }

  /** Nominal implementation base for givens whose arity should remain visible in their public result type. */
  trait Of[P, N <: Int] extends FixedArityOptimizableTraversal[P]:
    final type Arity = N

  /** Summon a fixed-arity traversal. */
  def apply[P](using traversal: FixedArityOptimizableTraversal[P]): FixedArityOptimizableTraversal[P] = traversal

  /** A fixed traversal is also the canonical lawful lens onto its complete parameter vector. */
  given parameterLens[P, N <: Int](using
      traversal: WithArity[P, N]
  ): Lens[P, SizedVector[OptimizableParameters, N]] = traversal.parameterLens
