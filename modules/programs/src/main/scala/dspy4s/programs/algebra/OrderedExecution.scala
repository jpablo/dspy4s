package dspy4s.programs.algebra

import dspy4s.core.algebra.{AnyObject, Category}
import dspy4s.programs.Compose
import dspy4s.programs.andThen
import dspy4s.programs.***
import dspy4s.programs.contracts.Module

/** Plain executable program morphisms without optimizer-addressability or decoder evidence. */
type ModuleHom[I, O] = Module[I, O]

/** A category equipped with independent-input pairing, with no functoriality or coherence laws assumed. */
trait TensorOps[Hom[_, _]] extends Category[AnyObject, Hom]:
  /** Run `f` on the first input, then `g` on the second, and pair their outputs. */
  def tensor[A, B, C, D](f: Hom[A, C], g: Hom[B, D]): Hom[(A, B), (C, D)]

/** A category with ordered independent-input execution and structural swap/copy/discard operations.
  *
  * No bifunctoriality, symmetry, or discard-naturality laws are asserted: fail-fast errors and ordinary runtime effects
  * make execution order observable. A commutative denotational carrier may implement the stronger
  * [[CopyDiscardCategory]].
  */
trait OrderedTensorOps[Hom[_, _]] extends TensorOps[Hom]:
  def swap[A, B]: Hom[(A, B), (B, A)]
  def copy[A]: Hom[A, (A, A)]
  def discard[A]: Hom[A, Unit]

/** Unrestricted executable programs with ordered tensor-like operations.
  *
  * Sequential composition is categorical on the complete prediction once structural wrappers are transparent and raw
  * evidence uses its associative accumulator with an empty identity. Independent-input execution stays ordered and
  * therefore deliberately has no interchange law.
  */
given orderedProgram: OrderedTensorOps[ModuleHom] with
  def id[A: AnyObject]: ModuleHom[A, A] = Compose.id[A]

  extension [A, B](f: ModuleHom[A, B])
    infix def >>>[C](g: ModuleHom[B, C]): ModuleHom[A, C] =
      f.andThen(g)

  def tensor[A, B, C, D](f: ModuleHom[A, C], g: ModuleHom[B, D]): ModuleHom[(A, B), (C, D)] =
    f *** g

  def swap[A, B]: ModuleHom[(A, B), (B, A)] = Compose.swap[A, B]
  def copy[A]: ModuleHom[A, (A, A)]         = Compose.copy[A]
  def discard[A]: ModuleHom[A, Unit]        = Compose.discard[A]
