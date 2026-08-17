package dspy4s.optimize

import dspy4s.core.contracts.{DspyError, ErrorLimit}
import dspy4s.core.data.Example
import dspy4s.evaluate.{Evaluate, EvaluateOptions, Metric}
import dspy4s.optimize.contracts.{CandidateProgram, OptimizationReport}
import dspy4s.programs.{PredictionBackend, RecordProgram}
import zio.ZIO

final case class BootstrapRandomSearchConfig(
    metric              : Metric,
    numCandidates       : SearchCandidateCount = SearchCandidateCount(16),
    maxBootstrappedDemos: DemoCount            = DemoCount(4),
    maxLabeledDemos     : DemoCount            = DemoCount(16),
    maxRounds           : RoundCount           = RoundCount(1),
    maxErrors           : ErrorLimit           = ErrorLimit(10),
    stopAtScore         : Option[Double]       = None,
    metricThreshold     : Option[Double]       = None,
    evaluation          : EvaluateOptions      = EvaluateOptions(),
    seed                : Long                 = 0L
)

/** Effectful bootstrap random search for the new record-program boundary. */
object BootstrapRandomSearch:

  private final case class Generated[I, O](
      candidates: Vector[(Int, RecordProgram[I, O])],
      skipped   : Vector[(Int, DspyError)]
  ):
    def add(seed: Int, result: Either[DspyError, RecordProgram[I, O]]): Generated[I, O] =
      result match
        case Right(program) => copy(candidates = candidates :+ (seed -> program))
        case Left(error)    => copy(skipped = skipped :+ (seed -> error))

  def apply[I, O](
      student : RecordProgram[I, O],
      trainset: Vector[Example],
      teacher : Option[RecordProgram[I, O]] = None,
      valset  : Option[Vector[Example]]     = None,
      config  : BootstrapRandomSearchConfig
  ): ZIO[PredictionBackend, DspyError, OptimizationReport[RecordProgram[I, O]]] =
    for
      zeroShot <- ZIO.fromEither(withDemos(student, Vector.empty))
      initial   = Generated(Vector(-3 -> zeroShot), Vector.empty)
      labeled   = Option.when(config.maxLabeledDemos > 0)(
                  withDemos(student, sample(trainset, config.maxLabeledDemos, config.seed))
                )
      withLabeled = labeled.fold(initial)(result => initial.add(-2, result))
      unshuffled <- bootstrap(student, trainset, teacher, config, config.seed).either
      base        = withLabeled.add(-1, unshuffled)
      generated  <- ZIO.foldLeft(0 until config.numCandidates)(base) { (state, candidateSeed) =>
                     val random   = new scala.util.Random(config.seed + candidateSeed.toLong)
                     val shuffled = Vector.from(random.shuffle(trainset))
                     val maximum  = math.max(1, config.maxBootstrappedDemos)
                     val size     =
                       if maximum == 1 then 1
                       else 1 + random.nextInt(maximum)
                     val subset = shuffled.take(size)
                     bootstrap(student, subset, teacher, config, candidateSeed.toLong).either.map { result =>
                       state.add(candidateSeed, result)
                     }
                   }
      report <- evaluate(
                  generated,
                  valset.getOrElse(trainset),
                  config
                )
    yield report

  private def bootstrap[I, O](
      student : RecordProgram[I, O],
      trainset: Vector[Example],
      teacher : Option[RecordProgram[I, O]],
      config  : BootstrapRandomSearchConfig,
      seed    : Long
  ): ZIO[PredictionBackend, DspyError, RecordProgram[I, O]] =
    BootstrapFewShot(
      student,
      trainset,
      teacher,
      BootstrapFewShotConfig(
        metric = Some(config.metric),
        metricThreshold = config.metricThreshold,
        maxBootstrappedDemos = config.maxBootstrappedDemos,
        maxLabeledDemos = config.maxLabeledDemos,
        maxRounds = config.maxRounds,
        maxErrors = config.maxErrors,
        seed = seed
      )
    ).map(_.bestProgram)

  private def withDemos[I, O](
      program: RecordProgram[I, O],
      demos  : Vector[Example]
  ): Either[DspyError, RecordProgram[I, O]] =
    val updated = program.program.parameters.all.map { binding =>
      binding.id -> binding.value.copy(demos = demos)
    }.toMap
    program.replaceParameters(updated)

  private def sample(trainset: Vector[Example], count: Int, seed: Long): Vector[Example] =
    val random = new scala.util.Random(seed)
    Vector.from(random.shuffle(trainset).take(count))

  private def evaluate[I, O](
      generated: Generated[I, O],
      valset   : Vector[Example],
      config   : BootstrapRandomSearchConfig
  ): ZIO[PredictionBackend, Nothing, OptimizationReport[RecordProgram[I, O]]] =
    def loop(
        remaining: List[(Int, RecordProgram[I, O])],
        scored   : Vector[CandidateProgram[RecordProgram[I, O]]],
        best     : Option[(Int, CandidateProgram[RecordProgram[I, O]])]
    ): ZIO[PredictionBackend, Nothing, OptimizationReport[RecordProgram[I, O]]] =
      remaining match
        case Nil =>
          val selected = best.getOrElse {
            val fallback = generated.candidates.head
            fallback._1 -> CandidateProgram(fallback._2, 0.0, metadata = Map("seed" -> fallback._1))
          }
          ZIO.succeed(report(selected, scored, generated.skipped, stoppedEarly = false, config.stopAtScore))

        case (seed, program) :: tail =>
          Evaluate(program, valset, config.metric, config.evaluation).flatMap { evaluation =>
            val candidate = CandidateProgram(
              program = program,
              score = evaluation.score,
              evaluation = Some(evaluation),
              metadata = Map(
                "seed"        -> seed,
                "per_example" -> evaluation.results.map(_.score)
              )
            )
            val nextScored = scored :+ candidate
            val nextBest   = best match
              case Some((_, current)) if current.score >= candidate.score => best
              case _                                                      => Some(seed -> candidate)

            if config.stopAtScore.exists(candidate.score >= _) then
              ZIO.succeed(report(
                seed -> candidate,
                nextScored,
                generated.skipped,
                stoppedEarly = true,
                config.stopAtScore
              ))
            else ZIO.suspendSucceed(loop(tail, nextScored, nextBest))
          }

    loop(generated.candidates.toList, Vector.empty, None)

  private def report[I, O](
      selected    : (Int, CandidateProgram[RecordProgram[I, O]]),
      scored      : Vector[CandidateProgram[RecordProgram[I, O]]],
      skipped     : Vector[(Int, DspyError)],
      stoppedEarly: Boolean,
      stopAtScore : Option[Double]
  ): OptimizationReport[RecordProgram[I, O]] =
    val (bestSeed, best) = selected
    OptimizationReport(
      bestProgram = best.program,
      candidates = scored.sortBy(candidate => -candidate.score),
      metadata = Map(
        "optimizer"      -> "bootstrap_few_shot_random_search",
        "num_candidates" -> scored.size,
        "num_skipped"    -> skipped.size,
        "skipped_seeds"  -> skipped.map(_._1),
        "best_seed"      -> bestSeed,
        "best_score"     -> best.score,
        "stopped_early"  -> stoppedEarly,
        "stop_at_score"  -> stopAtScore
      )
    )
