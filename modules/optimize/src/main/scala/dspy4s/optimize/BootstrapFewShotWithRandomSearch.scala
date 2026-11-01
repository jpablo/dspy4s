package dspy4s.optimize

import dspy4s.programs.ProgramRunner

import dspy4s.programs.optimization.OptimizableStructure

import dspy4s.core.collections.Traverse
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.ErrorLimit
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ThreadCount
import dspy4s.evaluate.Evaluate
import dspy4s.evaluate.contracts.Metric
import dspy4s.optimize.contracts.CandidateProgram
import dspy4s.optimize.contracts.OptimizationReport
import dspy4s.optimize.contracts.Teleprompter

import scala.util.Random

final case class RandomSearchConfig(
    metric              : Metric,
    numCandidates       : SearchCandidateCount = SearchCandidateCount(16),
    maxBootstrappedDemos: DemoCount            = DemoCount(4),
    maxLabeledDemos     : DemoCount            = DemoCount(16),
    maxRounds           : RoundCount           = RoundCount(1),
    numThreads          : Option[ThreadCount]  = None,
    maxErrors           : ErrorLimit           = ErrorLimit(10),
    stopAtScore         : Option[Double]       = None,
    metricThreshold     : Option[Double]       = None,
    seed                : Long                 = 0L
)

/** BootstrapFewShotWithRandomSearch makes candidate programs with different seeds, scores each one on the validation
  * set, and keeps the best one (upstream `dspy/teleprompt/random_search.py`). The seeds, in order:
  *   - -3: the student with no demos.
  *   - -2: the labeled demos of [[LabeledFewShot]], when `maxLabeledDemos` is more than 0.
  *   - -1: [[BootstrapFewShot]] on the trainset, in its order.
  *   - 0 to `numCandidates - 1`: [[BootstrapFewShot]] on a shuffled part of the trainset.
  *
  * As upstream, the search builds each candidate inside the loop, so the first candidate that reaches `stopAtScore`
  * stops the search and no more bootstrap runs occur. A configuration error of a bootstrap run stops the compile. Each
  * other failure gives no candidate.
  */
final class BootstrapFewShotWithRandomSearch[P: {OptimizableStructure, ProgramRunner}](
    config: RandomSearchConfig
) extends Teleprompter[P]:

  override val name: String = "bootstrap_few_shot_random_search"

  private val bootstrapConfig = BootstrapFewShotConfig(
    metric = Some(config.metric),
    metricThreshold = config.metricThreshold,
    maxBootstrappedDemos = config.maxBootstrappedDemos,
    maxLabeledDemos = config.maxLabeledDemos,
    maxRounds = config.maxRounds,
    maxErrors = config.maxErrors,
    seed = config.seed
  )

  override protected[optimize] def compileInScope(
      student : P,
      trainset: Vector[Example],
      teacher : Option[P],
      valset  : Option[Vector[Example]]
  )(using RuntimeContext): Either[DspyError, OptimizationReport[P]] =
    // No valset or an empty valset selects the trainset (upstream `self.valset = valset or trainset`,
    // `dspy/teleprompt/random_search.py`).
    val evalset = valset.filter(_.nonEmpty).getOrElse(trainset)
    // Each candidate gets its score from `Evaluate`, which rejects an empty devset. Stop before the bootstrap work.
    if evalset.isEmpty then Left(OptimizerSupport.emptyEvalsetError(name))
    else
      for
        // Two leaves with the same predictor key are a configuration error (decision D2). Stop before any run.
        _          <- OptimizerSupport.requireDistinctKeys(student, teacher)
        candidates <- search(student, trainset, teacher, evalset)
      yield report(candidates)

  /** The candidates in the order of their seeds, up to the first candidate that reaches `stopAtScore`. The sequence is
    * lazy, so a seed after that candidate makes no run.
    */
  private def search(student: P, trainset: Vector[Example], teacher: Option[P], evalset: Vector[Example])(using
      RuntimeContext
  ): Either[DspyError, Vector[CandidateProgram[P]]] =
    val attempts = (-3 until config.numCandidates).to(LazyList).map { seed =>
      programOf(seed, student, trainset, teacher).map(_.map(scored(seed, _, evalset)))
    }
    def reachesStop(candidate: CandidateProgram[P]): Boolean = config.stopAtScore.exists(candidate.score >= _)
    // The first attempt that reaches the stop score is the head of `rest`.
    val (running, rest) = attempts.span(attempt => !attempt.exists(_.exists(reachesStop)))
    Traverse.either(running ++ rest.take(1))(identity).map(_.flatten)

  /** The program of one seed, or `None` when the seed gives no candidate. */
  private def programOf(seed: Int, student: P, trainset: Vector[Example], teacher: Option[P])(using
      RuntimeContext
  ): Either[DspyError, Option[P]] =
    seed match
      case -3 =>
        val ps = summon[OptimizableStructure[P]]
        Right(Some(ps.replace(student, ps.read(student).map(_.copy(demos = Vector.empty)))))
      case -2 if config.maxLabeledDemos > 0 =>
        val labeled = new LabeledFewShot[P](LabeledFewShotConfig(k = config.maxLabeledDemos, seed = config.seed))
        Right(labeled.compile(student, trainset).toOption.map(_.bestProgram))
      case -2 => Right(None)
      case -1 => OptimizerSupport.bootstrapCandidate(new BootstrapFewShot[P](bootstrapConfig).compile(
          student,
          trainset,
          teacher
        ))
      case _ =>
        val rng        = new Random(config.seed + seed)
        val shuffled   = rng.shuffle(trainset)
        val maxSamples = math.max(1, config.maxBootstrappedDemos)
        val subset     = shuffled.take(1 + rng.nextInt(maxSamples))
        OptimizerSupport.bootstrapCandidate(
          new BootstrapFewShot[P](bootstrapConfig.copy(seed = seed.toLong)).compile(student, subset, teacher)
        )

  /** The candidate of `program`, with its score on `evalset`. An evaluation that fails gives the score 0.0. */
  private def scored(seed: Int, program: P, evalset: Vector[Example])(using RuntimeContext): CandidateProgram[P] =
    val evaluator = Evaluate(
      devset = evalset,
      metric = config.metric,
      numThreads = config.numThreads,
      maxErrors = Some(config.maxErrors)
    )
    val (score, perExample) =
      evaluator()((example: Example) => summon[ProgramRunner[P]].run(program, example.inputs)) match
        case Right(result) => (result.score, result.results.map(_.score))
        case Left(_)       => (0.0, Vector.empty[Double])
    CandidateProgram(program = program, score = score, metadata = Map("seed" -> seed, "per_example" -> perExample))

  /** The report of the search. The seed -3 always gives a candidate, so `candidates` is not empty. */
  private def report(candidates: Vector[CandidateProgram[P]]): OptimizationReport[P] =
    val sorted = candidates.sortBy(-_.score)
    val last   = candidates.last
    config.stopAtScore.filter(last.score >= _) match
      case Some(stop) => OptimizationReport(
          bestProgram = last.program,
          candidates = sorted,
          metadata = Map("stopped_early" -> true, "stop_at_score" -> stop, "score" -> last.score)
        )
      case None =>
        val best = candidates.reduceLeft((best, next) => if next.score > best.score then next else best)
        OptimizationReport(
          bestProgram = best.program,
          candidates = sorted,
          metadata = Map("num_candidates" -> candidates.size, "best_score" -> best.score)
        )
