package dspy4s.programs.plan

import dspy4s.core.contracts.{DspyError, DynamicValues, NotFoundError, RuntimeError, SignatureLayout}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.programs.contracts.Prediction
import dspy4s.programs.optimization.OptimizableParameters
import dspy4s.programs.plan.Program.{Node, PredictSpec}
import zio.blocks.schema.DynamicValue
import zio.{IO, Ref, UIO, URIO, ZIO}

/** Immutable controls for one execution. The input is a separate typed argument. */
final case class RunOptions(
    config      : DynamicValue.Record = DynamicValue.Record.empty,
    rolloutId   : Option[Int]         = None,
    traceEnabled: Boolean             = true
) derives CanEqual

/** Data-only request produced by a typed prediction instruction.
  *
  * A backend receives encoded inputs and effective prompt parameters. It does not receive a program node or an
  * ambient runtime singleton.
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

/** One event in the explicit execution journal. IDs are local to one [[Execution]]. */
enum ProgramEvent:
  case Started(
      callId      : Int,
      parentCallId: Option[Int],
      component   : String,
      inputs      : DynamicValue.Record
  )
  case Completed(
      callId      : Int,
      parentCallId: Option[Int],
      component   : String,
      outputs     : DynamicValue.Record
  )
  case Failed(
      callId      : Int,
      parentCallId: Option[Int],
      component   : String,
      error       : DspyError
  )

/** Complete result of one program run. Failure does not discard the journal. */
final case class Execution[O](
    outcome: Either[DspyError, Prediction[O]],
    events : Vector[ProgramEvent]
)

/** ZIO interpreter for the typed program syntax.
  *
  * Recursive interpretation is suspended in `ZIO`, so deep syntax trees use the ZIO continuation stack instead of the
  * JVM call stack. Mutable execution state is scoped to two functional `Ref` values created for one run. The backend
  * is an explicit environment service. The interpreter has no instance state.
  */
object ProgramRunner:

  /** Run a program and keep domain failure in ZIO's typed error channel. */
  def run[I, O](
      program: Program[I, O],
      input  : I,
      options: RunOptions = RunOptions()
  ): ZIO[PredictionBackend, DspyError, Prediction[O]] =
    runJournaled(program, input, options).flatMap(execution => ZIO.fromEither(execution.outcome))

  /** Run a program and retain its event journal even when domain execution fails. */
  def runJournaled[I, O](
      program: Program[I, O],
      input  : I,
      options: RunOptions = RunOptions()
  ): URIO[PredictionBackend, Execution[O]] =
    for
      events  <- Ref.make(Vector.empty[ProgramEvent])
      nextId  <- Ref.make(0)
      outcome <- evaluate(erase(program.root), input, options, None, program.parameters, events, nextId).either
      journal <- events.get
    yield Execution(outcome.map(_.asInstanceOf[Prediction[O]]), journal)

  /** Decode and run at the explicit record boundary used by datasets and optimizers. */
  def runRecord[I, O](
      program: RecordProgram[I, O],
      input  : DynamicValue.Record,
      options: RunOptions = RunOptions()
  ): ZIO[PredictionBackend, DspyError, Prediction[O]] =
    ZIO.fromEither(program.inputShape.decode(input)).flatMap(decoded => run(program.program, decoded, options))

  /** Decode and run while retaining the journal on program failure. Decode failure has an empty journal. */
  def runRecordJournaled[I, O](
      program: RecordProgram[I, O],
      input  : DynamicValue.Record,
      options: RunOptions = RunOptions()
  ): URIO[PredictionBackend, Execution[O]] =
    ZIO.fromEither(program.inputShape.decode(input)).foldZIO(
      error => ZIO.succeed(Execution(Left(error), Vector.empty)),
      decoded => runJournaled(program.program, decoded, options)
    )

  private def evaluate(
      node        : Node[?, ?],
      input       : Any,
      options     : RunOptions,
      parentCallId: Option[Int],
      store       : ParameterStore,
      events      : Ref[Vector[ProgramEvent]],
      nextId      : Ref[Int]
  ): ZIO[PredictionBackend, DspyError, Prediction[Any]] =
    ZIO.suspendSucceed {
      node match
        case _: Node.Identity[?] =>
          ZIO.succeed(Prediction.pure(input))

        case lift: Node.Lift[?, ?] =>
          attempt("program_lift")(lift.run.asInstanceOf[Any => Any](input)).map(Prediction.pure)

        case lift: Node.LiftEither[?, ?] =>
          attempt("program_lift_either")(
            lift.run.asInstanceOf[Any => Either[DspyError, Any]](input)
          ).flatMap(ZIO.fromEither).map(Prediction.pure)

        case predict: Node.Predict[?, ?] =>
          runPredict(
            predict.spec.asInstanceOf[PredictSpec[Any, Any]],
            input,
            options,
            parentCallId,
            store,
            events,
            nextId
          )

        case sequential: Node.AndThen[?, ?, ?] =>
          evaluate(erase(sequential.first), input, options, parentCallId, store, events, nextId).flatMap { first =>
            evaluate(erase(sequential.second), first.output, options, parentCallId, store, events, nextId).map { second =>
              Prediction(second.output, first.raw.followedBy(second.raw))
            }
          }

        case fanout: Node.Fanout[?, ?, ?] =>
          evaluate(erase(fanout.left), input, options, parentCallId, store, events, nextId).flatMap { left =>
            evaluate(erase(fanout.right), input, options, parentCallId, store, events, nextId).map { right =>
              Prediction(left.output -> right.output, left.raw.followedBy(right.raw))
            }
          }

        case split: Node.Split[?, ?, ?, ?] =>
          val pair = input.asInstanceOf[(Any, Any)]
          evaluate(erase(split.left), pair._1, options, parentCallId, store, events, nextId).flatMap { left =>
            evaluate(erase(split.right), pair._2, options, parentCallId, store, events, nextId).map { right =>
              Prediction(left.output -> right.output, left.raw.followedBy(right.raw))
            }
          }

        case mapped: Node.MapOutput[?, ?, ?] =>
          evaluate(erase(mapped.inner), input, options, parentCallId, store, events, nextId).flatMap { value =>
            attempt("program_map_output")(mapped.map.asInstanceOf[Any => Any](value.output))
              .map(output => value.map(_ => output))
          }

        case contramapped: Node.ContramapInput[?, ?, ?] =>
          attempt("program_contramap_input")(
            contramapped.contramap.asInstanceOf[Any => Any](input)
          ).flatMap { mappedInput =>
            evaluate(erase(contramapped.inner), mappedInput, options, parentCallId, store, events, nextId)
          }

        case local: Node.Local[?, ?] =>
          attempt("program_local_options")(local.update(options)).flatMap { nextOptions =>
            evaluate(erase(local.inner), input, nextOptions, parentCallId, store, events, nextId)
          }

        case recovered: Node.Recover[?, ?] =>
          evaluate(erase(recovered.primary), input, options, parentCallId, store, events, nextId).catchAll { error =>
            attempt("program_recovery_policy")(recovered.when(error)).flatMap { allowed =>
              if allowed then
                evaluate(erase(recovered.fallback), input, options, parentCallId, store, events, nextId)
              else ZIO.fail(error)
            }
          }

        case iterate: Node.Iterate[?, ?] =>
          def loop(state: Any, remaining: Int, accumulated: RawPrediction)
              : ZIO[PredictionBackend, DspyError, Prediction[Any]] =
            ZIO.suspendSucceed {
              evaluate(erase(iterate.step), state, options, parentCallId, store, events, nextId).flatMap { transition =>
                val nextRaw = accumulated.followedBy(transition.raw)
                transition.output.asInstanceOf[LoopDecision[Any, Any]] match
                  case LoopDecision.Done(output) => ZIO.succeed(Prediction(output, nextRaw))
                  case LoopDecision.Continue(nextState) =>
                    if remaining > 1 then loop(nextState, remaining - 1, nextRaw)
                    else ZIO.fail(RuntimeError(
                        "program_loop",
                        s"Program loop did not finish within ${iterate.maxSteps} steps"
                      ))
              }
            }

          loop(input, iterate.maxSteps, RawPrediction.empty)

        case observed: Node.Observe[?, ?] =>
          if !options.traceEnabled then
            evaluate(erase(observed.inner), input, options, parentCallId, store, events, nextId)
          else
            for
              encoded <- attempt("program_observe_input")(
                           observed.encodeInput.asInstanceOf[Any => DynamicValue.Record](input)
                         )
              callId <- freshId(nextId)
              _      <- append(events, ProgramEvent.Started(callId, parentCallId, observed.name, encoded))
              result <- evaluate(
                          erase(observed.inner),
                          input,
                          options,
                          Some(callId),
                          store,
                          events,
                          nextId
                        ).tapBoth(
                          error => append(events, ProgramEvent.Failed(callId, parentCallId, observed.name, error)),
                          prediction => append(
                            events,
                            ProgramEvent.Completed(callId, parentCallId, observed.name, prediction.raw.values)
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
      events      : Ref[Vector[ProgramEvent]],
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
            executePredict(spec, binding.value, encoded, options, parentCallId, Some(callId), events)
          )
        else executePredict(spec, binding.value, encoded, options, parentCallId, None, events)
    yield result

  private def validateInputs(
      spec   : PredictSpec[Any, Any],
      encoded: DynamicValue.Record
  ): IO[DspyError, Unit] =
    val expected = spec.signature.layout.inputFields.iterator.map(_.name).toSet
    val present  = DynamicValues.recordKeys(encoded).toSet
    val missing  = expected -- present
    if missing.isEmpty then ZIO.unit
    else ZIO.fail(NotFoundError(
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
      events      : Ref[Vector[ProgramEvent]]
  ): ZIO[PredictionBackend, DspyError, Prediction[Any]] =
    val effectiveSignature = spec.signature.withInstructions(parameters.instructions)
    val request = PredictionRequest(
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
      append(events, ProgramEvent.Started(id, parentCallId, spec.name, encodedInput))
    )
    start *> ZIO
      .serviceWithZIO[PredictionBackend](_.generate(request))
      .flatMap(raw => ZIO.fromEither(Prediction.from(raw, effectiveSignature.outputShape)))
      .tapBoth(
        error => callId.fold[UIO[Unit]](ZIO.unit)(id =>
          append(events, ProgramEvent.Failed(id, parentCallId, spec.name, error))
        ),
        prediction => callId.fold[UIO[Unit]](ZIO.unit)(id =>
          append(events, ProgramEvent.Completed(id, parentCallId, spec.name, prediction.raw.values))
        )
      )

  private def attempt[A](component: String)(body: => A): IO[DspyError, A] =
    ZIO.attempt(body).mapError(error => RuntimeError(
      component,
      Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
    ))

  private def freshId(nextId: Ref[Int]): UIO[Int] = nextId.getAndUpdate(_ + 1)

  private def append(events: Ref[Vector[ProgramEvent]], event: ProgramEvent): UIO[Unit] =
    events.update(_ :+ event)

  private def erase[I, O](node: Node[I, O]): Node[?, ?] = node
