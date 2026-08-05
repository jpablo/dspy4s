package dspy4s.programs.algebra

import dspy4s.algebra.{
  AnyObject,
  Category,
  Functor,
  Id,
  Kleisli,
  ScalaProfunctor,
  functionCategory,
  kleisliCategory
}
import dspy4s.core.contracts.DspyError
import dspy4s.programs.compose.{Compose, Dimap}
import dspy4s.programs.contracts.Module

/** Fallible Scala functions as the Kleisli category of `Either[DspyError, *]`. */
type ErrorKleisli[A, B] = Kleisli[[X] =>> Either[DspyError, X], A, B]

given errorKleisliCategory: Category[AnyObject, ErrorKleisli] =
  kleisliCategory[[X] =>> Either[DspyError, X]]

/** Typed modules form a category under transparent sequential composition. */
given moduleCategory: Category[AnyObject, Module] with
  def id[A: AnyObject]: Module[A, A] = Compose.id[A]

  extension [A, B](f: Module[A, B])
    infix def >>>[C](g: Module[B, C]): Module[A, C] = Compose.andThen(f, g)

/** Embeds total Scala functions as parameter-free, lifecycle-transparent modules. */
object LiftFunctor
    extends Functor[Id, AnyObject, Function1, AnyObject, Module](using
      functionCategory,
      moduleCategory
    ):
  def mapObject[A](using AnyObject[A]): AnyObject[A] = summon
  def map[A, B](f: A => B): Module[A, B]             = Compose.lift(f)

/** Embeds fallible Scala functions as parameter-free, lifecycle-transparent modules. */
object LiftEitherFunctor
    extends Functor[Id, AnyObject, ErrorKleisli, AnyObject, Module](using
      errorKleisliCategory,
      moduleCategory
    ):
  def mapObject[A](using AnyObject[A]): AnyObject[A] = summon
  def map[A, B](f: ErrorKleisli[A, B]): Module[A, B] = Compose.liftEither(f)

/** Typed modules are contravariant in their input boundary and covariant in their semantic output boundary. */
object ModuleProfunctor extends ScalaProfunctor[Module](using functionCategory):
  def dimap[A, B, C, D](value: Module[A, B], before: C => A, after: B => D): Module[C, D] =
    Dimap(value, before, after)
