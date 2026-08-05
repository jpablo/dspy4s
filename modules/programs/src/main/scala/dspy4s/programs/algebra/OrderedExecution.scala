package dspy4s.programs.algebra

import dspy4s.algebra.{AnyObject, OrderedTensorOps}
import dspy4s.programs.compose.Compose
import dspy4s.programs.compose.andThen
import dspy4s.programs.compose.***
import dspy4s.programs.contracts.Module

/** Unrestricted executable programs with ordered tensor-like operations.
  *
  * Sequential composition is categorical on the complete prediction once structural wrappers are transparent and raw
  * evidence uses its associative accumulator with an empty identity. Independent-input execution stays ordered and
  * therefore deliberately has no interchange law.
  */
given orderedProgram: OrderedTensorOps[Module] with
  def id[A: AnyObject]: Module[A, A] = Compose.id[A]

  extension [A, B](f: Module[A, B])
    infix def >>>[C](g: Module[B, C]): Module[A, C] =
      f.andThen(g)

  def tensor[A, B, C, D](f: Module[A, C], g: Module[B, D]): Module[(A, B), (C, D)] =
    f *** g

  def swap[A, B]: Module[(A, B), (B, A)] = Compose.swap[A, B]
  def copy[A]: Module[A, (A, A)]         = Compose.copy[A]
  def discard[A]: Module[A, Unit]        = Compose.discard[A]
