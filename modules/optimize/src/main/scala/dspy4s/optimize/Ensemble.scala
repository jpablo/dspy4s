package dspy4s.optimize

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ValidationError
import dspy4s.programs.Aggregation
import dspy4s.programs.contracts.DynamicModule
import dspy4s.programs.contracts.ProgramCall
import zio.blocks.schema.DynamicValue

/** Port of Python DSPy's `dspy.teleprompt.ensemble.Ensemble`.
  *
  * Combines several already-compiled programs into one [[DynamicModule]] that, on each call, runs all of them (or a
  * random `size`-sized sample of them) and collapses their outputs through a `reduceFn`. The common reduce is a
  * majority vote (`dspy.majority` upstream), which is the default here via [[Aggregation.majority]].
  *
  * Shape choice: dspy4s splits programs into a typed surface (`Predict[I, O]`, `ChainOfThought[I, O]`, ...) and an
  * dynamic spine (`DynamicModule = Module[DynamicValue.Record, DynamicValue.Record]`). An ensemble is inherently
  * heterogeneous and generic over its members — Python's `EnsembledProgram` just forwards `*args, **kwargs` to each
  * member and reduces a list of arbitrary outputs. A faithful generic typed shape would require all members to share an
  * exact `Module[I, O]` and a typed reducer; that's both more restrictive than upstream and awkward. So we build on the
  * dynamic spine: members are `DynamicModule`s, the reducer folds their semantic output records, and the result is
  * itself a `DynamicModule` (so an ensemble can nest / be used anywhere a program is). This matches the "prefer the
  * dynamic spine for genuinely-generic edges" guidance.
  *
  * Upstream's `deterministic` flag must be `False` (it asserts), so there is no deterministic example-hashing path to
  * port. We keep a `seed` instead so the `size`-sampling is reproducible in tests and runs.
  *
  * @param reduceFn
  *   collapses the members' output rows into a single prediction. Defaults to majority vote.
  * @param size
  *   if set, a random `size`-sized subset of the members is run per call (sampling without replacement, mirroring
  *   Python's `random.sample`). Unset runs every member.
  * @param seed
  *   seed for the per-call sampling RNG, for reproducibility.
  */
final case class Ensemble(
    reduceFn: Vector[DynamicValue.Record] => Either[DspyError, RawPrediction] = Ensemble.majorityVote,
    size: Option[EnsembleSize] = None,
    seed: Long = 0L
):

  val name: String = "ensemble"

  /** Build the ensembled program over the given members. */
  def compile(programs: Vector[DynamicModule]): DynamicModule =
    Ensemble.EnsembledProgram(programs, reduceFn, size, seed)

object Ensemble:

  /** Default reducer: majority vote over the members' output rows (Python's `dspy.majority`). */
  val majorityVote: Vector[DynamicValue.Record] => Either[DspyError, RawPrediction] =
    rows => Aggregation.majority(rows)

  /** The compiled ensemble: a [[DynamicModule]] that runs the (optionally sampled) members and reduces. */
  private final case class EnsembledProgram(
      programs: Vector[DynamicModule],
      reduceFn: Vector[DynamicValue.Record] => Either[DspyError, RawPrediction],
      size: Option[EnsembleSize],
      seed: Long
  ) extends DynamicModule:
    override val moduleName: String = "ensemble"

    // One RNG for the program's lifetime: the SEQUENCE of samples is reproducible from `seed`, but each call
    // draws a different subset. Reseeding per call made every invocation select the identical subset, so the
    // other members never ran (java.util.Random underneath is thread-safe).
    private val rng = new scala.util.Random(seed)

    override protected def forwardDynamic(input: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, RawPrediction] =
      val selected = size match
        case Some(n) =>
          // `random.sample`-style selection without replacement: shuffle, then take n.
          rng.shuffle(programs).take(n)
        case None => programs

      if selected.isEmpty then Left(ValidationError("Ensemble has no programs to run"))
      else
        selected
          .foldLeft[Either[DspyError, Vector[DynamicValue.Record]]](Right(Vector.empty)) { (acc, program) =>
            for
              soFar      <- acc
              prediction <- program(input)
            yield soFar :+ prediction.output
          }
          .flatMap(reduceFn)
