package dspy4s.gepa

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.gepa.contracts.FeedbackMetric
import dspy4s.programs.*
import zio.{UIO, ZIO}

/** Connect the GEPA search engine to a functional record program. */
final class GepaAdapter[I, O, R](
    val program     : RecordProgramWithEnv[I, O, R],
    val metric      : FeedbackMetric,
    val failureScore: Double = 0.0,
    val parallelism : Int    = 8
):
  require(parallelism > 0, "GEPA parallelism must be positive")

  private val componentsById: Map[ParameterId, ParameterBinding] =
    program.program.parameters.all.map(binding => binding.id -> binding).toMap

  def evaluate(
      batch        : Vector[Example],
      candidate    : Candidate,
      captureEvents: Boolean
  ): ZIO[R, Nothing, EvaluationBatch] =
    applyCandidate(candidate) match
      case Left(_)                 => ZIO.succeed(failedBatch(batch, captureEvents))
      case Right(candidateProgram) => ZIO
          .foreachPar(batch)(runOne(candidateProgram, _))
          .withParallelism(parallelism)
          .map { trajectories =>
            EvaluationBatch(
              outputs = trajectories.map(_.prediction),
              scores = trajectories.map(_.score),
              trajectories = Option.when(captureEvents)(trajectories)
            )
          }

  def applyCandidate(candidate: Candidate) = Candidate.applyTo(program, candidate)

  def makeReflectiveDataset(
      evalBatch : EvaluationBatch,
      components: Vector[ParameterId]
  ): UIO[Map[ParameterId, Vector[ReflectiveRecord]]] =
    val trajectories = evalBatch.trajectories.getOrElse(Vector.empty)
    ZIO.foreach(components) { component =>
      ZIO.foreach(trajectories)(recordFor(component, _)).map(records => component -> records.flatten)
    }.map(_.toMap)

  private def runOne(
      candidateProgram: RecordProgramWithEnv[I, O, R],
      example         : Example
  ): ZIO[R, Nothing, Trajectory] =
    ProgramRunner.runRecordJournaled(candidateProgram, example.inputs).flatMap { execution =>
      execution.outcome match
        case Left(_)           => ZIO.succeed(Trajectory(example, RawPrediction.empty, execution.events, failureScore))
        case Right(prediction) => metric.score(example, prediction.raw, execution.events).either.map { result =>
            Trajectory(example, prediction.raw, execution.events, result.getOrElse(failureScore))
          }
    }

  private def recordFor(component: ParameterId, trajectory: Trajectory): UIO[Option[ReflectiveRecord]] =
    componentsById.get(component) match
      case None    => ZIO.none
      case Some(_) =>
        val componentEvents = trajectory.events.filter(eventParameterId(_).contains(component))
        val inputs          = componentEvents.collectFirst {
          case ProgramEvent.Started(_, _, _, values, _) => DynamicValues.renderText(values)
        }
        inputs match
          case None                 => ZIO.none
          case Some(renderedInputs) =>
            val generatedOutputs = componentEvents.collectFirst {
              case ProgramEvent.Completed(_, _, _, values, _) => DynamicValues.renderText(values)
              case ProgramEvent.Failed(_, _, _, error, _)     => s"[${error.code}] ${error.message}"
            }.getOrElse("(no output)")
            metric
              .feedback(
                trajectory.example,
                trajectory.prediction,
                trajectory.events,
                Some(component),
                componentEvents
              )
              .either
              .map { result =>
                val feedback = result.fold(_ => FeedbackMetric.defaultFeedback(trajectory.score), _.feedback)
                Some(ReflectiveRecord(renderedInputs, generatedOutputs, feedback))
              }

  private def eventParameterId(event: ProgramEvent): Option[ParameterId] =
    event match
      case ProgramEvent.Started(_, _, _, _, parameterId)     => parameterId
      case ProgramEvent.Completed(_, _, _, _, parameterId)   => parameterId
      case ProgramEvent.Failed(_, _, _, _, parameterId)      => parameterId
      case ProgramEvent.OutputChunk(_, _, _, _, parameterId) => parameterId

  private def failedBatch(batch: Vector[Example], captureEvents: Boolean): EvaluationBatch =
    val trajectories = batch.map(example => Trajectory(example, RawPrediction.empty, Vector.empty, failureScore))
    EvaluationBatch(
      outputs = trajectories.map(_.prediction),
      scores = trajectories.map(_.score),
      trajectories = Option.when(captureEvents)(trajectories)
    )
