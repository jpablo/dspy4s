package dspy4s.programs.para

import dspy4s.core.contracts.IsEq
import dspy4s.core.contracts.Law
import dspy4s.core.contracts.<->
import dspy4s.programs.DynamicPredict

/** A category equipped with the Para operations used by optimizers.
  *
  * `params` projects a morphism's tunable parameters and `reparam` changes those parameters while preserving the
  * program shape. In dspy4s, the homogeneous parameter tensor is `Vector[DynamicPredict]` under concatenation.
  * `parallel` is ordered fan-out over a shared input; copy is deliberately not natural for effectful morphisms because
  * sharing a computation is observably different from running it twice.
  */
trait ParaCategory[P[_], Hom[_, _]] extends Category[P, Hom]:
  extension [A, B](f: Hom[A, B])
    /** The morphism's tunable parameters, in stable address order. */
    def params: Vector[DynamicPredict]

    /** The same program shape over new parameters. */
    def reparam(ps: Vector[DynamicPredict]): Hom[A, B]

  /** Run both legs on the same input and tuple their outputs. */
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
