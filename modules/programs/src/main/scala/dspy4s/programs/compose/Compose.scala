package dspy4s.programs.compose

import dspy4s.core.contracts.DspyError
import dspy4s.programs.contracts.Module

/** Public factories for value-level category composition and ordered shared- or independent-input pairing.
  *
  * Carrier: a program is `Module[I, O]`. `andThen` threads the plain value `O`, while each child retains its own
  * lifecycle and the composite accumulates both raw prediction envelopes. The concrete structural nodes live in their
  * focused source files; this object is the construction façade.
  */
object Compose:
  /** The Category unit at type `I`. */
  def id[I]: Identity[I] = Identity[I]()

  /** Sequentially compose two programs, threading the first program's output value into the second. */
  def andThen[I, X, O, A <: Module[I, X], B <: Module[X, O]](
      first : A,
      second: B
  ): AndThen[I, X, O, A, B] = AndThen(first, second)

  /** Lift a total Scala function into a transparent, parameter-free program. */
  def lift[I, O](f: I => O): Lift[I, O] = Lift(f)

  /** Lift an explicitly fallible Scala function into a transparent, parameter-free program. */
  def liftEither[I, O](f: I => Either[DspyError, O]): LiftEither[I, O] = LiftEither(f)

  /** Ordered shared-input fanout (`&&&`): run `a`, then `b`, and pair their outputs. */
  def fanout[I, OA, OB, A <: Module[I, OA], B <: Module[I, OB]](
      a: A,
      b: B
  ): Both[I, OA, OB, A, B] = Both(a, b)

  /** Ordered independent-input split (`***`): run `a` on the first input, then `b` on the second. */
  def split[I, J, A, B, FA <: Module[I, A], FB <: Module[J, B]](
      a: FA,
      b: FB
  ): Tensor[I, J, A, B, FA, FB] = Tensor(a, b)

  /** Make the pair `(I, I)` from the input. The program has no parameters. It is the first half of a fan-out:
    * `fanout(a, b) = copy >>> split(a, b)`. It commutes with a deterministic program, but not with a program that
    * observes effects. Thus this is not a law of each program.
    */
  def copy[I]: Lift[I, (I, I)] = Lift(input => (input, input))

  /** Drop the input and give `()`. The program has no parameters. `f >>> discard` and `discard` return the same value,
    * but the first one also runs `f`, which can fail, spend tokens, or call tools. Thus no naturality law applies to
    * each program.
    */
  def discard[I]: Lift[I, Unit] = Lift(_ => ())

  /** Exchange two components. The program has no parameters. `swap >>> swap = id` is true for the values, but the
    * ordered execution of programs with effects stays not symmetric.
    */
  def swap[I, J]: Lift[(I, J), (J, I)] = Lift(_.swap)

  /** Non-learnable control middleware: `mode(m)(p)` runs `p` with its per-call controls rewritten by `m` (temperature /
    * rolloutId / traceEnabled). See [[Mode]].
    */
  def mode[I, O, P <: Module[I, O]](m: Mode)(p: P): Moded[I, O, P] = Moded(m, p)

/** Fluent composition syntax. Import `dspy4s.programs.*` to use these extensions. */
extension [I, X, A <: Module[I, X]](self: A)
  /** Named form of sequential composition. */
  def andThen[O, B <: Module[X, O]](next: B): AndThen[I, X, O, A, B] =
    AndThen(self, next)

  /** Operator form of [[andThen]]. */
  infix def >>>[O, B <: Module[X, O]](next: B): AndThen[I, X, O, A, B] =
    AndThen(self, next)

  /** Fluent ordered shared-input fanout. */
  def fanout[O, B <: Module[I, O]](other: B): Both[I, X, O, A, B] =
    Both(self, other)

  /** Arrow operator for ordered shared-input [[fanout]]. */
  infix def &&&[O, B <: Module[I, O]](other: B): Both[I, X, O, A, B] =
    Both(self, other)

  /** Fluent ordered independent-input split. */
  def split[J, O, B <: Module[J, O]](other: B): Tensor[I, J, X, O, A, B] =
    Tensor(self, other)

  /** Arrow operator for ordered independent-input [[split]]. */
  infix def ***[J, O, B <: Module[J, O]](other: B): Tensor[I, J, X, O, A, B] =
    Tensor(self, other)

  /** Apply non-learnable control middleware to this program. */
  def mode(value: Mode): Moded[I, X, A] = Moded(value, self)
