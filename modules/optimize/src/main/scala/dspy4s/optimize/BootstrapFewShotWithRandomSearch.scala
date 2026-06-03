package dspy4s.optimize

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Example
import dspy4s.programs.contracts.DynamicModule
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.evaluate.Evaluate
import dspy4s.evaluate.contracts.Metric
import dspy4s.optimize.contracts.CandidateProgram
import dspy4s.optimize.contracts.OptimizationReport
import dspy4s.optimize.contracts.Teleprompter
import dspy4s.programs.contracts.ProgramCall

import scala.collection.mutable
import scala.util.boundary
import scala.util.boundary.break

final case class RandomSearchConfig(
    metric: Metric,
    numCandidates: Int = 16,
    maxBootstrappedDemos: Int = 4,
    maxLabeledDemos: Int = 16,
    maxRounds: Int = 1,
    numThreads: Option[Int] = None,
    maxErrors: Int = 10,
    stopAtScore: Option[Double] = None,
    metricThreshold: Option[Double] = None,
    seed: Long = 0L
)

final class BootstrapFewShotWithRandomSearch[P <: DynamicModule: PredictOps](
    config: RandomSearchConfig
) extends Teleprompter[P]:

  override val name: String = "bootstrap_few_shot_random_search"

  override def compile(
      student: P,
      trainset: Vector[Example],
      teacher: Option[P] = None,
      valset: Option[Vector[Example]] = None
  )(using ctx: RuntimeContext): Either[DspyError, OptimizationReport[P]] =
    val ops = summon[PredictOps[P]]
    val effectiveValset: Vector[Example] =
      valset.getOrElse(trainset)

    val candidates = mutable.ArrayBuffer.empty[(Int, P)]

    // seed -3: zero-shot (no demos)
    candidates += ((-3, ops.withDemos(student, Vector.empty)))

    // seed -2: labeled few shot
    if config.maxLabeledDemos > 0 then
      val labelWrapper = LabeledSampleProgram(student, ops)
      val labeled = new LabeledFewShot[LabeledSampleProgram[P]](
        LabeledFewShotConfig(k = config.maxLabeledDemos, seed = config.seed)
      )
      labeled.compile(labelWrapper, trainset) match
        case Right(report) =>
          candidates += ((-2, report.bestProgram.wrapped))
        case Left(_) => ()

    // seed -1: bootstrap with unshuffled trainset
    val bootstrapConfig = BootstrapFewShotConfig(
      metric = Some(config.metric),
      metricThreshold = config.metricThreshold,
      maxBootstrappedDemos = math.min(config.maxBootstrappedDemos, math.max(1, trainset.size)),
      maxLabeledDemos = config.maxLabeledDemos,
      maxRounds = config.maxRounds,
      maxErrors = config.maxErrors,
      seed = config.seed
    )
    val bootstrapSeedMinus1 = new BootstrapFewShot[P](bootstrapConfig)
    bootstrapSeedMinus1.compile(student, trainset, teacher) match
      case Right(report) => candidates += ((-1, report.bestProgram))
      case Left(_)       => ()

    // seeds 0..numCandidates-1: bootstrap with shuffled subset
    (0 until config.numCandidates).foreach { seed =>
      val subsetRng = new scala.util.Random((config.seed + seed).toLong)
      val shuffled = Vector.from(subsetRng.shuffle(trainset))
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
          maxBootstrappedDemos = math.min(targetSize, config.maxBootstrappedDemos),
          maxLabeledDemos = config.maxLabeledDemos,
          maxRounds = config.maxRounds,
          maxErrors = config.maxErrors,
          seed = seed.toLong
        )
      )
      candidateBootstrap.compile(student, subset, teacher) match
        case Right(report) => candidates += ((seed, report.bestProgram))
        case Left(_)       => ()
    }

    var bestScore: Option[Double] = None
    var bestCandidate: Option[CandidateProgram[P]] = None
    val allCandidates = mutable.ArrayBuffer.empty[CandidateProgram[P]]

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
          program.apply(ProgramCall(inputs = ex.inputs))
        )
        val (score, perExample) = evalResult match
          case Right(r) =>
            val subscores: Vector[Double] = r.results.map(_.score)
            (r.score, subscores)
          case Left(_) =>
            (0.0, Vector.empty[Double])

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
        case Some(best) =>
          Right(
            OptimizationReport(
              bestProgram = best.program,
              candidates = allCandidates.toVector.sortBy(-_.score),
              metadata = Map(
                "num_candidates" -> allCandidates.size,
                "best_score" -> best.score
              )
            )
          )
        case None =>
          Right(
            OptimizationReport(
              bestProgram = student,
              candidates = Vector.empty,
              metadata = Map("no_candidates_evaluated" -> true)
            )
          )
    }

private final case class LabeledSampleProgram[P <: DynamicModule] private (
    wrapped: P,
    ops: PredictOps[P],
    currentDemos: Vector[Example]
) extends DynamicModule:
  override val moduleName: String = ops.name(wrapped)
  override protected def forward(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    ops.withDemos(wrapped, currentDemos).apply(input)

private object LabeledSampleProgram:
  def apply[P <: DynamicModule](wrapped: P, ops: PredictOps[P]): LabeledSampleProgram[P] =
    new LabeledSampleProgram(wrapped, ops, ops.demos(wrapped))

  given labeledOps[P <: DynamicModule]: PredictOps[LabeledSampleProgram[P]] with
    def name(program: LabeledSampleProgram[P]): String = program.ops.name(program.wrapped)
    def layout(program: LabeledSampleProgram[P]): dspy4s.core.contracts.SignatureLayout =
      program.ops.layout(program.wrapped)
    def demos(program: LabeledSampleProgram[P]): Vector[Example] =
      program.currentDemos
    def withDemos(program: LabeledSampleProgram[P], demos: Vector[Example]): LabeledSampleProgram[P] =
      new LabeledSampleProgram(program.wrapped, program.ops, demos)
