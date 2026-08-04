package dspy4s.core.algebra

/** A Kleisli morphism for `F`: an ordinary input followed by an effectful output. */
type Kleisli[F[_], A, B] = A => F[B]

/** Construct the Kleisli category induced by a [[ScalaMonad]].
  *
  * Identity lifts a value through the monad's unit; composition is monadic `flatMap`. The category laws are therefore
  * exactly the monad's left-identity, right-identity, and associativity laws.
  */
def kleisliCategory[F[_]](using monad: ScalaMonad[F]): Category[AnyObject, [A, B] =>> Kleisli[F, A, B]] =
  new Category[AnyObject, [A, B] =>> Kleisli[F, A, B]]:
    def id[A: AnyObject]: Kleisli[F, A, A] = monad.pure

    extension [A, B](f: Kleisli[F, A, B])
      infix def >>>[C](g: Kleisli[F, B, C]): Kleisli[F, A, C] =
        input => monad.flatMap(f(input))(g)
