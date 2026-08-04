package dspy4s.programs.algebra

import dspy4s.core.algebra.{AnyObject, Category, Functor, Id, ScalaProfunctor, functionCategory}
import dspy4s.core.contracts.DspyError
import dspy4s.programs.{Compose, Dimap}
import dspy4s.programs.contracts.Module

/** Fallible Scala functions as the Kleisli category of `Either[DspyError, *]`. */
type ErrorKleisli[A, B] = A => Either[DspyError, B]

given errorKleisliCategory: Category[AnyObject, ErrorKleisli] with
  def id[A: AnyObject]: ErrorKleisli[A, A] = Right(_)

  extension [A, B](f: ErrorKleisli[A, B])
    infix def >>>[C](g: ErrorKleisli[B, C]): ErrorKleisli[A, C] =
      input => f(input).flatMap(g)

/** Typed modules form a category under transparent sequential composition. */
given moduleCategory: Category[AnyObject, Module] with
  def id[A: AnyObject]: Module[A, A] = Compose.id[A]

  extension [A, B](f: Module[A, B])
    infix def >>>[C](g: Module[B, C]): Module[A, C] = Compose.andThen(f, g)

/** Embeds total Scala functions as parameter-free, lifecycle-transparent modules. */
object LiftFunctor extends Functor[AnyObject, Function1, AnyObject, Module, Id]:
  protected given source: Category[AnyObject, Function1] = functionCategory
  protected given target: Category[AnyObject, Module]    = moduleCategory

  def map[A, B](f: A => B): Module[A, B] = Compose.lift(f)

/** Embeds fallible Scala functions as parameter-free, lifecycle-transparent modules. */
object LiftEitherFunctor extends Functor[AnyObject, ErrorKleisli, AnyObject, Module, Id]:
  protected given source: Category[AnyObject, ErrorKleisli] = errorKleisliCategory
  protected given target: Category[AnyObject, Module]       = moduleCategory

  def map[A, B](f: ErrorKleisli[A, B]): Module[A, B] = Compose.liftEither(f)

/** Typed modules are contravariant in their input boundary and covariant in their semantic output boundary. */
object ModuleProfunctor extends ScalaProfunctor[Module](using functionCategory):
  def dimap[A, B, C, D](value: Module[A, B], before: C => A, after: B => D): Module[C, D] =
    Dimap(value, before, after)
