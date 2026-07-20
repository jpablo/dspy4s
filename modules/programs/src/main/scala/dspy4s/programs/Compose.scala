package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TransparentModule
import dspy4s.programs.contracts.ProgramCall
import dspy4s.typed.Prediction

/** The program-composition combinators `id` / `>>>` / `fanout` / `split` — value-level category composition plus
  * ordered shared- and independent-input pairing (`docs/refactor/algebra-2-program-composition.md`).
  *
  * Carrier (the grill's fork-1/5 decision): a program is `Module[ProgramCall[I], Prediction[O]]`. `>>>` threads the
  * plain typed value `O` (not the `Prediction[O]` envelope): it runs the first program, feeds its `prediction.output`
  * into a fresh `ProgramCall` that inherits the outer call's controls (`config` / `traceEnabled` / `rolloutId`), and
  * runs the second. Each sub-program's own `apply` records its trace/history entry, so the intermediate
  * `Prediction.raw` (reasoning / completions / per-step usage) is captured in the trace rather than carried onto the
  * composite result.
  *
  * Structural lifecycle. These nodes extend [[dspy4s.programs.contracts.TransparentModule]], so only their leaf
  * children emit callbacks, trace, and history. Association and identity syntax therefore cannot change the runtime
  * observations visible to a later leaf.
  *
  * Optimizer-addressability (fork 4): the combinators are concretely typed in their child programs (`A` / `B`), and
  * their hand-written [[Predictors]] instances distribute `read` / `replace` structurally (`read(a) ++ read(b)`), so
  * teleprompters can introspect and tune the predicts inside a pipeline.
  */

/** `id[I]` — the Category unit: a pure passthrough that returns its input as the output, with an empty raw envelope.
  * `id >>> p` is `p` (the left unit contributes nothing to the final prediction); `p >>> id` equals `p` on the threaded
  * output value (the right unit's empty raw becomes the result raw — the carrier's value-vs-envelope split, see the law
  * suite).
  */
final case class Identity[I]() extends TransparentModule[ProgramCall[I], Prediction[I]]:
  override val moduleName: String = "id"
  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[I]] =
    Right(Prediction(call.input, DynamicPrediction.empty))

object Identity:
  given identityPredictors[I]: Predictors[Identity[I]] = Predictors.empty

/** `a >>> b` — sequential (dependent) composition: run `a`, thread its output value into `b`. The Category operation.
  */
final case class AndThen[I, X, O, A <: Module[ProgramCall[I], Prediction[X]], B <: Module[
  ProgramCall[X],
  Prediction[O]
]](
    first: A,
    second: B
) extends TransparentModule[ProgramCall[I], Prediction[O]]:
  override val moduleName: String = "and_then"

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    first.apply(call).flatMap { predX =>
      // Map only the carrier; the outer call's controls pass through unchanged. The Prediction envelope of `predX`
      // stays behind, recorded by `first.apply`'s own trace entry.
      second.apply(call.mapInput(_ => predX.output))
    }

/** Shared `Predictors` distribution for the two-child combinators ([[AndThen]], [[Both]]): structural `inspect(first)
  * ++ inspect(second)`, `replace` slicing by `first`'s read-arity, and `first.` / `second.` name prefixing (fork 4).
  * One implementation keeps optimizer addressing in sync between `>>>` and `parallel` — a change to the slicing or path
  * naming applied to one combinator cannot silently miss the other.
  */
private[programs] object PairPredictors:
  def inspect[A, B](pa: Predictors[A], pb: Predictors[B])(first: A, second: B): Vector[PredictorView] =
    pa.inspect(first) ++ pb.inspect(second)

  def replace[A, B, P](pa: Predictors[A], pb: Predictors[B])(first: A, second: B, updates: Vector[PredictorState])(
      rebuild: (A, B) => P
  ): P =
    val (firstUpdates, secondUpdates) = updates.splitAt(pa.read(first).size)
    rebuild(pa.replace(first, firstUpdates), pb.replace(second, secondUpdates))

  def inspectNamed[A, B](pa: Predictors[A], pb: Predictors[B])(first: A, second: B): Vector[(String, PredictorView)] =
    pa.inspectNamed(first).map { case (sub, view) =>
      (if sub == "self" then "first" else s"first.$sub") -> view
    } ++
      pb.inspectNamed(second).map { case (sub, view) =>
        (if sub == "self" then "second" else s"second.$sub") -> view
      }

object AndThen:
  /** Structural `read(a) ++ read(b)`; `replace` slices the updates by `first`'s read-arity (fork 4). */
  given andThenPredictors[I, X, O, A <: Module[ProgramCall[I], Prediction[X]], B <: Module[
    ProgramCall[X],
    Prediction[O]
  ]](
      using
      pa: Predictors[A],
      pb: Predictors[B]
  ): Predictors[AndThen[I, X, O, A, B]] with
    def inspect(program: AndThen[I, X, O, A, B]): Vector[PredictorView] =
      PairPredictors.inspect(pa, pb)(program.first, program.second)

    def replace(program: AndThen[I, X, O, A, B], updates: Vector[PredictorState]): AndThen[I, X, O, A, B] =
      PairPredictors.replace(pa, pb)(program.first, program.second, updates)((a, b) =>
        program.copy(first = a, second = b)
      )

    override def inspectNamed(program: AndThen[I, X, O, A, B]): Vector[(String, PredictorView)] =
      PairPredictors.inspectNamed(pa, pb)(program.first, program.second)

/** `fanout(a, b)` (compatibility name `parallel`) — run both programs on the same input and tuple their outputs. On the
  * synchronous `Either` substrate the two attempts run left-to-right and fail fast; this is Arrow-like `&&&`, not
  * concurrent execution and not by itself an `Applicative` instance. The result's raw merges both sub-predictions'
  * value records (`second` wins on a key collision).
  */
final case class Both[I, OA, OB, A <: Module[ProgramCall[I], Prediction[OA]], B <: Module[
  ProgramCall[I],
  Prediction[OB]
]](
    first: A,
    second: B
) extends TransparentModule[ProgramCall[I], Prediction[(OA, OB)]]:
  override val moduleName: String = "parallel"

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[(OA, OB)]] =
    for
      predA <- first.apply(call)
      predB <- second.apply(call)
    yield Prediction(
      output = (predA.output, predB.output),
      raw = DynamicPrediction(values = DynamicValues.mergeRecords(predA.raw.values, predB.raw.values))
    )

object Both:
  /** Same structural distribution as [[AndThen.andThenPredictors]], via [[PairPredictors]]. */
  given bothPredictors[I, OA, OB, A <: Module[ProgramCall[I], Prediction[OA]], B <: Module[
    ProgramCall[I],
    Prediction[OB]
  ]](
      using
      pa: Predictors[A],
      pb: Predictors[B]
  ): Predictors[Both[I, OA, OB, A, B]] with
    def inspect(program: Both[I, OA, OB, A, B]): Vector[PredictorView] =
      PairPredictors.inspect(pa, pb)(program.first, program.second)

    def replace(program: Both[I, OA, OB, A, B], updates: Vector[PredictorState]): Both[I, OA, OB, A, B] =
      PairPredictors.replace(pa, pb)(program.first, program.second, updates)((a, b) =>
        program.copy(first = a, second = b)
      )

    override def inspectNamed(program: Both[I, OA, OB, A, B]): Vector[(String, PredictorView)] =
      PairPredictors.inspectNamed(pa, pb)(program.first, program.second)

/** `split(a, b)` (compatibility name `tensor`) — run two programs left-to-right on independent inputs and pair both
  * outputs. It is the operation beneath shared-input fan-out: `fanout(a, b) = copy >>> split(a, b)`. Because `Either`
  * failures and runtime effects make that order observable, this operation is not a bifunctorial monoidal tensor on
  * unrestricted modules. Unlike `parallel` it does NOT lift into the packaged `Program`/`ParaCategory` category: its
  * input `(I, J)` has no canonical single-record decoder (fan-out reuses the shared input's decoder; the tensor's two
  * inputs don't), so it lives at the `Module` level. Result raw merges both sub-predictions' records (`second` wins on
  * collision).
  */
final case class Tensor[I, J, A, B, FA <: Module[ProgramCall[I], Prediction[A]], FB <: Module[
  ProgramCall[J],
  Prediction[B]
]](
    first: FA,
    second: FB
) extends TransparentModule[ProgramCall[(I, J)], Prediction[(A, B)]]:
  override val moduleName: String = "tensor"

  override protected def forward(call: ProgramCall[(I, J)])(using
      RuntimeContext
  ): Either[DspyError, Prediction[(A, B)]] =
    for
      predA <- first.apply(call.mapInput(_._1))
      predB <- second.apply(call.mapInput(_._2))
    yield Prediction(
      output = (predA.output, predB.output),
      raw = DynamicPrediction(values = DynamicValues.mergeRecords(predA.raw.values, predB.raw.values))
    )

object Tensor:
  /** Structural `read(a) ++ read(b)`, same distribution as `AndThen` / `Both` (via [[PairPredictors]]). */
  given tensorPredictors[I, J, A, B, FA <: Module[ProgramCall[I], Prediction[A]], FB <: Module[
    ProgramCall[J],
    Prediction[B]
  ]](
      using
      pa: Predictors[FA],
      pb: Predictors[FB]
  ): Predictors[Tensor[I, J, A, B, FA, FB]] with
    def inspect(program: Tensor[I, J, A, B, FA, FB]): Vector[PredictorView] =
      PairPredictors.inspect(pa, pb)(program.first, program.second)

    def replace(program: Tensor[I, J, A, B, FA, FB], updates: Vector[PredictorState]): Tensor[I, J, A, B, FA, FB] =
      PairPredictors.replace(pa, pb)(program.first, program.second, updates)((a, b) =>
        program.copy(first = a, second = b)
      )

    override def inspectNamed(program: Tensor[I, J, A, B, FA, FB]): Vector[(String, PredictorView)] =
      PairPredictors.inspectNamed(pa, pb)(program.first, program.second)

/** `copy`: duplicate the input `I` into `(I, I)`. Parameter-free (like `id`); the first half of a fan-out, so
  * `parallel(a, b) = copy >>> tensor(a, b)`. Copy commutes with deterministic programs but not effect-observing
  * programs; this is a useful classifier rather than a law of the unrestricted execution carrier.
  */
final case class Copy[I]() extends TransparentModule[ProgramCall[I], Prediction[(I, I)]]:
  override val moduleName: String = "copy"
  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[(I, I)]] =
    Right(Prediction((call.input, call.input), DynamicPrediction.empty))

object Copy:
  given copyPredictors[I]: Predictors[Copy[I]] = Predictors.empty

/** `discard`: drop the input, producing `()`. Parameter-free. Although `f >>> discard` and `discard` return the same
  * value, the former still runs `f` and can fail, spend tokens, or invoke tools. No naturality law is claimed for
  * unrestricted executable programs.
  */
final case class Discard[I]() extends TransparentModule[ProgramCall[I], Prediction[Unit]]:
  override val moduleName: String = "discard"
  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[Unit]] =
    Right(Prediction((), DynamicPrediction.empty))

object Discard:
  given discardPredictors[I]: Predictors[Discard[I]] = Predictors.empty

/** `swap`: exchange two components. Parameter-free and involutive (`swap >>> swap = id`) as a structural value
  * transformation; it does not make ordered effectful execution symmetric.
  */
final case class Swap[I, J]() extends TransparentModule[ProgramCall[(I, J)], Prediction[(J, I)]]:
  override val moduleName: String = "swap"
  override protected def forward(call: ProgramCall[(I, J)])(using
      RuntimeContext
  ): Either[DspyError, Prediction[(J, I)]] =
    val (i, j) = call.input
    Right(Prediction((j, i), DynamicPrediction.empty))

object Swap:
  given swapPredictors[I, J]: Predictors[Swap[I, J]] = Predictors.empty

/** The composition combinators as functions / operators. `lift` / `id` / `fanout` / `split` / `copy` / `discard` /
  * `swap` are plain factories; `>>>`, variance transforms, and recovery also have fluent extensions. Import
  * `dspy4s.programs.*` (or this object's members) to use them.
  */
object Compose:
  /** The Category unit at type `I`. */
  def id[I]: Identity[I] = Identity[I]()

  /** Lift a total Scala function into a transparent, parameter-free program. */
  def lift[I, O](f: I => O): Lift[I, O] = Lift(f)

  /** Lift an explicitly fallible Scala function into a transparent, parameter-free program. */
  def liftEither[I, O](f: I => Either[DspyError, O]): LiftEither[I, O] = LiftEither(f)

  /** Ordered shared-input fanout (`&&&`): run `a`, then `b`, and pair their outputs. */
  def fanout[I, OA, OB, A <: Module[ProgramCall[I], Prediction[OA]], B <: Module[ProgramCall[I], Prediction[OB]]](
      a: A,
      b: B
  ): Both[I, OA, OB, A, B] = Both(a, b)

  /** Compatibility name for [[fanout]]. This operation is ordered, not concurrent. */
  def parallel[I, OA, OB, A <: Module[ProgramCall[I], Prediction[OA]], B <: Module[ProgramCall[I], Prediction[OB]]](
      a: A,
      b: B
  ): Both[I, OA, OB, A, B] = fanout(a, b)

  /** Ordered independent-input split (`***`): run `a` on the first input, then `b` on the second. */
  def split[I, J, A, B, FA <: Module[ProgramCall[I], Prediction[A]], FB <: Module[ProgramCall[J], Prediction[B]]](
      a: FA,
      b: FB
  ): Tensor[I, J, A, B, FA, FB] = Tensor(a, b)

  /** Compatibility name for [[split]]. */
  def tensor[I, J, A, B, FA <: Module[ProgramCall[I], Prediction[A]], FB <: Module[ProgramCall[J], Prediction[B]]](
      a: FA,
      b: FB
  ): Tensor[I, J, A, B, FA, FB] = split(a, b)

  /** Try `primary`, then a fixed fallback only when `policy` selects the primary error. */
  def recover[I, O, P <: Module[ProgramCall[I], Prediction[O]], F <: Module[ProgramCall[I], Prediction[O]]](
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
  def mode[I, O, P <: Module[ProgramCall[I], Prediction[O]]](m: Mode)(p: P): Moded[I, O, P] = Moded(m, p)

/** `a >>> b`: sequential composition. Defined at package level so it is available wherever the programs package is in
  * scope (or via `import dspy4s.programs.*`).
  */
extension [I, X, A <: Module[ProgramCall[I], Prediction[X]]](self: A)
  infix def >>>[O, B <: Module[ProgramCall[X], Prediction[O]]](next: B): AndThen[I, X, O, A, B] =
    AndThen[I, X, O, A, B](self, next)

  /** Fluent ordered shared-input fanout. */
  def fanout[O, B <: Module[ProgramCall[I], Prediction[O]]](other: B): Both[I, X, O, A, B] =
    Both(self, other)

  /** Fluent ordered independent-input split. */
  def split[J, O, B <: Module[ProgramCall[J], Prediction[O]]](other: B): Tensor[I, J, X, O, A, B] =
    Tensor(self, other)
