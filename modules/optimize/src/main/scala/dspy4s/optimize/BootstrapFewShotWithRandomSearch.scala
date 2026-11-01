package dspy4s.optimize

import dspy4s.programs.ProgramRunner

import dspy4s.programs.optimization.OptimizableStructure

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.ErrorLimit
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ThreadCount
import dspy4s.evaluate.contracts.Metric
import dspy4s.optimize.contracts.CandidateProgram
import dspy4s.optimize.contracts.OptimizationReport
import dspy4s.optimize.contracts.Teleprompter

import scala.annotation.tailrec
import scala.util.Random

final case class RandomSearchConfig(
    metric              : Metric,
    numCandidates       : SearchCandidateCount = SearchCandidateCount(16),
    maxBootstrappedDemos: DemoCount            = DemoCount(4),
    maxLabeledDemos     : DemoCount            = DemoCount(16),
    maxRounds           : RoundCount           = RoundCount(1),
    numThreads          : Option[ThreadCount]  = None,
    maxErrors           : Option[ErrorLimit]   = None,
    stopAtScore         : Option[Double]       = None,
    metricThreshold     : Option[Double]       = None,
    seed                : Long                 = 0L
)

/** BootstrapFewShotWithRandomSearch makes candidate programs with different seeds, scores each one on the validation
  * set, and keeps the best one (upstream `dspy/teleprompt/random_search.py`). The seeds, in order:
  *   - -3: the student with no demos.
  *   - -2: the labeled demos of [[LabeledFewShot]], when `maxLabeledDemos` is more than 0.
  *   - -1: [[BootstrapFewShot]] on the trainset, in its order.
  *   - 0 to `numCandidates - 1`: [[BootstrapFewShot]] on the whole trainset in a shuffled order, with a random size
  *     between 1 and `maxBootstrappedDemos` as its `maxBootstrappedDemos` (`OptimizerSupport.shuffledBootstrap`). The
  *     config seed plus the candidate seed gives the random source and the seed of the bootstrap.
  *
  * As upstream, the search builds each candidate inside the loop, so the first candidate that reaches `stopAtScore`
  * stops the search and no more bootstrap runs occur. A `Left` of a bootstrap or of an evaluation is the `Left` of
  * `compile`, as upstream raises. With no `maxErrors`, the bootstrap runs and the evaluations take the error limit of
  * the context (upstream `dspy.settings.max_errors`).
  *
  * '''Delta from Python.''' A `maxBootstrappedDemos` of 0 gives the size 0, where upstream `randint(1, 0)` raises.
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

  /** The candidates in the order of their seeds, up to the first candidate that reaches `stopAtScore`. A seed after
    * that candidate makes no run. The first `Left` stops the search, and it is the result.
    */
  private def search(student: P, trainset: Vector[Example], teacher: Option[P], evalset: Vector[Example])(using
      RuntimeContext
  ): Either[DspyError, Vector[CandidateProgram[P]]] =
    @tailrec
    def loop(seed: Int, done: Vector[CandidateProgram[P]]): Either[DspyError, Vector[CandidateProgram[P]]] =
      if seed >= config.numCandidates then Right(done)
      else
        candidateOf(seed, student, trainset, teacher, evalset) match
          case Left(error)            => Left(error)
          case Right(None)            => loop(seed + 1, done)
          case Right(Some(candidate)) =>
            if config.stopAtScore.exists(candidate.score >= _) then Right(done :+ candidate)
            else loop(seed + 1, done :+ candidate)
    loop(-3, Vector.empty)

  /** The candidate of one seed, or `None` when the seed gives no candidate. */
  private def candidateOf(
      seed    : Int,
      student : P,
      trainset: Vector[Example],
      teacher : Option[P],
      evalset : Vector[Example]
  )(using RuntimeContext): Either[DspyError, Option[CandidateProgram[P]]] =
    programOf(seed, student, trainset, teacher).flatMap {
      case Some(program) => scored(seed, program, evalset).map(Some(_))
      case None          => Right(None)
    }

  /** The program of one seed, or `None` when the seed gives no candidate. */
  private def programOf(seed: Int, student: P, trainset: Vector[Example], teacher: Option[P])(using
      RuntimeContext
  ): Either[DspyError, Option[P]] =
    seed match
      case -3 =>
        val ps = summon[OptimizableStructure[P]]
        Right(Some(ps.replace(student, ps.read(student).map(_.copy(demos = Vector.empty)))))
      case -2 if config.maxLabeledDemos > 0 =>
        new LabeledFewShot[P](LabeledFewShotConfig(k = config.maxLabeledDemos, seed = config.seed))
          .compile(student, trainset)
          .map(report => Some(report.bestProgram))
      case -2 => Right(None)
      case -1 => new BootstrapFewShot[P](bootstrapConfig).compile(student, trainset, teacher).map(report =>
          Some(report.bestProgram)
        )
      case _ =>
        val candidateSeed = config.seed + seed
        OptimizerSupport
          .shuffledBootstrap(
            new Random(candidateSeed),
            bootstrapConfig.copy(seed = candidateSeed),
            student,
            trainset,
            teacher
          )
          .map(report => Some(report.bestProgram))

  /** The candidate of `program`, with its score on `evalset`. */
  private def scored(seed: Int, program: P, evalset: Vector[Example])(using
      RuntimeContext
  ): Either[DspyError, CandidateProgram[P]] =
    OptimizerSupport
      .evaluation(program, evalset, config.metric, summon[ProgramRunner[P]], config.numThreads, config.maxErrors)
      .map(result =>
        CandidateProgram(
          program = program,
          score = result.score,
          metadata = Map("seed" -> seed, "per_example" -> result.results.map(_.score))
        )
      )

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
