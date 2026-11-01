package dspy4s.optimize

import dspy4s.programs.ProgramRunner

import dspy4s.core.collections.Traverse
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ErrorLimit
import dspy4s.core.contracts.PythonText
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ThreadCount
import dspy4s.core.contracts.ValidationError
import dspy4s.evaluate.Evaluate
import dspy4s.evaluate.contracts.EvaluationResult
import dspy4s.evaluate.contracts.Metric
import dspy4s.optimize.contracts.OptimizationReport
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.optimization.OptimizableStructure
import dspy4s.programs.optimization.PredictorTraces
import dspy4s.programs.strategies.DynamicPredict
import zio.blocks.schema.DynamicValue

import scala.util.Random
import scala.util.matching.Regex

/** The helpers of the optimizers: the rollout id base, one generation call, and one evaluation.
  *
  * '''The failure rule.''' Upstream raises when a bootstrap, a proposal, or an evaluation fails, and the exception
  * stops the compile. Thus each optimizer here returns the `Left` of such a step as the `Left` of `compile`. There are
  * two exceptions, and upstream has them too: a failed evaluation of a [[MIPROv2]] trial or baseline is the score 0.0
  * (upstream `eval_candidate_program`), and a failed dataset summary of `GroundedProposer` gives no grounding.
  */
private[optimize] object OptimizerSupport:

  /** Reject a student or a teacher in which two leaves have the same predictor key (UPGRADE-3.2.1-to-3.4.0, decision
    * D2). [[BootstrapFewShot]] and each optimizer that calls it make this check before they run a program.
    */
  def requireDistinctKeys[P](student: P, teacher: Option[P])(using
      ps: OptimizableStructure[P]
  ): Either[DspyError, Unit] =
    Traverse.either(student +: teacher.toVector)(program =>
      PredictorTraces.requireDistinctKeys(ps.readIdentified(program))
    )
      .map(_ => ())

  /** One shuffled bootstrap (upstream `random_search.py`, and `create_n_fewshot_demo_sets` in `utils.py`): the whole
    * trainset in a shuffled order goes to the bootstrap, and a random size between 1 and `base.maxBootstrappedDemos`
    * becomes its `maxBootstrappedDemos`. A maximum of 0 gives the size 0, where upstream `randint(1, 0)` raises.
    */
  def shuffledBootstrap[P: {OptimizableStructure, ProgramRunner}](
      rng     : Random,
      base    : BootstrapFewShotConfig,
      student : P,
      trainset: Vector[Example],
      teacher : Option[P]
  )(using RuntimeContext): Either[DspyError, OptimizationReport[P]] =
    val shuffled = rng.shuffle(trainset)
    val size     = if base.maxBootstrappedDemos > 0 then 1 + rng.nextInt(base.maxBootstrappedDemos) else 0
    new BootstrapFewShot[P](base.copy(maxBootstrappedDemos = DemoCount.applyUnsafe(size)))
      .compile(student, shuffled, teacher)

  /** The common successful result when a program exposes no optimizable leaves. */
  def noOptimizableLeavesReport[P](student: P): OptimizationReport[P] =
    OptimizationReport(
      bestProgram = student,
      candidates = Vector.empty,
      metadata = Map("no_optimizable_leaves" -> true)
    )

  /** Write `instruction` in the `idx`-th predictor of `program`. `None` keeps the current instructions, so a leaf with
    * no instructions stays with no instructions.
    */
  def applyInstruction[P](program: P, idx: Int, instruction: Option[String])(using ps: OptimizableStructure[P]): P =
    instruction.fold(program) { text =>
      val leaves = ps.read(program)
      ps.replace(program, leaves.updated(idx, leaves(idx).copy(instructions = Some(text))))
    }

  /** The candidate of a proposed text for a leaf whose effective instructions are `effective`. `None` means "keep the
    * current instructions". A proposal with the text of the current instructions is that same candidate.
    */
  def instructionCandidate(proposal: String, effective: String): Option[String] =
    Option.when(proposal != effective)(proposal)

  /** Map an optimizer `seed` to a base `rolloutId` in `[0, 1024)`. An optimizer adds an offset to this base, so that
    * its generation calls have different rollout ids, and a run is repeatable for a seed.
    */
  def seedBase(seed: Long): Int = math.floorMod(seed.toInt, 1024)

  /** One generation call of an optimizer: `predict` runs on `inputs` with `temperature` and `rolloutId`. The result is
    * the stripped text of the output `output`, or `None` when the output is absent or blank.
    */
  def generateText(
      predict    : DynamicPredict,
      inputs     : DynamicValue.Record,
      output     : String,
      temperature: Double,
      rolloutId  : Int
  )(using RuntimeContext): Either[DspyError, Option[String]] =
    predict(
      ProgramCall(
        input = inputs,
        config = DynamicValues.record("temperature" := temperature),
        rolloutId = Some(rolloutId)
      )
    ).map(prediction =>
      DynamicValues.recordGet(prediction.output, output)
        .map(DynamicValues.renderText)
        .map(PythonText.strip)
        .filter(_.nonEmpty)
    )

  /** Python `text.strip('"')`: no double quote at the start and at the end. */
  def stripQuotes(text: String): String =
    val start = text.indexWhere(_ != '"')
    if start < 0 then "" else text.substring(start, text.lastIndexWhere(_ != '"') + 1)

  /** Upstream `strip_prefix` (`dspy/propose/utils.py`): no label such as `Proposed Instruction:` at the start, and no
    * double quote at the start and at the end. The regular expression is the upstream one. The flag `U` gives the
    * Unicode classes `\w` and `\s` of a Python `str` pattern.
    */
  def stripPrefix(text: String): String =
    stripQuotes(LabelAtStart.replaceFirstIn(text, ""))

  private val LabelAtStart: Regex = """(?U)^[\*\s]*(([\w\'\-]+\s+){0,4}[\w\'\-]+):\s*""".r

  /** The error for an optimizer that has no example to score its candidates on. [[Evaluate]] rejects an empty devset,
    * and an optimizer cannot select a candidate that has no score. Each optimizer that scores candidates returns this
    * error before it does other work (Python raises `ValueError`).
    */
  def emptyEvalsetError(optimizer: String): DspyError =
    ValidationError(
      s"$optimizer cannot score candidates on an empty evaluation set. " +
        "Give a valset (or a trainset, when there is no valset) with at least one example."
    )

  /** The evaluation of `program` on `evalset` with `metric` (upstream `Evaluate`). The context gives the thread count
    * and the error limit that the caller does not give. A `Left` is an evaluation that stopped at its error limit, or
    * an empty `evalset`. Each caller rejects an empty set first with [[emptyEvalsetError]].
    */
  def evaluation[P](
      program   : P,
      evalset   : Vector[Example],
      metric    : Metric,
      runner    : ProgramRunner[P],
      numThreads: Option[ThreadCount] = None,
      maxErrors : Option[ErrorLimit]  = None
  )(using RuntimeContext): Either[DspyError, EvaluationResult] =
    Evaluate(devset = evalset, metric = metric, numThreads = numThreads, maxErrors = maxErrors)()((ex: Example) =>
      runner.run(program, ex.inputs)
    )

  /** The aggregate score (0..100) of [[evaluation]]. */
  def evalScore[P](
      program   : P,
      evalset   : Vector[Example],
      metric    : Metric,
      runner    : ProgramRunner[P],
      numThreads: Option[ThreadCount] = None,
      maxErrors : Option[ErrorLimit]  = None
  )(using RuntimeContext): Either[DspyError, Double] =
    evaluation(program, evalset, metric, runner, numThreads, maxErrors).map(_.score)
