package dspy4s.optimize

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.optimize.contracts.CandidateProgram
import dspy4s.optimize.contracts.OptimizationReport
import dspy4s.optimize.contracts.Teleprompter

import scala.annotation.nowarn
import scala.util.boundary
import scala.util.boundary.break
import scala.util.control.NonFatal

final case class BootstrapFewShotConfig(
    metric: Option[dspy4s.evaluate.contracts.Metric] = None,
    metricThreshold: Option[Double] = None,
    maxBootstrappedDemos: Int = 4,
    maxLabeledDemos: Int = 16,
    maxRounds: Int = 1,
    maxErrors: Int = 10,
    seed: Long = 0L
):
  require(maxBootstrappedDemos >= 0, "maxBootstrappedDemos must be non-negative")
  require(maxLabeledDemos >= 0, "maxLabeledDemos must be non-negative")
  require(maxRounds >= 1, "maxRounds must be at least 1")
  require(maxErrors > 0, "maxErrors must be > 0")

final class BootstrapFewShot[P: Predictors: Runnable](
    config: BootstrapFewShotConfig = BootstrapFewShotConfig()
) extends Teleprompter[P]:

  override val name: String = "bootstrap_few_shot"

  override def compile(
      student: P,
      trainset: Vector[Example],
      teacher: Option[P] = None,
      valset: Option[Vector[Example]] = None
  )(using ctx: RuntimeContext): Either[DspyError, OptimizationReport[P]] =
    if trainset.isEmpty then
      Right(
        OptimizationReport(
          bestProgram = student,
          candidates = Vector(CandidateProgram(student, 0.0, metadata = Map("optimizer" -> name, "reason" -> "empty_trainset"))),
          metadata = Map("optimizer" -> name)
        )
      )
    else
      compileInternal(student, trainset, teacher)

  @nowarn("msg=unused")
  private def compileInternal(
      student: P,
      trainset: Vector[Example],
      teacher: Option[P]
  )(using ctx: RuntimeContext): Either[DspyError, OptimizationReport[P]] =
    val teacherProgram: P = teacher.getOrElse(student)

    var errorCount = 0
    val bootstrapped = scala.collection.mutable.ArrayBuffer.empty[Example]
    val failedIndices = scala.collection.mutable.ArrayBuffer.empty[Int]

    boundary {
      trainset.zipWithIndex.foreach { case (example, idx) =>
        if bootstrapped.size >= config.maxBootstrappedDemos then ()
        else
          var round = 0
          var success = false
          while round < config.maxRounds && !success do
            try
              val runOutcome: Either[DspyError, DynamicPrediction] =
                dspy4s.core.runtime.RuntimeEnvironment.withGeneratedAsyncTask(s"bootstrap-round-$round") {
                  given RuntimeContext = dspy4s.core.runtime.RuntimeEnvironment.current
                  summon[Runnable[P]].run(teacherProgram, example.inputs)
                }
              runOutcome match
                case Left(_) => ()
                case Right(prediction) =>
                  val metricOk = config.metric match
                    case None => true
                    case Some(m) =>
                      m.score(example, prediction) match
                        case Right(score) =>
                          config.metricThreshold match
                            case None => score > 0.0
                            case Some(threshold) => score >= threshold
                        case Left(_) => false

                  if metricOk then
                    val demoValues = zio.blocks.schema.DynamicValue.Record(
                      zio.blocks.chunk.Chunk.from(
                        example.inputs.fields.iterator.toSeq ++ prediction.values.fields.iterator.toSeq
                      )
                    )
                    val demo = Example(values = demoValues, inputKeys = example.inputKeys, augmented = true)
                    bootstrapped += demo
                    success = true
            catch
              case NonFatal(err) =>
                errorCount += 1
                if errorCount >= config.maxErrors then
                  break(
                    Left(
                      RuntimeError(
                        "bootstrap",
                        s"Too many bootstrap errors (${errorCount}): ${err.getMessage}"
                      )
                    )
                  )
            end try
            round += 1

          if !success then failedIndices += idx
      }
      val ps = summon[Predictors[P]]
      val rng = new scala.util.Random(config.seed)
      val labeledPool = failedIndices.toVector.map(idx => trainset(idx))
      val labeledPoolShuffled = Vector.from(rng.shuffle(labeledPool))
      val labeledCount = math.min(
        config.maxLabeledDemos - math.min(bootstrapped.size, config.maxBootstrappedDemos),
        labeledPoolShuffled.size
      ).max(0)
      val rawDemos = labeledPoolShuffled.take(labeledCount)

      val allDemos = bootstrapped.toVector.take(config.maxBootstrappedDemos) ++ rawDemos
      val compiled = ps.replace(student, ps.read(student).map(_.copy(demos = allDemos)))

      Right(
        OptimizationReport(
          bestProgram = compiled,
          candidates = Vector(
            CandidateProgram(
              program = compiled,
              score = 0.0,
              metadata = Map(
                "optimizer" -> name,
                "num_bootstrapped_demos" -> bootstrapped.size,
                "num_labeled_demos" -> rawDemos.size,
                "num_errors" -> errorCount,
                "num_failed_examples" -> failedIndices.size
              )
            )
          ),
          metadata = Map(
            "max_bootstrapped_demos" -> config.maxBootstrappedDemos,
            "max_labeled_demos" -> config.maxLabeledDemos,
            "max_rounds" -> config.maxRounds,
            "max_errors" -> config.maxErrors,
            "seed" -> config.seed
          )
        )
      )
    }
