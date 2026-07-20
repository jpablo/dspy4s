package dspy4s.programs.para

import dspy4s.core.contracts.IsEq
import dspy4s.core.contracts.Law
import dspy4s.core.contracts.<->
import dspy4s.programs.AndThen
import dspy4s.programs.Copy
import dspy4s.programs.Discard
import dspy4s.programs.Identity
import dspy4s.programs.Swap
import dspy4s.programs.Tensor
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ProgramCall
import dspy4s.typed.Prediction

/** Plain executable program morphisms without optimizer-addressability or decoder evidence. */
type ModuleHom[I, O] = Module[ProgramCall[I], Prediction[O]]

/** A category with ordered independent-input execution and structural swap/copy/discard operations.
  *
  * No bifunctoriality, symmetry, or discard-naturality laws are asserted: fail-fast errors and ordinary runtime effects
  * make execution order observable. A commutative denotational carrier may implement the stronger [[CDCategory]].
  */
trait OrderedTensorOps[Hom[_, _]] extends Category[AnyObject, Hom]:
  /** Run `f` on the first input, then `g` on the second, and pair their outputs. */
  def tensor[A, B, C, D](f: Hom[A, C], g: Hom[B, D]): Hom[(A, B), (C, D)]

  def swap[A, B]: Hom[(A, B), (B, A)]
  def copy[A]: Hom[A, (A, A)]
  def discard[A]: Hom[A, Unit]

/** A copy-discard category: a symmetric monoidal category in which each object carries a commutative comonoid. This law
  * vocabulary is intentionally not instantiated for unrestricted executable [[ModuleHom]]s because their failures and
  * effects are ordered.
  *
  * Under output-observational equality, discard is natural and the unit is terminal, yielding a Markov category. Copy
  * is natural exactly for deterministic morphisms; [[copyNaturality]] is therefore a classifier rather than an
  * unconditional law.
  *
  * These partial laws omit unitors, associators, counit/coassociativity, and coherence laws. The trait is a target
  * interface, not a claim about current execution.
  */
trait CDCategory[Hom[_, _]] extends OrderedTensorOps[Hom]:

  @Law("tensor is a bifunctor (interchange)")
  def tensorInterchange[A, B, C, D, E, F2](
      f1: Hom[A, C],
      g1: Hom[B, D],
      f2: Hom[C, E],
      g2: Hom[D, F2]
  ): IsEq[Hom[(A, B), (E, F2)]] =
    (tensor(f1, g1) >>> tensor(f2, g2)) <-> tensor(f1 >>> f2, g1 >>> g2)

  @Law("tensor preserves identities")
  def tensorIdentity[A, B]: IsEq[Hom[(A, B), (A, B)]] =
    tensor(id[A], id[B]) <-> id[(A, B)]

  @Law("swap is involutive (symmetry)")
  def swapInvolution[A, B]: IsEq[Hom[(A, B), (A, B)]] =
    (swap[A, B] >>> swap[B, A]) <-> id[(A, B)]

  @Law("copy is cocommutative")
  def cocommutativity[A]: IsEq[Hom[A, (A, A)]] =
    (copy[A] >>> swap[A, A]) <-> copy[A]

  @Law("discard is natural (unit terminal) — under output-observational equality")
  def discardNatural[A, B](f: Hom[A, B]): IsEq[Hom[A, Unit]] =
    (f >>> discard[B]) <-> discard[A]

  /** `copy` commutes with `f` iff `f` is deterministic. This is deliberately not annotated as a law. */
  def copyNaturality[A, B](f: Hom[A, B]): IsEq[Hom[A, (B, B)]] =
    (f >>> copy[B]) <-> (copy[A] >>> tensor(f, f))

/** Unrestricted executable programs with ordered tensor-like operations.
  *
  * Sequential composition is categorical on the threaded value and lifecycle once structural wrappers are transparent.
  * The final `Prediction.raw` envelope remains outside that observation. Independent-input execution stays ordered and
  * therefore deliberately has no interchange law.
  */
given orderedProgram: OrderedTensorOps[ModuleHom] with
  def id[A: AnyObject]: ModuleHom[A, A] = Identity[A]()

  extension [A, B](f: ModuleHom[A, B])
    infix def >>>[C](g: ModuleHom[B, C]): ModuleHom[A, C] =
      AndThen[A, B, C, ModuleHom[A, B], ModuleHom[B, C]](f, g)

  def tensor[A, B, C, D](f: ModuleHom[A, C], g: ModuleHom[B, D]): ModuleHom[(A, B), (C, D)] =
    Tensor[A, B, C, D, ModuleHom[A, C], ModuleHom[B, D]](f, g)

  def swap[A, B]: ModuleHom[(A, B), (B, A)] = Swap[A, B]()
  def copy[A]: ModuleHom[A, (A, A)]         = Copy[A]()
  def discard[A]: ModuleHom[A, Unit]        = Discard[A]()
