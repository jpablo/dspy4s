package dspy4s.optimize

import dspy4s.core.contracts.{DspyError, DynamicValues, ErrorLimit, RuntimeError}
import dspy4s.core.data.Example
import dspy4s.evaluate.Metric
import dspy4s.optimize.contracts.{CandidateProgram, OptimizationReport}
import dspy4s.programs.{PredictionBackend, ProgramRunner, RecordProgram, RunOptions}
import zio.ZIO

final case class BootstrapFewShotConfig(
    metric              : Option[Metric] = None,
    metricThreshold     : Option[Double] = None,
    maxBootstrappedDemos: DemoCount      = DemoCount(4),
    maxLabeledDemos     : DemoCount      = DemoCount(16),
    maxRounds           : RoundCount     = RoundCount(1),
    maxErrors           : ErrorLimit     = ErrorLimit(10),
    seed                : Long           = 0L
)

/** Effectful bootstrap optimizer for the new record-program boundary. */
object BootstrapFewShot:

  private final case class Harvest(
      bootstrapped: Vector[Example],
      failed      : Vector[Int],
      errors      : Int
  )

  private final case class Attempt(demo: Option[Example], errors: Int)

  def apply[I, O](
      student : RecordProgram[I, O],
      trainset: Vector[Example],
      teacher : Option[RecordProgram[I, O]] = None,
      config  : BootstrapFewShotConfig      = BootstrapFewShotConfig()
  ): ZIO[PredictionBackend, DspyError, OptimizationReport[RecordProgram[I, O]]] =
    if trainset.isEmpty then ZIO.succeed(emptyReport(student))
    else
      val teacherProgram = teacher.getOrElse(student)
      ZIO.foldLeft(trainset.zipWithIndex)(Harvest(Vector.empty, Vector.empty, 0)) { case (state, (example, index)) =>
        if state.bootstrapped.size >= config.maxBootstrappedDemos then ZIO.succeed(state)
        else
          tryRounds(teacherProgram, example, config, state.errors).map { attempt =>
            attempt.demo match
              case Some(demo) => state.copy(bootstrapped = state.bootstrapped :+ demo, errors = attempt.errors)
              case None       => state.copy(failed = state.failed :+ index, errors = attempt.errors)
          }
      }.flatMap(state => finish(student, trainset, state, config))

  private def tryRounds[I, O](
      teacher      : RecordProgram[I, O],
      example      : Example,
      config       : BootstrapFewShotConfig,
      errorsAtStart: Int
  ): ZIO[PredictionBackend, DspyError, Attempt] =
    def loop(round: Int, errors: Int): ZIO[PredictionBackend, DspyError, Attempt] =
      if round >= config.maxRounds then ZIO.succeed(Attempt(None, errors))
      else
        ProgramRunner
          .runRecordJournaled(teacher, example.inputs, RunOptions(rolloutId = Some(round)))
          .flatMap { execution =>
            execution.outcome match
              case Left(error)       => continueAfterError(round, errors, error)
              case Right(prediction) => config.metric match
                  case None         => ZIO.succeed(Attempt(Some(toDemo(example, prediction.raw.values)), errors))
                  case Some(metric) => metric.score(example, prediction.raw, execution.events).either.flatMap {
                      case Left(error)  => continueAfterError(round, errors, error)
                      case Right(score) =>
                        val accepted = config.metricThreshold.fold(score > 0.0)(score >= _)
                        if accepted then ZIO.succeed(Attempt(Some(toDemo(example, prediction.raw.values)), errors))
                        else ZIO.suspendSucceed(loop(round + 1, errors))
                    }
          }

    def continueAfterError(
        round : Int,
        errors: Int,
        error : DspyError
    ): ZIO[PredictionBackend, DspyError, Attempt] =
      val nextErrors = errors + 1
      if nextErrors >= config.maxErrors then
        ZIO.fail(RuntimeError(
          "bootstrap",
          s"Too many bootstrap errors ($nextErrors); last error: ${error.message}"
        ))
      else ZIO.suspendSucceed(loop(round + 1, nextErrors))

    loop(0, errorsAtStart)

  private def finish[I, O](
      student : RecordProgram[I, O],
      trainset: Vector[Example],
      state   : Harvest,
      config  : BootstrapFewShotConfig
  ): ZIO[Any, DspyError, OptimizationReport[RecordProgram[I, O]]] =
    val random       = new scala.util.Random(config.seed)
    val labeledPool  = Vector.from(random.shuffle(state.failed.map(trainset)))
    val labeledCount = math.min(
      config.maxLabeledDemos - math.min(state.bootstrapped.size, config.maxBootstrappedDemos),
      labeledPool.size
    ).max(0)
    val labeled      = labeledPool.take(labeledCount)
    val demos        = state.bootstrapped.take(config.maxBootstrappedDemos) ++ labeled
    val replacements = student.program.parameters.all.map { binding =>
      binding.id -> binding.value.copy(demos = demos)
    }.toMap

    ZIO.fromEither(student.replaceParameters(replacements)).map { compiled =>
      OptimizationReport(
        bestProgram = compiled,
        candidates = Vector(CandidateProgram(
          program = compiled,
          score = 0.0,
          metadata = Map(
            "optimizer"              -> "bootstrap_few_shot",
            "num_bootstrapped_demos" -> state.bootstrapped.size,
            "num_labeled_demos"      -> labeled.size,
            "num_errors"             -> state.errors,
            "num_failed_examples"    -> state.failed.size
          )
        )),
        metadata = Map(
          "max_bootstrapped_demos" -> config.maxBootstrappedDemos,
          "max_labeled_demos"      -> config.maxLabeledDemos,
          "max_rounds"             -> config.maxRounds,
          "max_errors"             -> config.maxErrors,
          "seed"                   -> config.seed
        )
      )
    }

  private def toDemo(example: Example, outputs: zio.blocks.schema.DynamicValue.Record): Example =
    Example(
      values = DynamicValues.mergeRecords(example.inputs, outputs),
      inputKeys = example.inputKeys,
      augmented = true
    )

  private def emptyReport[I, O](student: RecordProgram[I, O]): OptimizationReport[RecordProgram[I, O]] =
    OptimizationReport(
      bestProgram = student,
      candidates = Vector(CandidateProgram(
        student,
        0.0,
        metadata = Map("optimizer" -> "bootstrap_few_shot", "reason" -> "empty_trainset")
      )),
      metadata = Map("optimizer" -> "bootstrap_few_shot")
    )
