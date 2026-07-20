package dspy4s.programs.para

import dspy4s.core.contracts.IsEq
import dspy4s.core.contracts.Law
import dspy4s.core.contracts.<->
import dspy4s.programs.PredictorState

/** A category equipped with the Para operations used by optimizers.
  *
  * `params` projects a morphism's tunable parameters and `reparam` changes those parameters while preserving the
  * program shape. In dspy4s, the homogeneous parameter tensor is `Vector[PredictorState]` under concatenation.
  * `fanout` is ordered pairing over a shared input; copy is deliberately not natural for effectful morphisms because
  * sharing a computation is observably different from running it twice. As with [[Category]], each `IsEq` law is a
  * statement interpreted under the carrier's documented observational equality rather than Scala structural `==`.
  */
trait ParaCategory[P[_], Hom[_, _]] extends Category[P, Hom]:
  extension [A, B](f: Hom[A, B])
    /** The morphism's tunable parameters, in stable address order. */
    def params: Vector[PredictorState]

    /** The same program shape over new parameters. */
    def reparam(ps: Vector[PredictorState]): Hom[A, B]

  /** Run both legs on the same input, left-to-right, and tuple their outputs. */
  def fanout[I, A, B](f: Hom[I, A], g: Hom[I, B]): Hom[I, (A, B)]

  /** Compatibility name for [[fanout]]. The operation is ordered, not concurrent. */
  final def parallel[I, A, B](f: Hom[I, A], g: Hom[I, B]): Hom[I, (A, B)] = fanout(f, g)

  @Law("the identity is parameter-free")
  def paramsId[A: P]: IsEq[Vector[PredictorState]] =
    id[A].params <-> Vector.empty

  @Law("composition concatenates parameters")
  def paramsCompose[A, B, C](f: Hom[A, B], g: Hom[B, C]): IsEq[Vector[PredictorState]] =
    (f >>> g).params <-> (f.params ++ g.params)

  @Law("fan-out concatenates parameters")
  def paramsFanout[I, A, B](f: Hom[I, A], g: Hom[I, B]): IsEq[Vector[PredictorState]] =
    fanout(f, g).params <-> (f.params ++ g.params)

  /** Compatibility law name for [[paramsFanout]]. */
  final def paramsParallel[I, A, B](f: Hom[I, A], g: Hom[I, B]): IsEq[Vector[PredictorState]] =
    paramsFanout(f, g)

  @Law("reparameterization round-trip")
  def reparamRoundTrip[A, B](f: Hom[A, B]): IsEq[Vector[PredictorState]] =
    f.reparam(f.params).params <-> f.params

  @Law("reparameterization writes back (arity-matched ps)")
  def reparamWriteBack[A, B](f: Hom[A, B], ps: Vector[PredictorState]): IsEq[Vector[PredictorState]] =
    f.reparam(ps).params <-> ps
