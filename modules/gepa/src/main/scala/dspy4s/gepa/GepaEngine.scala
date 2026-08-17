package dspy4s.gepa

import dspy4s.core.contracts.{DspyError, RuntimeError, ValidationError}
import dspy4s.core.data.Example
import dspy4s.programs.{ProgramRunner, ProgramWithEnv, RecordProgramWithEnv, RunOptions}
import zio.{IO, ZIO}

import java.nio.file.Path
import scala.util.Random

final case class GepaConfig(
    maxMetricCalls         : MetricCallCount,
    reflectionMinibatchSize: MinibatchSize        = MinibatchSize(3),
    candidateSelector      : CandidateSelector    = CandidateSelector.Pareto,
    componentSelector      : ComponentSelector    = ComponentSelector.RoundRobin,
    batchSampler           : BatchSamplerKind     = BatchSamplerKind.EpochShuffled,
    useMerge               : Boolean              = true,
    maxMergeInvocations    : MergeInvocationLimit = MergeInvocationLimit(5),
    skipPerfectScore       : Boolean              = true,
    perfectScore           : Double               = 1.0,
    failureScore           : Double               = 0.0,
    stopOnPerfectScore     : Boolean              = false,
    seed                   : Long                 = 0L,
    parallelism            : Int                  = 8
):
  require(parallelism > 0, "GEPA parallelism must be positive")

final case class GepaResult[P](
    bestCandidate   : Candidate,
    bestProgram     : P,
    bestScore       : Double,
    numCandidates   : GepaCandidateCount,
    totalMetricCalls: MetricCallCount
)

/** Effectful GEPA search over a functional record program and a visible reflection program. */
final class GepaEngine[I, O, R, RR](
    adapter  : GepaAdapter[I, O, R],
    reflector: ProgramWithEnv[InstructionProposer.Input, InstructionProposer.Output, RR],
    config   : GepaConfig
):

  private type ResultProgram = RecordProgramWithEnv[I, O, R]

  def optimize(
      seedCandidate: Candidate,
      trainset     : Vector[Example],
      valset       : Vector[Example],
      runDir       : Option[Path] = None
  ): ZIO[R & RR, DspyError, GepaResult[ResultProgram]] =
    val random  = new Random(config.seed)
    val sampler = MinibatchSampler.of(config.batchSampler, config.reflectionMinibatchSize, config.seed)
    val cache   = new GepaEvalCache(adapter)
    val merger  = Option.when(config.useMerge)(
      new MergeProposer[I, O, R](valset, config.maxMergeInvocations, random, cache)
    )

    for
      restored <- runDir.fold[IO[DspyError, Option[GepaState]]](ZIO.none)(loadState)
      initial  <- restored match
                   case Some(saved) => ZIO.fromEither(validateAndWarm(saved, seedCandidate, valset, cache)).as(saved)
                   case None        => seed(seedCandidate, valset, cache)
      _       <- checkpoint(runDir, initial)
      state   <- loop(initial, Map.empty, 0, trainset, valset, sampler, random, cache, merger, runDir)
      best     = state.bestIndex
      program <- ZIO.fromEither(adapter.applyCandidate(state.candidates(best)))
    yield GepaResult(
      bestCandidate = state.candidates(best),
      bestProgram = program,
      bestScore = state.aggregateScore(best),
      numCandidates = GepaCandidateCount.assume(state.candidates.size),
      totalMetricCalls = state.totalMetricCalls
    )

  private def seed(
      candidate: Candidate,
      valset   : Vector[Example],
      cache    : GepaEvalCache[I, O, R]
  ): ZIO[R, DspyError, GepaState] =
    val seedCost = cache.uncachedCount(candidate, valset)
    if seedCost > config.maxMetricCalls then
      ZIO.fail(ValidationError(
        s"maxMetricCalls=${config.maxMetricCalls} cannot cover the seed validation cost of $seedCost"
      ))
    else
      fullEval(candidate, valset, cache).map { case (scores, evaluations) =>
        GepaState.seed(candidate, scores, evaluations)
      }

  private def loop(
      state    : GepaState,
      pointers : Map[Int, Int],
      iteration: Int,
      trainset : Vector[Example],
      valset   : Vector[Example],
      sampler  : MinibatchSampler,
      random   : Random,
      cache    : GepaEvalCache[I, O, R],
      merger   : Option[MergeProposer[I, O, R]],
      runDir   : Option[Path]
  ): ZIO[R & RR, DspyError, GepaState] =
    if state.totalMetricCalls >= config.maxMetricCalls || converged(state) then ZIO.succeed(state)
    else
      val remaining      = config.maxMetricCalls.toLong - state.totalMetricCalls
      val mergeCost      = valset.distinct.size.toLong
      val reflectionCost = 2L * config.reflectionMinibatchSize + valset.distinct.size

      attemptMerge(state, valset, cache, merger, remaining, mergeCost).flatMap {
        case Some(mergedState) => checkpoint(runDir, mergedState) *>
            ZIO.suspendSucceed(
              loop(mergedState, pointers, iteration + 1, trainset, valset, sampler, random, cache, merger, runDir)
            )
        case None =>
          if remaining < reflectionCost then ZIO.succeed(state)
          else
            merger.foreach(_.lastIterFoundNewProgram = false)
            reflect(state, pointers, iteration, trainset, valset, sampler, random, cache).flatMap {
              case (nextState, nextPointers, accepted) =>
                if accepted then merger.foreach(_.onReflectiveAccepted())
                checkpoint(runDir, nextState) *>
                  ZIO.suspendSucceed(
                    loop(
                      nextState,
                      nextPointers,
                      iteration + 1,
                      trainset,
                      valset,
                      sampler,
                      random,
                      cache,
                      merger,
                      runDir
                    )
                  )
            }
      }

  private def attemptMerge(
      state    : GepaState,
      valset   : Vector[Example],
      cache    : GepaEvalCache[I, O, R],
      merger   : Option[MergeProposer[I, O, R]],
      remaining: Long,
      mergeCost: Long
  ): ZIO[R, Nothing, Option[GepaState]] =
    merger.filter(value => value.shouldAttempt && remaining >= mergeCost) match
      case None        => ZIO.none
      case Some(value) => value.propose(state).flatMap {
          case None =>
            value.lastIterFoundNewProgram = false
            ZIO.none
          case Some(proposal) =>
            value.lastIterFoundNewProgram = false
            if proposal.accepted then
              fullEval(proposal.candidate, valset, cache).map { case (scores, evaluations) =>
                value.onMergeAccepted()
                Some(state.add(
                  proposal.candidate,
                  scores,
                  proposal.parents,
                  proposal.metricCalls + evaluations
                ))
              }
            else
              ZIO.some(state.copy(totalMetricCalls =
                MetricCallCount.add(
                  state.totalMetricCalls,
                  proposal.metricCalls
                )
              ))
        }

  private def reflect(
      state    : GepaState,
      pointers : Map[Int, Int],
      iteration: Int,
      trainset : Vector[Example],
      valset   : Vector[Example],
      sampler  : MinibatchSampler,
      random   : Random,
      cache    : GepaEvalCache[I, O, R]
  ): ZIO[R & RR, DspyError, (GepaState, Map[Int, Int], Boolean)] =
    val parentIndex               = config.candidateSelector.select(state, random)
    val parent                    = state.candidates(parentIndex)
    val allComponents             = parent.keys.toVector.sortBy(_.value)
    val (components, nextPointer) = config.componentSelector.select(
      allComponents,
      pointers.getOrElse(parentIndex, 0)
    )
    val nextPointers = pointers.updated(parentIndex, nextPointer)
    val minibatch    = sampler.sample(trainset.size, iteration).map(trainset)

    adapter.evaluate(minibatch, parent, captureEvents = true).flatMap { parentEvaluation =>
      val initialCalls = minibatch.size
      val perfect      = config.skipPerfectScore && parentEvaluation.scores.nonEmpty &&
        parentEvaluation.scores.forall(_ >= config.perfectScore)

      if perfect then
        ZIO.succeed(
          state.copy(totalMetricCalls = MetricCallCount.add(state.totalMetricCalls, initialCalls)),
          nextPointers,
          false
        )
      else
        for
          records  <- adapter.makeReflectiveDataset(parentEvaluation, components)
          proposed <- ZIO.foldLeft(components.zipWithIndex)(parent) { case (candidate, (component, index)) =>
                        val input = InstructionProposer.Input(
                          candidate.getOrElse(component, None).getOrElse(""),
                          records.getOrElse(component, Vector.empty)
                        )
                        ProgramRunner
                          .run(reflector, input, RunOptions(rolloutId = Some(iteration * 1000 + index)))
                          .either
                          .map {
                            case Left(_)           => candidate
                            case Right(prediction) =>
                              val instruction = InstructionProposer.extractInstruction(prediction.output.instruction)
                              candidate.updated(component, Some(instruction))
                          }
                      }
          proposedEvaluation <- adapter.evaluate(minibatch, proposed, captureEvents = false)
          accepted            = proposedEvaluation.scores.sum > parentEvaluation.scores.sum
          result             <-
            if accepted then
              fullEval(proposed, valset, cache).map { case (scores, evaluations) =>
                val calls = initialCalls + minibatch.size + evaluations
                (state.add(proposed, scores, Vector(parentIndex), calls), nextPointers, true)
              }
            else
              val calls = initialCalls + minibatch.size
              ZIO.succeed(
                state.copy(totalMetricCalls = MetricCallCount.add(state.totalMetricCalls, calls)),
                nextPointers,
                false
              )
        yield result
    }

  private def fullEval(
      candidate: Candidate,
      valset   : Vector[Example],
      cache    : GepaEvalCache[I, O, R]
  ): ZIO[R, Nothing, (Vector[Double], Int)] =
    cache.scores(candidate, valset)

  private def converged(state: GepaState): Boolean =
    config.stopOnPerfectScore && state.aggregateScore(state.bestIndex) >= config.perfectScore

  private def validateAndWarm(
      state        : GepaState,
      seedCandidate: Candidate,
      valset       : Vector[Example],
      cache        : GepaEvalCache[I, O, R]
  ): Either[DspyError, Unit] =
    val expectedIds = seedCandidate.keySet
    val mismatched  = state.candidates.zipWithIndex.collect {
      case (candidate, index) if candidate.keySet != expectedIds => index
    }
    val wrongRows = state.valSubscores.zipWithIndex.collect {
      case (scores, index) if scores.size != valset.size => index
    }

    if mismatched.nonEmpty then
      Left(ValidationError(
        s"GEPA checkpoint parameter IDs do not match the current program at candidates ${mismatched.mkString(", ")}"
      ))
    else if wrongRows.nonEmpty then
      Left(ValidationError(
        s"GEPA checkpoint validation rows do not match valset size ${valset.size}: ${wrongRows.mkString(", ")}"
      ))
    else
      state.candidates.zip(state.valSubscores).foldLeft[Either[DspyError, Unit]](Right(())) {
        case (acc, (candidate, scores)) =>
          acc.flatMap(_ => cache.restore(candidate, valset, scores).left.map(ValidationError(_)))
      }

  private def loadState(dir: Path): IO[DspyError, Option[GepaState]] =
    ZIO
      .attemptBlocking(GepaStatePersistence.load(dir))
      .mapError(error => ioError("gepa_checkpoint_load", error))
      .flatMap(result => ZIO.fromEither(result.left.map(ValidationError(_))))

  private def checkpoint(dir: Option[Path], state: GepaState): IO[DspyError, Unit] =
    dir match
      case None       => ZIO.unit
      case Some(path) => ZIO
          .attemptBlocking(GepaStatePersistence.save(path, state))
          .mapError(error => ioError("gepa_checkpoint_save", error))

  private def ioError(component: String, error: Throwable): RuntimeError =
    RuntimeError(component, Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName))
