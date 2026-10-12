package dspy4s.optimize

import dspy4s.programs.ProgramRunner

import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.optimization.IdentifiedOptimizable
import dspy4s.programs.optimization.OptimizableStructure
import dspy4s.programs.optimization.PredictorTraces
import dspy4s.programs.optimization.TracedRun

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ErrorLimit
import dspy4s.core.data.Example
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.ValidationError
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.optimize.contracts.CandidateProgram
import dspy4s.optimize.contracts.OptimizationReport
import dspy4s.optimize.contracts.Teleprompter
import zio.blocks.schema.DynamicValue

import scala.annotation.tailrec
import scala.util.Random
import scala.util.control.NonFatal
import scala.util.hashing.MurmurHash3

final case class BootstrapFewShotConfig(
    metric              : Option[dspy4s.evaluate.contracts.Metric] = None,
    metricThreshold     : Option[Double]                           = None,
    maxBootstrappedDemos: DemoCount                                = DemoCount(4),
    maxLabeledDemos     : DemoCount                                = DemoCount(16),
    maxRounds           : RoundCount                               = RoundCount(1),
    maxErrors           : ErrorLimit                               = ErrorLimit(10),
    seed                : Long                                     = 0L
)

/** BootstrapFewShot builds the demos of each predictor of a program. It is a port of `dspy.teleprompt.BootstrapFewShot`
  * (`dspy/teleprompt/bootstrap.py`, PORT_GAPS G-42).
  *
  * The steps:
  *   1. '''Teacher.''' The teacher is the program that gives the traces. With no `teacher`, the student is the teacher.
  *      A teacher that is not compiled gets labeled demos first from [[LabeledFewShot]] (`k = maxLabeledDemos`).
  *   1. '''Bootstrap.''' The teacher runs on the trainset examples, in order, until `maxBootstrappedDemos` examples
  *      pass. For the run of an example, the teacher does not have that example in its demos. An example passes when
  *      the run is successful and the metric accepts the prediction. The metric receives the trace of the run. An
  *      example that does not pass gets up to `maxRounds` runs. Round 0 is the plain call. A later round `r` sends
  *      `rolloutId = r` and `temperature = 1.0`, so the model does not give the same result again.
  *   1. '''Demos for each predictor.''' An example that passes gives one bootstrapped demo to each predictor that ran:
  *      the inputs and the outputs of the trace entry of that predictor. The optimizer finds the entries of a predictor
  *      by the predictor key (`PredictorTraces`, decision D2). A predictor that ran more than one time in one example
  *      keeps one demo: one of the first N-1 demos at random with probability 0.5, otherwise the last demo.
  *   1. '''Labeled demos.''' The labeled pool is all trainset examples that were not bootstrapped, in a seeded random
  *      order. An example that did not run is also in the pool. Each student predictor gets its first
  *      `maxBootstrappedDemos` bootstrapped demos. Then it gets a random sample from the pool, until it has
  *      `maxLabeledDemos` demos.
  *
  * A run that returns a `Left`, a metric that returns a `Left`, and a thrown exception each count as one error. When
  * the count is `maxErrors`, `compile` returns a `Left`. Two predictors with the same key, a predictor type that
  * records no key, and a teacher that does not agree with the student are configuration errors. `compile` returns each
  * of them immediately as a `ValidationError` and does not count them.
  *
  * ==Deliberate differences from Python==
  *   - '''Compiled teacher.''' Python reads the `_compiled` flag of the teacher, and it rejects a compiled student.
  *     dspy4s programs have no such flag. Here a teacher is not compiled when none of its predictors has a demo. The
  *     rule is the same for a student that is its own teacher. Such a student keeps its demos while it runs as the
  *     teacher (Python: `student.deepcopy()`). When it has a demo, it gets no labeled demos.
  *   - '''Signatures.''' Python requires equal signatures for each student predictor and its teacher predictor, and the
  *     instructions are a part of that comparison. dspy4s compares the signature structure, which holds no
  *     instructions. Thus a teacher can have other instructions.
  *   - '''Random numbers.''' Python uses `random.Random(0)` and a hash of the demos. dspy4s uses `scala.util.Random`
  *     with `seed`. A run is repeatable for a seed, but the choices are not equal to the choices of Python.
  *   - '''Retry rounds.''' Python makes a copy of the configured LM with the new rollout id and temperature. dspy4s
  *     sends the two values in the [[ProgramCall]], and they have priority over the configuration of a predictor.
  *   - '''Errors.''' Python raises the last exception again. dspy4s returns a `RuntimeError` with the message of the
  *     last error.
  *   - '''Report.''' `compile` returns an [[OptimizationReport]] with one candidate. The metadata of the candidate
  *     holds `num_bootstrapped_demos` (the examples that passed), `num_labeled_demos_per_predictor` (in the read order
  *     of the predictors), `num_errors`, and `num_failed_examples` (the examples that were run and did not pass).
  */
final class BootstrapFewShot[P: {OptimizableStructure, ProgramRunner}](
    config: BootstrapFewShotConfig = BootstrapFewShotConfig()
) extends Teleprompter[P]:
  import BootstrapFewShot.Attempt
  import BootstrapFewShot.Progress

  override val name: String = "bootstrap_few_shot"

  private val ps: OptimizableStructure[P] = summon[OptimizableStructure[P]]

  override def compile(
      student : P,
      trainset: Vector[Example],
      teacher : Option[P]               = None,
      valset  : Option[Vector[Example]] = None
  )(using ctx: RuntimeContext): Either[DspyError, OptimizationReport[P]] =
    if trainset.isEmpty then
      Right(
        OptimizationReport(
          bestProgram = student,
          candidates =
            Vector(CandidateProgram(student, 0.0, metadata = Map("optimizer" -> name, "reason" -> "empty_trainset"))),
          metadata = Map("optimizer" -> name)
        )
      )
    else
      compileInternal(student, trainset, teacher)

  private def compileInternal(
      student : P,
      trainset: Vector[Example],
      teacher : Option[P]
  )(using RuntimeContext): Either[DspyError, OptimizationReport[P]] =
    // With no teacher, the student is the teacher, and it keeps its demos (Python: `student.deepcopy()`).
    val teacherBase = teacher.getOrElse(student)
    // The keys do not change when a leaf gets demos, so the leaves of the base teacher are valid for each run.
    val leaves = ps.readIdentified(teacherBase)
    for
      _              <- OptimizerSupport.requireDistinctKeys(student, teacher)
      _              <- requireSameSignatures(ps.readIdentified(student), leaves)
      teacherProgram <- withLabeledDemos(teacherBase, trainset)
      progress       <- bootstrap(teacherProgram, leaves, trainset)
    yield
      val pool                    = labeledPool(trainset, progress.bootstrapped)
      val (compiled, labeledSize) = train(student, progress.demos, pool)
      OptimizationReport(
        bestProgram = compiled,
        candidates = Vector(
          CandidateProgram(
            program = compiled,
            score = 0.0,
            metadata = Map(
              "optimizer"                       -> name,
              "num_bootstrapped_demos"          -> progress.bootstrapped.size,
              "num_labeled_demos_per_predictor" -> labeledSize,
              "num_errors"                      -> progress.errors,
              "num_failed_examples"             -> progress.failed
            )
          )
        ),
        metadata = Map(
          "max_bootstrapped_demos" -> config.maxBootstrappedDemos,
          "max_labeled_demos"      -> config.maxLabeledDemos,
          "max_rounds"             -> config.maxRounds,
          "max_errors"             -> config.maxErrors,
          "seed"                   -> config.seed
        )
      )

  // ── Teacher ─────────────────────────────────────────────────────────────

  /** The demos of teacher leaf `i` go to student leaf `i` (Python: `_prepare_predictor_mappings`). Thus the two
    * programs must have the same leaf count, and each pair of leaves must have the same signature structure. The
    * structure holds no instructions. A different module name is not an error.
    */
  private def requireSameSignatures(
      student: Vector[IdentifiedOptimizable],
      teacher: Vector[IdentifiedOptimizable]
  ): Either[DspyError, Unit] =
    def fields(leaf: IdentifiedOptimizable): String =
      val structure = leaf.metadata.structure
      s"'${structure.name}': ${structure.inputFields.map(_.name).mkString(", ")} -> " +
        structure.outputFields.map(_.name).mkString(", ")

    if student.size != teacher.size then
      Left(
        ValidationError(
          s"The student has ${student.size} optimizable leaves and the teacher has ${teacher.size}. " +
            "The student and the teacher must have the same number of predictors."
        )
      )
    else
      student.zip(teacher).find((own, other) => !own.metadata.structure.equals(other.metadata.structure)) match
        case None               => Right(())
        case Some((own, other)) => Left(
            ValidationError(
              s"The optimizable leaf '${own.displayName}' has the signature (${fields(own)}) in the student and " +
                s"the signature (${fields(other)}) in the teacher. The student and the teacher must have the same " +
                "signatures: the same name, and fields with the same names, types, and descriptions."
            )
          )

  /** Give labeled demos to a teacher that is not compiled (Python: `_prepare_student_and_teacher`). A teacher is not
    * compiled when none of its leaves has a demo. The rule is the same for a student that is its own teacher.
    */
  private def withLabeledDemos(teacher: P, trainset: Vector[Example])(using RuntimeContext): Either[DspyError, P] =
    val compiled = ps.read(teacher).exists(_.demos.nonEmpty)
    if config.maxLabeledDemos > 0 && !compiled then
      new LabeledFewShot[P](LabeledFewShotConfig(k = config.maxLabeledDemos, seed = config.seed))
        .compile(teacher, trainset)
        .map(_.bestProgram)
    else Right(teacher)

  // ── Bootstrap ───────────────────────────────────────────────────────────

  /** Run the teacher on the trainset until `maxBootstrappedDemos` examples pass (Python: `_bootstrap`). A `Left` is the
    * error limit or a configuration error.
    */
  private def bootstrap(
      teacher : P,
      leaves  : Vector[IdentifiedOptimizable],
      trainset: Vector[Example]
  ): Either[DspyError, Progress] =
    @tailrec
    def loop(index: Int, round: Int, progress: Progress): Either[DspyError, Progress] =
      if index >= trainset.size || progress.bootstrapped.size >= config.maxBootstrappedDemos then Right(progress)
      else if round >= config.maxRounds then loop(index + 1, 0, progress.copy(failed = progress.failed + 1))
      else
        attempt(teacher, leaves, trainset(index), round) match
          case Left(error)                    => Left(error)
          case Right(Attempt.Accepted(demos)) => loop(index + 1, 0, progress.accept(index, demos))
          case Right(Attempt.Rejected)        => loop(index, round + 1, progress)
          case Right(Attempt.Failed(reason))  =>
            val errors = progress.errors + 1
            if errors >= config.maxErrors then
              Left(RuntimeError("bootstrap", s"Too many bootstrap errors ($errors): $reason"))
            else loop(index, round + 1, progress.copy(errors = errors))

    loop(0, 0, Progress(Vector.fill(leaves.size)(Vector.empty), Set.empty, failed = 0, errors = 0))

  /** One run of the teacher on one example (Python: `_bootstrap_one_example`). A `Left` is a configuration error. The
    * run takes the current runtime context, so the method has no context parameter.
    */
  private def attempt(
      teacher: P,
      leaves : Vector[IdentifiedOptimizable],
      example: Example,
      round  : Int
  ): Either[DspyError, Attempt] =
    try
      RuntimeEnvironment.withGeneratedAsyncTask(s"bootstrap-round-$round") {
        given RuntimeContext = RuntimeEnvironment.current
        // The metric runs in the isolated context of the run (Python: inside `dspy.context(trace=[])`), so the trace
        // and the history of an LM-judged metric do not reach the caller's context.
        TracedRun.runWith(withoutDemo(teacher, example), teacherCall(example, round)) { (result, trace) =>
          result match
            case Left(error)       => Right(Attempt.Failed(error.message))
            case Right(prediction) =>
              // A leaf type that records no key is a configuration error, so the check does not wait for the metric.
              PredictorTraces.byLeaf(leaves, trace).map { entries =>
                accepts(example, prediction, trace) match
                  case Left(error)  => Attempt.Failed(error.message)
                  case Right(false) => Attempt.Rejected
                  case Right(true)  =>
                    Attempt.Accepted(entries.map(group => BootstrapFewShot.chooseDemo(group.map(demoOf), config.seed)))
              }
        }
      }
    catch
      case NonFatal(error) => Right(Attempt.Failed(Option(error.getMessage).getOrElse(error.toString)))

  /** The teacher must not see the example of the run as a demo (Python: `x != example`). Python compares the stores of
    * the two examples. The store holds the values and the `augmented` mark, and it does not hold the input keys.
    */
  private def withoutDemo(teacher: P, example: Example): P =
    def same(demo: Example): Boolean = demo.augmented == example.augmented && demo.values.equals(example.values)
    ps.replace(teacher, ps.read(teacher).map(leaf => leaf.copy(demos = leaf.demos.filterNot(same))))

  /** Round 0 is the plain call. A later round sends a new rollout id and the temperature 1.0, so the cache gives no
    * stored result and the model gives a different trace (Python: `lm.copy(rollout_id=round_idx, temperature=1.0)`).
    * The per-call config has priority over the config of a predictor.
    */
  private def teacherCall(example: Example, round: Int): ProgramCall[DynamicValue.Record] =
    if round == 0 then ProgramCall(example.inputs)
    else ProgramCall(example.inputs, config = DynamicValues.record("temperature" := 1.0), rolloutId = Some(round))

  private def accepts(example: Example, prediction: RawPrediction, trace: Vector[TraceEntry])(using
      RuntimeContext
  ): Either[DspyError, Boolean] =
    config.metric match
      case None         => Right(true)
      case Some(metric) => metric.score(example, prediction, trace).map { score =>
          config.metricThreshold match
            case None            => score > 0.0
            case Some(threshold) => score >= threshold
        }

  /** The bootstrapped demo of one call of a predictor: its inputs and its outputs. */
  private def demoOf(entry: TraceEntry): Example =
    Example(
      values = DynamicValues.mergeRecords(entry.inputs, entry.outputs),
      inputKeys = DynamicValues.recordKeys(entry.inputs).toSet,
      augmented = true
    )

  // ── Demos of the student ────────────────────────────────────────────────

  /** All examples that were not bootstrapped, in a seeded random order (Python: `self.validation`). */
  private def labeledPool(trainset: Vector[Example], bootstrapped: Set[Int]): Vector[Example] =
    val rest = trainset.zipWithIndex.collect { case (example, index) if !bootstrapped.contains(index) => example }
    new Random(config.seed).shuffle(rest)

  /** Give each student leaf its bootstrapped demos, then a sample of labeled demos (Python: `_train`). One random
    * source serves all leaves, in read order. The second result is the number of labeled demos of each leaf.
    */
  private def train(student: P, demos: Vector[Vector[Example]], pool: Vector[Example]): (P, Vector[Int]) =
    val rng     = new Random(config.seed)
    val updates = ps.read(student).zip(demos).map { (leaf, bootstrapped) =>
      val augmented  = bootstrapped.take(config.maxBootstrappedDemos)
      val sampleSize = math.max(0, math.min(config.maxLabeledDemos - augmented.size, pool.size))
      val labeled    = if sampleSize == 0 then Vector.empty else rng.shuffle(pool).take(sampleSize)
      leaf.copy(demos = augmented ++ labeled) -> labeled.size
    }
    ps.replace(student, updates.map(_._1)) -> updates.map(_._2)

private[optimize] object BootstrapFewShot:

  /** The one demo that a leaf keeps for one example. A leaf that ran more than one time keeps one of the first N-1
    * demos at random with probability 0.5, otherwise the last demo. The random source gets its seed from the content of
    * the demos and from `seed`, so the choice is repeatable (Python: `random.Random(Hasher.hash(tuple(demos)))`).
    */
  def chooseDemo(demos: Vector[Example], seed: Long): Option[Example] =
    if demos.sizeIs <= 1 then demos.headOption
    else
      val content = demos.map(demo => DynamicValues.renderText(demo.values))
      val rng     = new Random(MurmurHash3.orderedHash(content, seed.##).toLong)
      Some(if rng.nextDouble() < 0.5 then demos(rng.nextInt(demos.size - 1)) else demos.last)

  /** The result of one run of the teacher on one example. */
  private enum Attempt derives CanEqual:
    /** The example passed. `demos` holds the demo of each leaf, in read order. A leaf that did not run has `None`. */
    case Accepted(demos: Vector[Option[Example]])

    /** The run was successful, but the metric did not accept the prediction. */
    case Rejected

    /** The run or the metric failed. This counts as one error. */
    case Failed(reason: String)

  /** The state of the bootstrap loop.
    *
    * @param demos
    *   the bootstrapped demos of each leaf, in read order
    * @param bootstrapped
    *   the trainset indices of the examples that passed
    * @param failed
    *   the number of examples that used all their rounds and did not pass
    * @param errors
    *   the number of errors
    */
  private final case class Progress(
      demos       : Vector[Vector[Example]],
      bootstrapped: Set[Int],
      failed      : Int,
      errors      : Int
  ):
    def accept(index: Int, found: Vector[Option[Example]]): Progress =
      copy(demos = demos.zip(found).map((kept, demo) => kept ++ demo), bootstrapped = bootstrapped + index)
