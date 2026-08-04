package dspy4s.programs

import dspy4s.programs.optimization.*
import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TransparentModule
import dspy4s.programs.contracts.ProgramCall
import dspy4s.typed.Prediction

import scala.compiletime.ops.int.+

/** The program-composition combinators `id` / `>>>` / `fanout` / `split` — value-level category composition plus
  * ordered shared- and independent-input pairing (`docs/refactor/algebra-2-program-composition.md`).
  *
  * Carrier (the grill's fork-1/5 decision): a program is `Module[I, O]`. `>>>` threads the plain typed value `O` (not
  * the `Prediction[O]` envelope): it runs the first program, feeds its `prediction.output` into a fresh `ProgramCall`
  * that inherits the outer call's controls (`config` / `traceEnabled` / `rolloutId`), and runs the second. Each
  * sub-program's own `apply` records its trace/history entry, while the composite result accumulates both
  * `Prediction.raw` envelopes with [[RawPrediction.followedBy]].
  *
  * Structural lifecycle. These nodes extend [[dspy4s.programs.contracts.TransparentModule]], so only their leaf
  * children emit callbacks, trace, and history. Association and identity syntax therefore cannot change the runtime
  * observations visible to a later leaf.
  *
  * Optimizer-addressability (fork 4): the combinators are concretely typed in their child programs (`A` / `B`), and
  * their hand-written [[OptimizableTraversal]] instances distribute `read` / `replace` structurally (`read(a) ++
  * read(b)`), so teleprompters can introspect and tune the predicts inside a pipeline.
  */

/** `id[I]` — the Category unit: a pure passthrough that returns its input as the output, with an empty raw envelope.
  * Sequential composition accumulates envelopes through [[RawPrediction.followedBy]], for which the empty envelope is
  * an identity, so both `id >>> p` and `p >>> id` preserve the complete prediction.
  */
final case class Identity[I]() extends TransparentModule[I, I]:
  override val moduleName: String                                                                              = "id"
  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[I]] =
    Right(Prediction(call.input, RawPrediction.empty))

object Identity:
  given identityOptimizableTraversal[I]: OptimizableTraversal.WithArity[Identity[I], 0] =
    OptimizableTraversal.empty

/** `a >>> b` — sequential (dependent) composition: run `a`, thread its output value into `b`. The Category operation.
  */
final case class AndThen[I, X, O, A <: Module[I, X], B <: Module[X, O]](
    first: A,
    second: B
) extends TransparentModule[I, O]:
  override val moduleName: String = "and_then"

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    first(call).flatMap { predX =>
      // The outer call's controls pass through unchanged; combine the evidence envelopes after the carrier runs.
      second(call.mapInput(_ => predX.output))
        .map(predO => predO.copy(raw = predX.raw.followedBy(predO.raw)))
    }

/** Shared `OptimizableTraversal` distribution for the two-child combinators ([[AndThen]], [[Both]]): structural
  * `inspect(first)
  * ++ inspect(second)`, `replace` slicing by `first`'s read-arity, and `first.` / `second.` name prefixing (fork 4).
  * One implementation keeps optimizer addressing in sync between `>>>` and `parallel` — a change to the slicing or path
  * naming applied to one combinator cannot silently miss the other.
  */
private[programs] object PairOptimizableTraversal:
  def inspect[A, B](
      pa: OptimizableTraversal[A],
      pb: OptimizableTraversal[B]
  )(first: A, second: B): Vector[OptimizableView] =
    pa.inspect(first) ++ pb.inspect(second)

  def replace[A, B, P](pa: OptimizableTraversal[A], pb: OptimizableTraversal[B])(
      first: A,
      second: B,
      updates: Vector[OptimizableParameters]
  )(
      rebuild: (A, B) => P
  ): P =
    val (firstUpdates, secondUpdates) = updates.splitAt(pa.read(first).size)
    rebuild(pa.replace(first, firstUpdates), pb.replace(second, secondUpdates))

  def inspectNamed[A, B](
      pa: OptimizableTraversal[A],
      pb: OptimizableTraversal[B]
  )(first: A, second: B): Vector[(String, OptimizableView)] =
    pa.inspectNamed(first).map { case (sub, view) =>
      (if sub == "self" then "first" else s"first.$sub") -> view
    } ++
      pb.inspectNamed(second).map { case (sub, view) =>
        (if sub == "self" then "second" else s"second.$sub") -> view
      }

object AndThen:
  /** Structural `read(a) ++ read(b)`; `replace` slices the updates by `first`'s read-arity (fork 4). */
  given andThenOptimizableTraversal[
      I,
      X,
      O,
      A <: Module[I, X],
      B <: Module[X, O],
      NA <: Int,
      NB <: Int
  ](
      using
      pa: OptimizableTraversal.WithArity[A, NA],
      pb: OptimizableTraversal.WithArity[B, NB]
  ): OptimizableTraversal.Of[AndThen[I, X, O, A, B], NA + NB] with
    def arity(program: AndThen[I, X, O, A, B]): Int = pa.arity(program.first) + pb.arity(program.second)
    def inspect(program: AndThen[I, X, O, A, B]): Vector[OptimizableView] =
      PairOptimizableTraversal.inspect(pa, pb)(program.first, program.second)

    def replace(program: AndThen[I, X, O, A, B], updates: Vector[OptimizableParameters]): AndThen[I, X, O, A, B] =
      PairOptimizableTraversal.replace(pa, pb)(program.first, program.second, updates)((a, b) =>
        program.copy(first = a, second = b)
      )

    override def inspectNamed(program: AndThen[I, X, O, A, B]): Vector[(String, OptimizableView)] =
      PairOptimizableTraversal.inspectNamed(pa, pb)(program.first, program.second)

/** `fanout(a, b)` (compatibility name `parallel`) — run both programs on the same input and tuple their outputs. On the
  * synchronous `Either` substrate the two attempts run left-to-right and fail fast; this is Arrow-like `&&&`, not
  * concurrent execution and not by itself an `Applicative` instance. The result's raw merges both sub-predictions'
  * value records (`second` wins on a key collision).
  */
final case class Both[I, OA, OB, A <: Module[I, OA], B <: Module[I, OB]](
    first: A,
    second: B
) extends TransparentModule[I, (OA, OB)]:
  override val moduleName: String = "parallel"

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[(OA, OB)]] =
    for
      predA <- first(call)
      predB <- second(call)
    yield Prediction(
      output = (predA.output, predB.output),
      raw = RawPrediction(values = DynamicValues.mergeRecords(predA.raw.values, predB.raw.values))
    )

object Both:
  /** Same structural distribution as [[AndThen.andThenOptimizableTraversal]], via [[PairOptimizableTraversal]]. */
  given bothOptimizableTraversal[
      I,
      OA,
      OB,
      A <: Module[I, OA],
      B <: Module[I, OB],
      NA <: Int,
      NB <: Int
  ](
      using
      pa: OptimizableTraversal.WithArity[A, NA],
      pb: OptimizableTraversal.WithArity[B, NB]
  ): OptimizableTraversal.Of[Both[I, OA, OB, A, B], NA + NB] with
    def arity(program: Both[I, OA, OB, A, B]): Int = pa.arity(program.first) + pb.arity(program.second)
    def inspect(program: Both[I, OA, OB, A, B]): Vector[OptimizableView] =
      PairOptimizableTraversal.inspect(pa, pb)(program.first, program.second)

    def replace(program: Both[I, OA, OB, A, B], updates: Vector[OptimizableParameters]): Both[I, OA, OB, A, B] =
      PairOptimizableTraversal.replace(pa, pb)(program.first, program.second, updates)((a, b) =>
        program.copy(first = a, second = b)
      )

    override def inspectNamed(program: Both[I, OA, OB, A, B]): Vector[(String, OptimizableView)] =
      PairOptimizableTraversal.inspectNamed(pa, pb)(program.first, program.second)

/** `split(a, b)` (compatibility name `tensor`) — run two programs left-to-right on independent inputs and pair both
  * outputs. It is the operation beneath shared-input fan-out: `fanout(a, b) = copy >>> split(a, b)`. Because `Either`
  * failures and runtime effects make that order observable, this operation is not a bifunctorial monoidal tensor on
  * unrestricted modules. Unlike `parallel` it does NOT lift into the packaged `Program`/`ParameterizedCategory`: its
  * input `(I, J)` has no canonical single-record decoder (fan-out reuses the shared input's decoder; the tensor's two
  * inputs don't), so it lives at the `Module` level. Result raw merges both sub-predictions' records (`second` wins on
  * collision).
  */
final case class Tensor[
    I,
    J,
    A,
    B,
    FA <: Module[I, A],
    FB <: Module[J, B]
](
    first: FA,
    second: FB
) extends TransparentModule[(I, J), (A, B)]:
  override val moduleName: String = "tensor"

  override protected def forward(call: ProgramCall[(I, J)])(using
      RuntimeContext
  ): Either[DspyError, Prediction[(A, B)]] =
    for
      predA <- first(call.mapInput(_._1))
      predB <- second(call.mapInput(_._2))
    yield Prediction(
      output = (predA.output, predB.output),
      raw = RawPrediction(values = DynamicValues.mergeRecords(predA.raw.values, predB.raw.values))
    )

object Tensor:
  /** Structural `read(a) ++ read(b)`, same distribution as `AndThen` / `Both` (via [[PairOptimizableTraversal]]). */
  given tensorOptimizableTraversal[
      I,
      J,
      A,
      B,
      FA <: Module[I, A],
      FB <: Module[J, B],
      NA <: Int,
      NB <: Int
  ](
      using
      pa: OptimizableTraversal.WithArity[FA, NA],
      pb: OptimizableTraversal.WithArity[FB, NB]
  ): OptimizableTraversal.Of[Tensor[I, J, A, B, FA, FB], NA + NB] with
    def arity(program: Tensor[I, J, A, B, FA, FB]): Int = pa.arity(program.first) + pb.arity(program.second)
    def inspect(program: Tensor[I, J, A, B, FA, FB]): Vector[OptimizableView] =
      PairOptimizableTraversal.inspect(pa, pb)(program.first, program.second)

    def replace(
        program: Tensor[I, J, A, B, FA, FB],
        updates: Vector[OptimizableParameters]
    ): Tensor[I, J, A, B, FA, FB] =
      PairOptimizableTraversal.replace(pa, pb)(program.first, program.second, updates)((a, b) =>
        program.copy(first = a, second = b)
      )

    override def inspectNamed(program: Tensor[I, J, A, B, FA, FB]): Vector[(String, OptimizableView)] =
      PairOptimizableTraversal.inspectNamed(pa, pb)(program.first, program.second)

/** `copy`: duplicate the input `I` into `(I, I)`. Parameter-free (like `id`); the first half of a fan-out, so
  * `parallel(a, b) = copy >>> tensor(a, b)`. Copy commutes with deterministic programs but not effect-observing
  * programs; this is a useful classifier rather than a law of the unrestricted execution carrier.
  */
final case class Copy[I]() extends TransparentModule[I, (I, I)]:
  override val moduleName: String = "copy"
  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[(I, I)]] =
    Right(Prediction((call.input, call.input), RawPrediction.empty))

object Copy:
  given copyOptimizableTraversal[I]: OptimizableTraversal.WithArity[Copy[I], 0] =
    OptimizableTraversal.empty

/** `discard`: drop the input, producing `()`. Parameter-free. Although `f >>> discard` and `discard` return the same
  * value, the former still runs `f` and can fail, spend tokens, or invoke tools. No naturality law is claimed for
  * unrestricted executable programs.
  */
final case class Discard[I]() extends TransparentModule[I, Unit]:
  override val moduleName: String = "discard"
  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[Unit]] =
    Right(Prediction((), RawPrediction.empty))

object Discard:
  given discardOptimizableTraversal[I]: OptimizableTraversal.WithArity[Discard[I], 0] =
    OptimizableTraversal.empty

/** `swap`: exchange two components. Parameter-free and involutive (`swap >>> swap = id`) as a structural value
  * transformation; it does not make ordered effectful execution symmetric.
  */
final case class Swap[I, J]() extends TransparentModule[(I, J), (J, I)]:
  override val moduleName: String = "swap"
  override protected def forward(call: ProgramCall[(I, J)])(using
      RuntimeContext
  ): Either[DspyError, Prediction[(J, I)]] =
    val (i, j) = call.input
    Right(Prediction((j, i), RawPrediction.empty))

object Swap:
  given swapOptimizableTraversal[I, J]: OptimizableTraversal.WithArity[Swap[I, J], 0] =
    OptimizableTraversal.empty

/** The composition combinators as functions / operators. `lift` / `id` / `fanout` / `split` / `copy` / `discard` /
  * `swap` are plain factories; `>>>`, variance transforms, and recovery also have fluent extensions. Import
  * `dspy4s.programs.*` (or this object's members) to use them.
  */
object Compose:
  /** The Category unit at type `I`. */
  def id[I]: Identity[I] = Identity[I]()

  /** Sequentially compose two programs, threading the first program's output value into the second. */
  def andThen[I, X, O, A <: Module[I, X], B <: Module[X, O]](
      first: A,
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

  /** Compatibility name for [[fanout]]. This operation is ordered, not concurrent. */
  def parallel[I, OA, OB, A <: Module[I, OA], B <: Module[I, OB]](
      a: A,
      b: B
  ): Both[I, OA, OB, A, B] = fanout(a, b)

  /** Ordered independent-input split (`***`): run `a` on the first input, then `b` on the second. */
  def split[I, J, A, B, FA <: Module[I, A], FB <: Module[J, B]](
      a: FA,
      b: FB
  ): Tensor[I, J, A, B, FA, FB] = Tensor(a, b)

  /** Compatibility name for [[split]]. */
  def tensor[I, J, A, B, FA <: Module[I, A], FB <: Module[J, B]](
      a: FA,
      b: FB
  ): Tensor[I, J, A, B, FA, FB] = split(a, b)

  /** Try `primary`, then a fixed fallback only when `policy` selects the primary error. */
  def recover[I, O, P <: Module[I, O], F <: Module[I, O]](
      primary: P,
      fallback: F,
      policy: RecoveryPolicy
  ): RecoverWith[I, O, P, F] = RecoverWith(primary, fallback, policy)

  /** Duplicate the input into `(I, I)`. The first half of a fan-out. */
  def copy[I]: Copy[I] = Copy[I]()

  /** Drop the input. */
  def discard[I]: Discard[I] = Discard[I]()

  /** Exchange two components. */
  def swap[I, J]: Swap[I, J] = Swap[I, J]()

  /** Non-learnable control middleware: `mode(m)(p)` runs `p` with its per-call controls rewritten by `m` (model /
    * temperature / rolloutId / traceEnabled). See [[Mode]].
    */
  def mode[I, O, P <: Module[I, O]](m: Mode)(p: P): Moded[I, O, P] = Moded(m, p)

/** `a >>> b`: sequential composition. Defined at package level so it is available wherever the programs package is in
  * scope (or via `import dspy4s.programs.*`).
  */
extension [I, X, A <: Module[I, X]](self: A)
  /** Named form of sequential composition. */
  def andThen[O, B <: Module[X, O]](next: B): AndThen[I, X, O, A, B] =
    Compose.andThen(self, next)

  /** Operator form of [[andThen]]. */
  infix def >>>[O, B <: Module[X, O]](next: B): AndThen[I, X, O, A, B] =
    self.andThen(next)

  /** Fluent ordered shared-input fanout. */
  def fanout[O, B <: Module[I, O]](other: B): Both[I, X, O, A, B] =
    Compose.fanout(self, other)

  /** Arrow operator for ordered shared-input [[fanout]]. */
  infix def &&&[O, B <: Module[I, O]](other: B): Both[I, X, O, A, B] =
    self.fanout(other)

  /** Compatibility name for [[fanout]]. */
  def parallel[O, B <: Module[I, O]](other: B): Both[I, X, O, A, B] =
    self.fanout(other)

  /** Fluent ordered independent-input split. */
  def split[J, O, B <: Module[J, O]](other: B): Tensor[I, J, X, O, A, B] =
    Compose.split(self, other)

  /** Arrow operator for ordered independent-input [[split]]. */
  infix def ***[J, O, B <: Module[J, O]](other: B): Tensor[I, J, X, O, A, B] =
    self.split(other)

  /** Compatibility name for [[split]]. */
  def tensor[J, O, B <: Module[J, O]](other: B): Tensor[I, J, X, O, A, B] =
    self.split(other)

  /** Apply non-learnable control middleware to this program. */
  def mode(value: Mode): Moded[I, X, A] = Compose.mode(value)(self)
