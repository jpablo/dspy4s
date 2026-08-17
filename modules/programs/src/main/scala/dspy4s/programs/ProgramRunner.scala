package dspy4s.programs

import dspy4s.core.contracts.{CodeResult, DspyError, DynamicValues, NotFoundError, RuntimeError, SignatureLayout}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.programs.contracts.{Prediction, ToolCallRequest, ToolCallResult}
import dspy4s.programs.optimization.OptimizableParameters
import dspy4s.programs.Program.{Node, PredictSpec}
import zio.blocks.schema.DynamicValue
import zio.{IO, Ref, UIO, ZIO}

/** Immutable controls for one execution. The input is a separate typed argument. */
final case class RunOptions(
    config      : DynamicValue.Record = DynamicValue.Record.empty,
    rolloutId   : Option[Int]         = None,
    traceEnabled: Boolean             = true
) derives CanEqual

/** Data-only request produced by a typed prediction instruction.
  *
  * A backend receives encoded inputs and effective prompt parameters. It does not receive a program node or an ambient
  * runtime singleton.
  */
final case class PredictionRequest(
    parameterId     : ParameterId,
    component       : String,
    layout          : SignatureLayout,
    demos           : Vector[Example],
    inputs          : DynamicValue.Record,
    config          : DynamicValue.Record,
    rolloutId       : Option[Int],
    outputJsonSchema: Option[String],
    tools           : Vector[ToolSpec]
)

/** Effect boundary for language-model prediction. */
trait PredictionBackend:
  def generate(request: PredictionRequest): IO[DspyError, RawPrediction]

  /** Generate one complete prediction and report live output chunks when the backend supports streaming. */
  def generateStreaming(
      request                : PredictionRequest,
      @annotation.unused emit: PredictionChunk => UIO[Unit]
  ): IO[DspyError, RawPrediction] = generate(request)

/** One backend-neutral fragment of a prediction output field. */
final case class PredictionChunk(fieldName: String, text: String, isLast: Boolean = false) derives CanEqual

/** Effect boundary for generated-code execution. */
trait CodeExecutionBackend:
  def execute(code: String): IO[DspyError, CodeExecutionResult]

/** Effect boundary for host-tool invocation. */
trait ToolBackend:
  def invoke(request: ToolCallRequest): IO[DspyError, ToolCallResult]

/** One event in the explicit execution journal. IDs are local to one [[Execution]]. */
enum ProgramEvent:
  case Started(
      callId      : Int,
      parentCallId: Option[Int],
      component   : String,
      inputs      : DynamicValue.Record,
      parameterId : Option[ParameterId]
  )
  case Completed(
      callId      : Int,
      parentCallId: Option[Int],
      component   : String,
      outputs     : DynamicValue.Record,
      parameterId : Option[ParameterId]
  )
  case Failed(
      callId      : Int,
      parentCallId: Option[Int],
      component   : String,
      error       : DspyError,
      parameterId : Option[ParameterId]
  )
  case OutputChunk(
      callId      : Int,
      parentCallId: Option[Int],
      component   : String,
      chunk       : PredictionChunk,
      parameterId : Option[ParameterId]
  )

/** Effectful consumer for live execution events. */
trait ProgramObserver:
  def onEvent(event: ProgramEvent): UIO[Unit]

object ProgramObserver:
  val noop: ProgramObserver = new ProgramObserver:
    def onEvent(@annotation.unused event: ProgramEvent): UIO[Unit] = ZIO.unit

/** Complete result of one program run. Failure does not discard the journal. */
final case class Execution[O](
    outcome: Either[DspyError, Prediction[O]],
    events : Vector[ProgramEvent]
)

/** ZIO interpreter for the typed program syntax.
  *
  * Recursive interpretation is suspended in `ZIO`, so deep syntax trees use the ZIO continuation stack instead of the
  * JVM call stack. Mutable execution state is scoped to two functional `Ref` values created for one run. The backend is
  * an explicit environment service. The interpreter has no instance state.
  */
object ProgramRunner:

  /** Run a program and keep domain failure in ZIO's typed error channel. */
  def run[I, O, R](
      program: ProgramWithEnv[I, O, R],
      input  : I,
      options: RunOptions = RunOptions()
  ): ZIO[R, DspyError, Prediction[O]] =
    runObserved(program, input, ProgramObserver.noop, options)

  def runObserved[I, O, R](
      program : ProgramWithEnv[I, O, R],
      input   : I,
      observer: ProgramObserver,
      options : RunOptions = RunOptions()
  ): ZIO[R, DspyError, Prediction[O]] =
    runJournaledObserved(program, input, observer, options).flatMap(execution => ZIO.fromEither(execution.outcome))

  /** Run a program and retain its event journal even when domain execution fails. */
  def runJournaled[I, O, R](
      program: ProgramWithEnv[I, O, R],
      input  : I,
      options: RunOptions = RunOptions()
  ): ZIO[R, Nothing, Execution[O]] =
    runJournaledObserved(program, input, ProgramObserver.noop, options)

  def runJournaledObserved[I, O, R](
      program : ProgramWithEnv[I, O, R],
      input   : I,
      observer: ProgramObserver,
      options : RunOptions = RunOptions()
  ): ZIO[R, Nothing, Execution[O]] =
    for
      events   <- Ref.make(Vector.empty[ProgramEvent])
      nextId   <- Ref.make(0)
      journal   = EventJournal(events, observer)
      outcome  <- evaluate(erase(program.root), input, options, None, program.parameters, journal, nextId).either
      recorded <- events.get
    yield Execution(outcome.map(_.asInstanceOf[Prediction[O]]), recorded)

  /** Decode and run at the explicit record boundary used by datasets and optimizers. */
  def runRecord[I, O, R](
      program: RecordProgramWithEnv[I, O, R],
      input  : DynamicValue.Record,
      options: RunOptions = RunOptions()
  ): ZIO[R, DspyError, Prediction[O]] =
    ZIO.fromEither(program.inputShape.decode(input)).flatMap(decoded => run(program.program, decoded, options))

  def runRecordObserved[I, O, R](
      program : RecordProgramWithEnv[I, O, R],
      input   : DynamicValue.Record,
      observer: ProgramObserver,
      options : RunOptions = RunOptions()
  ): ZIO[R, DspyError, Prediction[O]] =
    ZIO.fromEither(program.inputShape.decode(input)).flatMap(decoded =>
      runObserved(program.program, decoded, observer, options)
    )

  /** Decode and run while retaining the journal on program failure. Decode failure has an empty journal. */
  def runRecordJournaled[I, O, R](
      program: RecordProgramWithEnv[I, O, R],
      input  : DynamicValue.Record,
      options: RunOptions = RunOptions()
  ): ZIO[R, Nothing, Execution[O]] =
    ZIO.fromEither(program.inputShape.decode(input)).foldZIO(
      error => ZIO.succeed(Execution(Left(error), Vector.empty)),
      decoded => runJournaled(program.program, decoded, options)
    )

  def runRecordJournaledObserved[I, O, R](
      program : RecordProgramWithEnv[I, O, R],
      input   : DynamicValue.Record,
      observer: ProgramObserver,
      options : RunOptions = RunOptions()
  ): ZIO[R, Nothing, Execution[O]] =
    ZIO.fromEither(program.inputShape.decode(input)).foldZIO(
      error => ZIO.succeed(Execution(Left(error), Vector.empty)),
      decoded => runJournaledObserved(program.program, decoded, observer, options)
    )

  private def evaluate[R](
      node        : Node[?, ?],
      input       : Any,
      options     : RunOptions,
      parentCallId: Option[Int],
      store       : ParameterStore,
      journal     : EventJournal,
      nextId      : Ref[Int]
  ): ZIO[R, DspyError, Prediction[Any]] =
    ZIO.suspendSucceed {
      node match
        case _: Node.Identity[?] => ZIO.succeed(Prediction.pure(input))

        case lift: Node.Lift[?, ?] =>
          attempt("program_lift")(lift.run.asInstanceOf[Any => Any](input)).map(Prediction.pure)

        case lift: Node.LiftEither[?, ?] => attempt("program_lift_either")(
            lift.run.asInstanceOf[Any => Either[DspyError, Any]](input)
          ).flatMap(ZIO.fromEither).map(Prediction.pure)

        case predict: Node.Predict[?, ?] => requireService[R, PredictionBackend, Prediction[Any]](runPredict(
            predict.spec.asInstanceOf[PredictSpec[Any, Any]],
            input,
            options,
            parentCallId,
            store,
            journal,
            nextId
          ))

        case _: Node.ExecuteCode => requireService[R, CodeExecutionBackend, CodeExecutionResult](
            ZIO.serviceWithZIO[CodeExecutionBackend](_.execute(input.asInstanceOf[String]))
          ).map(Prediction.pure)

        case _: Node.InvokeTool => requireService[R, ToolBackend, ToolCallResult](
            ZIO.serviceWithZIO[ToolBackend](_.invoke(input.asInstanceOf[ToolCallRequest]))
          ).map(Prediction.pure)

        case _: Node.ExecuteRepl => requireService[R, ReplExecutionBackend, CodeResult](
            ZIO.serviceWithZIO[ReplExecutionBackend](_.execute(input.asInstanceOf[ReplExecutionRequest]))
          ).map(Prediction.pure)

        case sequential: Node.AndThen[?, ?, ?] =>
          evaluate(erase(sequential.first), input, options, parentCallId, store, journal, nextId).flatMap { first =>
            evaluate(erase(sequential.second), first.output, options, parentCallId, store, journal, nextId).map {
              second =>
                Prediction(second.output, first.raw.followedBy(second.raw))
            }
          }

        case fanout: Node.Fanout[?, ?, ?] =>
          evaluate(erase(fanout.left), input, options, parentCallId, store, journal, nextId).flatMap { left =>
            evaluate(erase(fanout.right), input, options, parentCallId, store, journal, nextId).map { right =>
              Prediction(left.output -> right.output, left.raw.followedBy(right.raw))
            }
          }

        case split: Node.Split[?, ?, ?, ?] =>
          val pair = input.asInstanceOf[(Any, Any)]
          evaluate(erase(split.left), pair._1, options, parentCallId, store, journal, nextId).flatMap { left =>
            evaluate(erase(split.right), pair._2, options, parentCallId, store, journal, nextId).map { right =>
              Prediction(left.output -> right.output, left.raw.followedBy(right.raw))
            }
          }

        case choice: Node.Choice[?, ?, ?] => input.asInstanceOf[Either[Any, Any]] match
            case Left(value)  => evaluate(erase(choice.left), value, options, parentCallId, store, journal, nextId)
            case Right(value) => evaluate(erase(choice.right), value, options, parentCallId, store, journal, nextId)

        case mapped: Node.MapOutput[?, ?, ?] =>
          evaluate(erase(mapped.inner), input, options, parentCallId, store, journal, nextId).flatMap { value =>
            attempt("program_map_output")(mapped.map.asInstanceOf[Any => Any](value.output))
              .map(output => value.map(_ => output))
          }

        case contramapped: Node.ContramapInput[?, ?, ?] => attempt("program_contramap_input")(
            contramapped.contramap.asInstanceOf[Any => Any](input)
          ).flatMap { mappedInput =>
            evaluate(erase(contramapped.inner), mappedInput, options, parentCallId, store, journal, nextId)
          }

        case local: Node.Local[?, ?] => attempt("program_local_options")(local.update(options)).flatMap { nextOptions =>
            evaluate(erase(local.inner), input, nextOptions, parentCallId, store, journal, nextId)
          }

        case local: Node.LocalWithInput[?, ?] => attempt("program_local_input_options")(
            local.update.asInstanceOf[(Any, RunOptions) => RunOptions](input, options)
          ).flatMap { nextOptions =>
            evaluate(erase(local.inner), input, nextOptions, parentCallId, store, journal, nextId)
          }

        case local: Node.LocalParameters[?, ?, ?] =>
          evaluate(erase(local.configurator), input, options, parentCallId, store, journal, nextId).flatMap {
            configuration =>
              attempt("program_local_parameters")(
                local.update.asInstanceOf[(ParameterStore, Any) => Either[DspyError, ParameterStore]](
                  store,
                  configuration.output
                )
              ).flatMap(ZIO.fromEither).flatMap { nextStore =>
                evaluate(erase(local.inner), input, options, parentCallId, nextStore, journal, nextId).map { result =>
                  Prediction(result.output, configuration.raw.followedBy(result.raw))
                }
              }
          }

        case recovered: Node.Recover[?, ?] =>
          evaluate(erase(recovered.primary), input, options, parentCallId, store, journal, nextId).catchAll { error =>
            attempt("program_recovery_policy")(recovered.when(error)).flatMap { allowed =>
              if allowed then
                evaluate(erase(recovered.fallback), input, options, parentCallId, store, journal, nextId)
              else ZIO.fail(error)
            }
          }

        case attempted: Node.Attempt[?, ?] =>
          evaluate(erase(attempted.inner), input, options, parentCallId, store, journal, nextId)
            .map(prediction => prediction.map(Right(_)))
            .catchAll(error => ZIO.succeed(Prediction.pure(Left(error))))

        case captured: Node.WithEvidence[?, ?] =>
          evaluate(erase(captured.inner), input, options, parentCallId, store, journal, nextId).map { prediction =>
            Prediction(prediction, prediction.raw)
          }

        case restored: Node.FromEvidence[?, ?] =>
          evaluate(erase(restored.inner), input, options, parentCallId, store, journal, nextId).map { result =>
            result.output.asInstanceOf[Prediction[Any]]
          }

        case collected: Node.CollectAll[?, ?] =>
          val members = collected.members.asInstanceOf[Vector[Node[Any, Any]]]
          ZIO.foldLeft(members)(Prediction.pure(Vector.empty[Any])) { (accumulated, member) =>
            evaluate(erase(member), input, options, parentCallId, store, journal, nextId).map { prediction =>
              Prediction(
                accumulated.output.asInstanceOf[Vector[Any]] :+ prediction.output,
                accumulated.raw.followedBy(prediction.raw)
              )
            }
          }

        case collected: Node.CollectAllPar[?, ?] =>
          val members = collected.members.asInstanceOf[Vector[Node[Any, Any]]]
          ZIO
            .foreachPar(members)(member =>
              evaluate(erase(member), input, options, parentCallId, store, journal, nextId)
            )
            .withParallelism(collected.parallelism)
            .map { predictions =>
              predictions.foldLeft(Prediction.pure(Vector.empty[Any])) { (accumulated, prediction) =>
                Prediction(
                  accumulated.output.asInstanceOf[Vector[Any]] :+ prediction.output,
                  accumulated.raw.followedBy(prediction.raw)
                )
              }
            }

        case iterate: Node.Iterate[?, ?] =>
          def loop(state: Any, remaining: Int, accumulated: RawPrediction)
              : ZIO[R, DspyError, Prediction[Any]] =
            ZIO.suspendSucceed {
              evaluate(erase(iterate.step), state, options, parentCallId, store, journal, nextId).flatMap {
                transition =>
                  val nextRaw = accumulated.followedBy(transition.raw)
                  transition.output.asInstanceOf[LoopDecision[Any, Any]] match
                    case LoopDecision.Done(output)        => ZIO.succeed(Prediction(output, nextRaw))
                    case LoopDecision.Continue(nextState) =>
                      if remaining > 1 then loop(nextState, remaining - 1, nextRaw)
                      else
                        ZIO.fail(RuntimeError(
                          "program_loop",
                          s"Program loop did not finish within ${iterate.maxSteps} steps"
                        ))
              }
            }

          loop(input, iterate.maxSteps, RawPrediction.empty)

        case selection: Node.BestOfN[?, ?] =>
          def select(
              index    : Int,
              failures : Int,
              best     : Option[(Double, Prediction[Any])],
              lastError: Option[DspyError]
          ): ZIO[R, DspyError, Prediction[Any]] =
            if index >= selection.attempts then
              best.fold[ZIO[R, DspyError, Prediction[Any]]](
                ZIO.fail(lastError.getOrElse(RuntimeError("program_best_of_n", "No attempt produced a result")))
              )(entry => ZIO.succeed(entry._2))
            else
              val rolloutBase    = options.rolloutId.getOrElse(0)
              val attemptOptions = options.copy(rolloutId = Some(rolloutBase + index))
              evaluate(
                erase(selection.inner),
                input,
                attemptOptions,
                parentCallId,
                store,
                journal,
                nextId
              ).flatMap { prediction =>
                attempt("program_best_of_n_reward")(
                  selection.reward.asInstanceOf[(Any, Prediction[Any]) => Either[DspyError, Double]](input, prediction)
                ).flatMap(ZIO.fromEither).flatMap { score =>
                  val nextBest = best match
                    case Some(current) if current._1 >= score => best
                    case _                                    => Some(score -> prediction)
                  if selection.threshold.exists(score >= _) then ZIO.succeed(prediction)
                  else ZIO.suspendSucceed(select(index + 1, failures, nextBest, lastError))
                }
              }.catchAll { error =>
                val nextFailures = failures + 1
                if nextFailures >= selection.failAfter then ZIO.fail(error)
                else ZIO.suspendSucceed(select(index + 1, nextFailures, best, Some(error)))
              }

          ZIO.suspendSucceed(select(0, 0, None, None))

        case observed: Node.Observe[?, ?] =>
          if !options.traceEnabled then
            evaluate(erase(observed.inner), input, options, parentCallId, store, journal, nextId)
          else
            for
              encoded <- attempt("program_observe_input")(
                           observed.encodeInput.asInstanceOf[Any => DynamicValue.Record](input)
                         )
              callId <- freshId(nextId)
              _      <- append(journal, ProgramEvent.Started(callId, parentCallId, observed.name, encoded, None))
              result <- evaluate(
                          erase(observed.inner),
                          input,
                          options,
                          Some(callId),
                          store,
                          journal,
                          nextId
                        ).tapBoth(
                          error =>
                            append(journal, ProgramEvent.Failed(callId, parentCallId, observed.name, error, None)),
                          prediction =>
                            append(
                              journal,
                              ProgramEvent.Completed(callId, parentCallId, observed.name, prediction.raw.values, None)
                            )
                        )
            yield result
    }

  private def runPredict(
      spec        : PredictSpec[Any, Any],
      input       : Any,
      options     : RunOptions,
      parentCallId: Option[Int],
      store       : ParameterStore,
      journal     : EventJournal,
      nextId      : Ref[Int]
  ): ZIO[PredictionBackend, DspyError, Prediction[Any]] =
    for
      binding <- ZIO.fromOption(store.binding(spec.parameterId)).orElseFail(
                   NotFoundError("program_parameter", s"Missing parameters for '${spec.parameterId.value}'")
                 )
      encoded <- attempt("program_predict_encode")(spec.signature.inputShape.encode(input))
      _       <- validateInputs(spec, encoded)
      result  <-
        if options.traceEnabled then
          freshId(nextId).flatMap(callId =>
            executePredict(spec, binding.value, encoded, options, parentCallId, Some(callId), journal)
          )
        else executePredict(spec, binding.value, encoded, options, parentCallId, None, journal)
    yield result

  private def validateInputs(
      spec   : PredictSpec[Any, Any],
      encoded: DynamicValue.Record
  ): IO[DspyError, Unit] =
    val expected = spec.signature.layout.inputFields.iterator.map(_.name).toSet
    val present  = DynamicValues.recordKeys(encoded).toSet
    val missing  = expected -- present
    if missing.isEmpty then ZIO.unit
    else
      ZIO.fail(NotFoundError(
        "program_input",
        s"Missing required inputs for '${spec.signature.name}': ${missing.toVector.sorted.mkString(", ")}"
      ))

  private def executePredict(
      spec        : PredictSpec[Any, Any],
      parameters  : OptimizableParameters,
      encodedInput: DynamicValue.Record,
      options     : RunOptions,
      parentCallId: Option[Int],
      callId      : Option[Int],
      journal     : EventJournal
  ): ZIO[PredictionBackend, DspyError, Prediction[Any]] =
    val effectiveSignature = spec.signature.withInstructions(parameters.instructions)
    val request            = PredictionRequest(
      parameterId = spec.parameterId,
      component = spec.name,
      layout = effectiveSignature.layout,
      demos = parameters.demos,
      inputs = encodedInput,
      config = DynamicValues.mergeRecords(parameters.config, options.config),
      rolloutId = options.rolloutId,
      outputJsonSchema = effectiveSignature.outputShape.jsonSchemaString,
      tools = spec.tools
    )

    val start = callId.fold[UIO[Unit]](ZIO.unit)(id =>
      append(journal, ProgramEvent.Started(id, parentCallId, spec.name, encodedInput, Some(spec.parameterId)))
    )
    val emit = (chunk: PredictionChunk) =>
      callId.fold[UIO[Unit]](ZIO.unit)(id =>
        append(journal, ProgramEvent.OutputChunk(id, parentCallId, spec.name, chunk, Some(spec.parameterId)))
      )
    start *>
      ZIO
        .serviceWithZIO[PredictionBackend] { backend =>
          if callId.isDefined then backend.generateStreaming(request, emit)
          else backend.generate(request)
        }
        .flatMap(raw => ZIO.fromEither(Prediction.from(raw, effectiveSignature.outputShape)))
        .tapBoth(
          error =>
            callId.fold[UIO[Unit]](ZIO.unit)(id =>
              append(journal, ProgramEvent.Failed(id, parentCallId, spec.name, error, Some(spec.parameterId)))
            ),
          prediction =>
            callId.fold[UIO[Unit]](ZIO.unit)(id =>
              append(
                journal,
                ProgramEvent.Completed(id, parentCallId, spec.name, prediction.raw.values, Some(spec.parameterId))
              )
            )
        )

  private def attempt[A](component: String)(body: => A): IO[DspyError, A] =
    ZIO.attempt(body).mapError(error =>
      RuntimeError(
        component,
        Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
      )
    )

  private def freshId(nextId: Ref[Int]): UIO[Int] = nextId.getAndUpdate(_ + 1)

  private final case class EventJournal(events: Ref[Vector[ProgramEvent]], observer: ProgramObserver)

  private def append(journal: EventJournal, event: ProgramEvent): UIO[Unit] =
    journal.events.update(_ :+ event) *> journal.observer.onEvent(event)

  private def erase[I, O](node: Node[I, O]): Node[?, ?] = node

  /** Smart constructors keep the phantom environment honest. This cast only forgets a leaf's narrower requirement. */
  private def requireService[R, S, A](
      effect: ZIO[S, DspyError, A]
  ): ZIO[R, DspyError, A] =
    effect.asInstanceOf[ZIO[R, DspyError, A]]
