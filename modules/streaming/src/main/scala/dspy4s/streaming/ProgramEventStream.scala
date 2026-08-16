package dspy4s.streaming

import dspy4s.core.contracts.DspyError
import dspy4s.programs.contracts.Prediction
import dspy4s.programs.plan.{ProgramEvent, ProgramObserver, ProgramRunner, ProgramWithEnv, RunOptions}
import zio.stream.{Take, ZStream}
import zio.{Queue, ZIO}

enum ProgramStreamItem[O]:
  case Event(value: ProgramEvent)
  case Result(value: Prediction[O])

/** Live event and result stream for the functional program interpreter. */
object ProgramEventStream:

  def run[I, O, R](
      program: ProgramWithEnv[I, O, R],
      input  : I,
      options: RunOptions = RunOptions()
  ): ZStream[R, DspyError, ProgramStreamItem[O]] =
    ZStream.unwrapScoped {
      for
        queue <- Queue.unbounded[Take[DspyError, ProgramStreamItem[O]]]
        observer = new ProgramObserver:
                     def onEvent(event: ProgramEvent): ZIO[Any, Nothing, Unit] =
                       queue.offer(Take.single(ProgramStreamItem.Event(event))).unit
        _ <- ProgramRunner
               .runObserved(program, input, observer, options)
               .foldZIO(
                 error => queue.offer(Take.fail(error)).unit,
                 prediction =>
                   queue.offer(Take.single(ProgramStreamItem.Result(prediction))).unit *>
                     queue.offer(Take.end).unit
               )
               .forkScoped
      yield ZStream.fromQueue(queue).flattenTake.ensuring(queue.shutdown)
    }
