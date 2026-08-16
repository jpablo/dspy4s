package dspy4s.algebra

import dspy4s.algebra.Optic.*

/** A lawful lens: a focused `get`/`set` pair from a whole `S` onto a part `A`, carrying the three classic laws and
  * three modify laws on the trait as `@Law`/[[IsEq]] statements (the same shape as [[Monoid]]; see `Laws.scala`).
  * Concrete types provide `given` instances; the law suites execute the statements under the equality honest for each
  * carrier.
  *
  * An instance is only lawful when `A` is exactly the writable part of `S`: a focus smaller than what `set` touches
  * breaks Put-Get, one larger than what `set` accepts breaks Get-Put. The prime example is
  * `dspy4s.programs.optimization.OptimizableLeaf`, whose focus `dspy4s.programs.optimization.OptimizableParameters` was
  * carved out of the executable predictor precisely so these laws hold.
  */
trait Lens[S, A] extends Optic[S, S, A, A, Tuple2]:
  final type X = S

  /** Read the focused part. */
  def get(s: S): A

  /** Replace the focused part, leaving the rest of `S` unchanged. */
  def set(s: S, a: A): S

  final def to(s: S): (S, A) = s -> get(s)

  final def from(focus: (S, A)): S = set(focus._1, focus._2)

  @Law("get-put: writing back the value just read changes nothing")
  def getPut(s: S): IsEq[S] =
    set(s, get(s)) <-> s

  @Law("put-get: reading after a write yields the written value")
  def putGet(s: S, a: A): IsEq[A] =
    get(set(s, a)) <-> a

  @Law("put-put: the last write wins")
  def putPut(s: S, a1: A, a2: A): IsEq[S] =
    set(set(s, a1), a2) <-> set(s, a2)

  @Law("modify identity: modifying with identity changes nothing")
  def modifyIdentity(s: S): IsEq[S] =
    this.modify(s)(identity) <-> s

  @Law("modify composition: sequential modifications compose")
  def modifyComposition(s: S, f: A => A, g: A => A): IsEq[S] =
    this.modify(this.modify(s)(f))(g) <-> this.modify(s)(f.andThen(g))

  @Law("set-modify consistency: setting a value equals a constant modification")
  def consistentSetModify(s: S, a: A): IsEq[S] =
    set(s, a) <-> this.modify(s)(_ => a)

object Lens:
  /** Summon the instance focusing `S` onto `A`. */
  def apply[S, A](using l: Lens[S, A]): Lens[S, A] = l
