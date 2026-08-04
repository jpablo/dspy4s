package dspy4s.programs.algebra

import dspy4s.core.algebra.{Category, IsEq, Law, <->}
import dspy4s.programs.optimization.OptimizableParameters

/** A category equipped with the parameterization operations used by optimizers.
  *
  * `params` projects a morphism's tunable parameters and `reparam` changes those parameters while preserving the
  * program shape. In dspy4s, the homogeneous parameter tensor is `Vector[OptimizableParameters]` under concatenation.
  * `fanout` is ordered pairing over a shared input; copy is deliberately not natural for effectful morphisms because
  * sharing a computation is observably different from running it twice. As with [[Category]], each `IsEq` law is a
  * statement interpreted under the carrier's documented observational equality rather than Scala structural `==`.
  */
trait ParameterizedCategory[P[_], Hom[_, _]] extends Category[P, Hom]:
  extension [A, B](f: Hom[A, B])
    /** The morphism's tunable parameters, in stable address order. */
    def params: Vector[OptimizableParameters]

    /** The same program shape over new parameters. */
    def reparam(ps: Vector[OptimizableParameters]): Hom[A, B]

  /** Run both legs on the same input, left-to-right, and tuple their outputs. */
  def fanout[I, A, B](f: Hom[I, A], g: Hom[I, B]): Hom[I, (A, B)]

  /** Compatibility name for [[fanout]]. The operation is ordered, not concurrent. */
  final def parallel[I, A, B](f: Hom[I, A], g: Hom[I, B]): Hom[I, (A, B)] = fanout(f, g)

  @Law("the identity is parameter-free")
  def paramsId[A: P]: IsEq[Vector[OptimizableParameters]] =
    id[A].params <-> Vector.empty

  @Law("composition concatenates parameters")
  def paramsCompose[A, B, C](f: Hom[A, B], g: Hom[B, C]): IsEq[Vector[OptimizableParameters]] = (f >>> g).params <->
    (f.params ++ g.params)

  @Law("fan-out concatenates parameters")
  def paramsFanout[I, A, B](f: Hom[I, A], g: Hom[I, B]): IsEq[Vector[OptimizableParameters]] =
    fanout(f, g).params <-> (f.params ++ g.params)

  /** Compatibility law name for [[paramsFanout]]. */
  final def paramsParallel[I, A, B](f: Hom[I, A], g: Hom[I, B]): IsEq[Vector[OptimizableParameters]] =
    paramsFanout(f, g)

  @Law("reparameterization round-trip")
  def reparamRoundTrip[A, B](f: Hom[A, B]): IsEq[Vector[OptimizableParameters]] =
    f.reparam(f.params).params <-> f.params

  @Law("reparameterization writes back (arity-matched ps)")
  def reparamWriteBack[A, B](f: Hom[A, B], ps: Vector[OptimizableParameters]): IsEq[Vector[OptimizableParameters]] =
    f.reparam(ps).params <-> ps
