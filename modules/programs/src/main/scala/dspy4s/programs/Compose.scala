package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.Prediction
import zio.blocks.schema.DynamicValue

/** The program-composition combinators `id` / `>>>` / `parallel` — the Category and Applicative operations of
  * Algebra 2 (`docs/refactor/algebra-2-program-composition.md`).
  *
  * Carrier (the grill's fork-1/5 decision): a program is `Module[TypedCall[I], Prediction[O]]`. `>>>` threads
  * the plain typed value `O` (not the `Prediction[O]` envelope): it runs the first program, feeds its
  * `prediction.output` into a fresh `TypedCall` that inherits the outer call's controls (`config` /
  * `traceEnabled` / `rolloutId`), and runs the second. Each sub-program's own `apply` records its trace/history
  * entry, so the intermediate `Prediction.raw` (reasoning / completions / per-step usage) is captured in the
  * trace rather than carried onto the composite result.
  *
  * Optimizer-addressability (fork 4): the combinators are concretely typed in their child programs (`A` / `B`),
  * and their hand-written [[Predictors]] instances distribute `read` / `replace` structurally
  * (`read(a) ++ read(b)`), so teleprompters can introspect and tune the predicts inside a pipeline. */

/** `id[I]` — the Category unit: a pure passthrough that returns its input as the output, with an empty raw
  * envelope. `id >>> p` is `p` (the left unit contributes nothing to the final prediction); `p >>> id` equals
  * `p` on the threaded output value (the right unit's empty raw becomes the result raw — the carrier's
  * value-vs-envelope split, see the law suite). */
final case class Identity[I]() extends Module[TypedCall[I], Prediction[I]]:
  override val moduleName: String = "id"
  override protected def callInputs(call: TypedCall[I]): DynamicValue.Record       = DynamicValue.Record.empty
  override protected def callTraceEnabled(call: TypedCall[I]): Boolean             = call.traceEnabled
  override protected def tracePayload(prediction: Prediction[I]): DynamicValue.Record = prediction.raw.values
  override protected def forward(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[I]] =
    Right(Prediction(call.input, DynamicPrediction.empty))

object Identity:
  given identityPredictors[I]: Predictors[Identity[I]] = Predictors.empty

/** `a >>> b` — sequential (dependent) composition: run `a`, thread its output value into `b`. The Category
  * operation. */
final case class AndThen[I, X, O, A <: Module[TypedCall[I], Prediction[X]], B <: Module[TypedCall[X], Prediction[O]]](
    first: A,
    second: B
) extends Module[TypedCall[I], Prediction[O]]:
  override val moduleName: String = "and_then"
  override protected def callInputs(call: TypedCall[I]): DynamicValue.Record          = DynamicValue.Record.empty
  override protected def callTraceEnabled(call: TypedCall[I]): Boolean                = call.traceEnabled
  override protected def tracePayload(prediction: Prediction[O]): DynamicValue.Record = prediction.raw.values

  override protected def forward(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    first.apply(call).flatMap { predX =>
      // Thread the value X into a fresh TypedCall[X] inheriting the outer call's controls (the Prediction
      // envelope of `predX` stays behind, recorded by `first.apply`'s own trace entry).
      second.apply(TypedCall(
        input        = predX.output,
        config       = call.config,
        traceEnabled = call.traceEnabled,
        rolloutId    = call.rolloutId
      ))
    }

object AndThen:
  /** Structural `read(a) ++ read(b)`; `replace` slices the updates by `first`'s read-arity (fork 4). */
  given andThenPredictors[I, X, O, A <: Module[TypedCall[I], Prediction[X]], B <: Module[TypedCall[X], Prediction[O]]](
      using pa: Predictors[A], pb: Predictors[B]
  ): Predictors[AndThen[I, X, O, A, B]] with
    def read(program: AndThen[I, X, O, A, B]): Vector[DynamicPredict] =
      pa.read(program.first) ++ pb.read(program.second)

    def replace(program: AndThen[I, X, O, A, B], updates: Vector[DynamicPredict]): AndThen[I, X, O, A, B] =
      val (firstUpdates, secondUpdates) = updates.splitAt(pa.read(program.first).size)
      program.copy(
        first  = pa.replace(program.first, firstUpdates),
        second = pb.replace(program.second, secondUpdates)
      )

    override def readNamed(program: AndThen[I, X, O, A, B]): Vector[(String, DynamicPredict)] =
      pa.readNamed(program.first).map { case (sub, p) => (if sub == "self" then "first" else s"first.$sub") -> p } ++
        pb.readNamed(program.second).map { case (sub, p) => (if sub == "self" then "second" else s"second.$sub") -> p }

/** `parallel(a, b)` — independent composition: run both programs on the same input and tuple their outputs.
  * The Applicative operation; the dual of `>>>` (independent vs dependent). On the synchronous `Either`
  * substrate the two attempts run in sequence (concurrency is a later CIO-substrate concern); the result's raw
  * merges both sub-predictions' value records (`second` wins on a key collision). */
final case class Both[I, OA, OB, A <: Module[TypedCall[I], Prediction[OA]], B <: Module[TypedCall[I], Prediction[OB]]](
    first: A,
    second: B
) extends Module[TypedCall[I], Prediction[(OA, OB)]]:
  override val moduleName: String = "parallel"
  override protected def callInputs(call: TypedCall[I]): DynamicValue.Record               = DynamicValue.Record.empty
  override protected def callTraceEnabled(call: TypedCall[I]): Boolean                     = call.traceEnabled
  override protected def tracePayload(prediction: Prediction[(OA, OB)]): DynamicValue.Record = prediction.raw.values

  override protected def forward(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[(OA, OB)]] =
    for
      predA <- first.apply(call)
      predB <- second.apply(call)
    yield Prediction(
      output = (predA.output, predB.output),
      raw    = DynamicPrediction(values = DynamicValues.mergeRecords(predA.raw.values, predB.raw.values))
    )

object Both:
  given bothPredictors[I, OA, OB, A <: Module[TypedCall[I], Prediction[OA]], B <: Module[TypedCall[I], Prediction[OB]]](
      using pa: Predictors[A], pb: Predictors[B]
  ): Predictors[Both[I, OA, OB, A, B]] with
    def read(program: Both[I, OA, OB, A, B]): Vector[DynamicPredict] =
      pa.read(program.first) ++ pb.read(program.second)

    def replace(program: Both[I, OA, OB, A, B], updates: Vector[DynamicPredict]): Both[I, OA, OB, A, B] =
      val (firstUpdates, secondUpdates) = updates.splitAt(pa.read(program.first).size)
      program.copy(
        first  = pa.replace(program.first, firstUpdates),
        second = pb.replace(program.second, secondUpdates)
      )

    override def readNamed(program: Both[I, OA, OB, A, B]): Vector[(String, DynamicPredict)] =
      pa.readNamed(program.first).map { case (sub, p) => (if sub == "self" then "first" else s"first.$sub") -> p } ++
        pb.readNamed(program.second).map { case (sub, p) => (if sub == "self" then "second" else s"second.$sub") -> p }

/** The composition combinators as functions / operators. `id` and `parallel` are plain factories; `>>>` is an
  * infix extension on any typed program. Import `dspy4s.programs.*` (or this object's members) to use them. */
object Compose:
  /** The Category unit at type `I`. */
  def id[I]: Identity[I] = Identity[I]()

  /** Independent composition: `parallel(a, b)` runs both on the same input and tuples the outputs. */
  def parallel[I, OA, OB, A <: Module[TypedCall[I], Prediction[OA]], B <: Module[TypedCall[I], Prediction[OB]]](
      a: A,
      b: B
  ): Both[I, OA, OB, A, B] = Both(a, b)

/** `a >>> b`: sequential composition. Defined at package level so it is available wherever the programs
  * package is in scope (or via `import dspy4s.programs.*`). */
extension [I, X, A <: Module[TypedCall[I], Prediction[X]]](self: A)
  infix def >>>[O, B <: Module[TypedCall[X], Prediction[O]]](next: B): AndThen[I, X, O, A, B] =
    AndThen[I, X, O, A, B](self, next)
