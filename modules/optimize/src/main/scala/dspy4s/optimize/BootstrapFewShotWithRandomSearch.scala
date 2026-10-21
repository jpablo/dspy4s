package dspy4s.optimize

import dspy4s.programs.ProgramRunner

import dspy4s.programs.optimization.OptimizableStructure

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

import scala.collection.mutable
import scala.util.boundary
import scala.util.boundary.break

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

final class BootstrapFewShotWithRandomSearch[P: {OptimizableStructure, ProgramRunner}](
    config: RandomSearchConfig
) extends Teleprompter[P]:

  override val name: String = "bootstrap_few_shot_random_search"

  override def compile(
      student : P,
      trainset: Vector[Example],
      teacher : Option[P]               = None,
      valset  : Option[Vector[Example]] = None
  )(using RuntimeContext): Either[DspyError, OptimizationReport[P]] =
    Teleprompter.withCompileCallbacks(this, trainset, valset)(compileInScope(student, trainset, teacher, valset))

  /** The work of [[compile]], inside its compile scope. */
  private def compileInScope(
      student : P,
      trainset: Vector[Example],
      teacher : Option[P],
      valset  : Option[Vector[Example]]
  )(using ctx: RuntimeContext): Either[DspyError, OptimizationReport[P]] =
    val ps = summon[OptimizableStructure[P]]
    // No valset or an empty valset selects the trainset (upstream `self.valset = valset or trainset`,
    // `dspy/teleprompt/random_search.py`).
    val effectiveValset: Vector[Example] = valset.filter(_.nonEmpty).getOrElse(trainset)
    // Each candidate gets its score from `Evaluate`, which rejects an empty devset. Stop before the bootstrap work.
    if effectiveValset.isEmpty then return Left(OptimizerSupport.emptyEvalsetError(name))
    // Two leaves with the same predictor key are a configuration error (decision D2). Stop before any run.
    OptimizerSupport.requireDistinctKeys(student, teacher) match
      case Left(error) => return Left(error)
      case Right(())   => ()

    val candidates = mutable.ArrayBuffer.empty[(Int, P)]

    // A configuration error of a bootstrap run stops the compile. Each other failure gives no candidate.
    def addBootstrapped(seed: Int, result: Either[DspyError, OptimizationReport[P]]): Either[DspyError, Unit] =
      OptimizerSupport.bootstrapCandidate(result).map(_.foreach(program => candidates += ((seed, program))))

    // seed -3: zero-shot (no demos)
    candidates += ((-3, ps.replace(student, ps.read(student).map(_.copy(demos = Vector.empty)))))

    // seed -2: labeled few shot
    if config.maxLabeledDemos > 0 then
      val labeled = new LabeledFewShot[P](
        LabeledFewShotConfig(k = config.maxLabeledDemos, seed = config.seed)
      )
      labeled.compile(student, trainset) match
        case Right(report) => candidates += ((-2, report.bestProgram))
        case Left(_)       => ()

    // seed -1: bootstrap with unshuffled trainset
    val bootstrapConfig = BootstrapFewShotConfig(
      metric = Some(config.metric),
      metricThreshold = config.metricThreshold,
      maxBootstrappedDemos = config.maxBootstrappedDemos,
      maxLabeledDemos = config.maxLabeledDemos,
      maxRounds = config.maxRounds,
      maxErrors = config.maxErrors,
      seed = config.seed
    )
    val bootstrapSeedMinus1 = new BootstrapFewShot[P](bootstrapConfig)
    val unshuffled          = addBootstrapped(-1, bootstrapSeedMinus1.compile(student, trainset, teacher))

    // seeds 0..numCandidates-1: bootstrap with shuffled subset
    val bootstrapped = (0 until config.numCandidates).foldLeft(unshuffled) { (added, seed) =>
      added.flatMap { _ =>
        val subsetRng  = new scala.util.Random((config.seed + seed).toLong)
        val shuffled   = Vector.from(subsetRng.shuffle(trainset))
        val minSamples = 1
        val maxSamples = math.max(minSamples, config.maxBootstrappedDemos)
        val targetSize =
          if maxSamples == minSamples then minSamples
          else minSamples + subsetRng.nextInt(maxSamples - minSamples + 1)
        val subset = shuffled.take(targetSize)

        val candidateBootstrap = new BootstrapFewShot[P](
          BootstrapFewShotConfig(
            metric = Some(config.metric),
            metricThreshold = config.metricThreshold,
            maxBootstrappedDemos = config.maxBootstrappedDemos,
            maxLabeledDemos = config.maxLabeledDemos,
            maxRounds = config.maxRounds,
            maxErrors = config.maxErrors,
            seed = seed.toLong
          )
        )
        addBootstrapped(seed, candidateBootstrap.compile(student, subset, teacher))
      }
    }
    bootstrapped match
      case Left(error) => return Left(error)
      case Right(())   => ()

    var bestScore: Option[Double]                  = None
    var bestCandidate: Option[CandidateProgram[P]] = None
    val allCandidates                              = mutable.ArrayBuffer.empty[CandidateProgram[P]]

    val sortedCandidates = candidates.sortBy(c =>
      val (seed, _) = c
      // Evaluate special seeds first (-3, -2, -1), then the numeric ones
      if seed < 0 then seed.toLong else (seed + 1000).toLong
    )

    boundary[Either[DspyError, OptimizationReport[P]]] {
      sortedCandidates.foreach { case (seed, program) =>
        val evaluator = Evaluate(
          devset = effectiveValset,
          metric = config.metric,
          numThreads = config.numThreads,
          maxErrors = Some(config.maxErrors)
        )
        val evalResult = evaluator()((ex: Example) =>
          summon[ProgramRunner[P]].run(program, ex.inputs)
        )
        val (score, perExample) = evalResult match
          case Right(r) =>
            val subscores: Vector[Double] = r.results.map(_.score)
            (r.score, subscores)
          case Left(_) => (0.0, Vector.empty[Double])

        val candidate = CandidateProgram(
          program = program,
          score = score,
          metadata = Map("seed" -> seed, "per_example" -> perExample)
        )
        allCandidates += candidate

        if bestScore.forall(score > _) then
          bestScore = Some(score)
          bestCandidate = Some(candidate)

        config.stopAtScore.foreach { stop =>
          if score >= stop then
            break(
              Right(
                OptimizationReport(
                  bestProgram = program,
                  candidates = allCandidates.toVector.sortBy(-_.score),
                  metadata = Map("stopped_early" -> true, "stop_at_score" -> stop, "score" -> score)
                )
              )
            )
        }
      }

      bestCandidate match
        case Some(best) => Right(
            OptimizationReport(
              bestProgram = best.program,
              candidates = allCandidates.toVector.sortBy(-_.score),
              metadata = Map(
                "num_candidates" -> allCandidates.size,
                "best_score"     -> best.score
              )
            )
          )
        case None => Right(
            OptimizationReport(
              bestProgram = student,
              candidates = Vector.empty,
              metadata = Map("no_candidates_evaluated" -> true)
            )
          )
    }
