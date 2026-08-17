package dspy4s.programs

import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeError, :=}
import dspy4s.core.data.RawPrediction
import dspy4s.programs.optimization.OptimizableParameters
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.{Ref, Runtime, UIO, Unsafe, ZEnvironment, ZIO}

final class ProgramSuite extends FunSuite:

  private final case class Question(question: String) derives CanEqual
  private final case class Draft(draft: String) derives CanEqual
  private final case class Answer(answer: String) derives CanEqual

  private val draftId  = ParameterId("draft")
  private val answerId = ParameterId("answer")

  private val draftSignature = Signature.derived[Question, Draft](
    "Draft",
    instructions = "write a draft"
  )
  private val answerSignature = Signature.derived[Draft, Answer](
    "Answer",
    instructions = "finish the answer"
  )

  private val backend = new PredictionBackend:
    def generate(request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
      ZIO.fromEither {
        if request.parameterId == draftId then
          DynamicValues.requireString(request.inputs, "question", "draft backend").map { question =>
            val instruction = request.layout.instructions.getOrElse("missing instruction")
            RawPrediction(DynamicValues.record("draft" := s"$question [$instruction]"))
          }
        else if request.parameterId == answerId then
          DynamicValues.requireString(request.inputs, "draft", "answer backend").map { draft =>
            RawPrediction(DynamicValues.record("answer" := s"final: $draft"))
          }
        else Left(RuntimeError("test_backend", s"Unknown parameter id '${request.parameterId.value}'"))
      }

  private val draft  = Program.predict(draftId, draftSignature, name = "draft_predict")
  private val answer = Program.predict(answerId, answerSignature, name = "answer_predict")

  private def run[A](effect: ZIO[PredictionBackend, Nothing, A], service: PredictionBackend = backend): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect.provideEnvironment(ZEnvironment(service))).getOrThrowFiberFailure()
    }

  private def runPure[A](effect: UIO[A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  test("typed prediction nodes compose without Module or forward") {
    val pipeline  = draft >>> answer
    val execution = run(ProgramRunner.runJournaled(pipeline, Question("Why?")))

    assertEquals(
      execution.outcome.map(_.output),
      Right(Answer("final: Why? [write a draft]"))
    )
    assertEquals(
      execution.events.collect { case ProgramEvent.Started(_, _, component, _, _) => component },
      Vector("draft_predict", "answer_predict")
    )
    assertEquals(pipeline.parameters.all.map(_.id), Vector(draftId, answerId))
  }

  test("stable parameter replacement changes execution without rebuilding syntax") {
    val pipeline     = draft >>> answer
    val updatedDraft = pipeline.parameters
      .get(draftId)
      .getOrElse(fail("missing draft parameters"))
      .copy(instructions = Some("write a better draft"))
    val optimized = pipeline
      .updatedParameter(draftId, updatedDraft)
      .fold(error => fail(error.message), identity)

    assertEquals(
      run(ProgramRunner.runJournaled(optimized, Question("Why?"))).outcome.map(_.output),
      Right(Answer("final: Why? [write a better draft]"))
    )
    assertEquals(ProgramGraph.from(optimized), ProgramGraph.from(pipeline))
  }

  test("reusing a parameter ID shares one slot") {
    val shared = draft &&& draft

    assertEquals(shared.parameters.size, 1)
    assertEquals(
      run(ProgramRunner.runJournaled(shared, Question("Q"))).outcome.map(_.output),
      Right(Draft("Q [write a draft]") -> Draft("Q [write a draft]"))
    )
  }

  test("a conflicting use of one parameter ID is rejected at construction") {
    val conflicting = Program.predict(
      draftId,
      draftSignature.withInstructions("different"),
      name = "draft_predict"
    )

    interceptMessage[IllegalArgumentException](
      "requirement failed: Parameter id 'draft' was composed with different metadata or values"
    ) {
      val _ = draft &&& conflicting
    }
  }

  test("the graph interpreter reads the same program syntax") {
    val graph = ProgramGraph.from(draft >>> answer)

    assertEquals(
      graph.nodes.map(node => (node.id, node.kind, node.parameterId)),
      Vector(
        (0, "and_then", None),
        (1, "predict", Some(draftId)),
        (2, "predict", Some(answerId))
      )
    )
    assertEquals(
      graph.edges,
      Vector(
        ProgramGraphEdge(0, 1, "first"),
        ProgramGraphEdge(0, 2, "second")
      )
    )
  }

  test("observed scopes create explicit parent-child events") {
    val observed = (draft >>> answer).observed("qa", draftSignature.inputShape.encode)
    val events   = run(ProgramRunner.runJournaled(observed, Question("Q"))).events

    assertEquals(
      events.collect { case ProgramEvent.Started(id, parent, component, _, _) => (id, parent, component) },
      Vector(
        (0, None, "qa"),
        (1, Some(0), "draft_predict"),
        (2, Some(0), "answer_predict")
      )
    )
    assertEquals(
      events.collect { case ProgramEvent.Completed(id, parent, component, _, _) => (id, parent, component) },
      Vector(
        (1, Some(0), "draft_predict"),
        (2, Some(0), "answer_predict"),
        (0, None, "qa")
      )
    )
  }

  test("trace disabling removes all prediction and scope events") {
    val observed = (draft >>> answer).observed("qa", draftSignature.inputShape.encode)
    val result   = run(ProgramRunner.runJournaled(observed, Question("Q"), RunOptions(traceEnabled = false)))

    assertEquals(result.events, Vector.empty)
    assert(result.outcome.isRight)
  }

  test("fanout, split, map, contramap, local options, and recovery are interpreted centrally") {
    val doubled  = Program.lift[Int, Int](_ * 2)
    val shown    = Program.lift[Int, String](_.toString)
    val parsed   = Program.liftEither[String, Int](value => value.toIntOption.toRight(RuntimeError("parse", value)))
    val fallback = Program.lift[String, Int](_.length)

    assertEquals(run(ProgramRunner.runJournaled(doubled &&& shown, 3)).outcome.map(_.output), Right(6 -> "3"))
    assertEquals(run(ProgramRunner.runJournaled(doubled *** shown, 3 -> 4)).outcome.map(_.output), Right(6 -> "4"))
    assertEquals(
      run(ProgramRunner.runJournaled(doubled.map(_ + 1).contramap[String](_.length), "abc")).outcome.map(_.output),
      Right(7)
    )
    assertEquals(
      run(ProgramRunner.runJournaled(parsed.recoverWith(fallback)(_ => true), "bad")).outcome.map(_.output),
      Right(3)
    )

    val localized        = draft.local(options => options.copy(rolloutId = Some(9)))
    val capturingBackend = new PredictionBackend:
      def generate(request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
        if request.rolloutId.contains(9) then backend.generate(request)
        else ZIO.fail(RuntimeError("test_backend", "local options were not applied"))
    assert(run(ProgramRunner.runJournaled(localized, Question("Q")), capturingBackend).outcome.isRight)
  }

  test("attempt converts a typed program failure into visible output data") {
    val failed = Program.liftEither[String, Int](value => Left(RuntimeError("attempt_test", value))).attempt
    val result = run(ProgramRunner.runJournaled(failed, "expected"))

    assert(result.outcome match
      case Right(prediction) => prediction.output match
          case Left(RuntimeError("attempt_test", "expected")) => true
          case _                                              => false
      case Left(_) => false)
    assertEquals(ProgramGraph.from(failed).nodes.map(_.kind), Vector("attempt", "lift_either"))
  }

  test("typed choice runs one visible branch and retains both parameter sets") {
    val left   = draft.map(_.draft)
    val right  = answer.contramap[String](Draft.apply).map(_.answer)
    val choice = left ||| right

    assertEquals(
      run(ProgramRunner.runJournaled(choice, Left(Question("Q")))).outcome.map(_.output),
      Right("Q [write a draft]")
    )
    assertEquals(
      run(ProgramRunner.runJournaled(choice, Right("ready"))).outcome.map(_.output),
      Right("final: ready")
    )
    assertEquals(choice.parameters.all.map(_.id), Vector(draftId, answerId))
    assertEquals(
      ProgramGraph.from(choice).nodes.map(_.kind),
      Vector("choice", "map", "predict", "map", "contramap", "predict")
    )
    assertEquals(
      ProgramGraph.from(choice).edges.filter(_.from == 0).map(_.role),
      Vector("left", "right")
    )

    val selected = Program.lift[String, String](identity) |||
      Program.liftEither[Int, String](_ => Left(RuntimeError("unselected", "right branch ran")))
    assertEquals(
      run(ProgramRunner.runJournaled(selected, Left("left"))).outcome.map(_.output),
      Right("left")
    )
  }

  test("complete parameter replacement validates stable IDs") {
    val pipeline   = draft >>> answer
    val incomplete = Map(
      draftId -> OptimizableParameters(instructions = Some("only one"))
    )

    assert(pipeline.replaceParameters(incomplete).isLeft)
    val replaced = pipeline
      .replaceParameters(pipeline.parameters.all.map(binding => binding.id -> binding.value).toMap)
      .fold(error => fail(error.message), identity)
    assertEquals(replaced.parameters, pipeline.parameters)
    assertEquals(ProgramGraph.from(replaced), ProgramGraph.from(pipeline))
  }

  test("a visible configurator derives run-local parameters from the typed input") {
    val configurator = Program.lift[Question, String](_.question.toUpperCase)
    val configured   = draft.localParametersWith(configurator) { (store, instruction) =>
      store
        .get(draftId)
        .toRight(RuntimeError("test", "missing draft parameters"))
        .flatMap(value => store.updated(draftId, value.copy(instructions = Some(instruction))))
    }
    val execution = run(ProgramRunner.runJournaled(configured, Question("local instruction")))
    val graph     = ProgramGraph.from(configured)

    assertEquals(
      execution.outcome.map(_.output),
      Right(Draft("local instruction [LOCAL INSTRUCTION]"))
    )
    assertEquals(configured.parameters.get(draftId).flatMap(_.instructions), Some("write a draft"))
    assertEquals(graph.nodes.map(_.kind), Vector("local_parameters", "lift", "predict"))
    assertEquals(graph.edges.map(_.role), Vector("configurator", "inner"))
  }

  test("parameter state round-trips by stable ID without serializing syntax") {
    val pipeline = draft >>> answer
    val updated  = pipeline
      .updatedParameter(
        answerId,
        pipeline.parameters.get(answerId).getOrElse(fail("missing answer")).copy(instructions = Some("concise"))
      )
      .fold(error => fail(error.message), identity)
    val loaded = pipeline.loadParameterState(updated.parameters.dumpState).fold(error => fail(error.message), identity)

    assertEquals(loaded.parameters, updated.parameters)
    assertEquals(ProgramGraph.from(loaded), ProgramGraph.from(pipeline))
    assert(pipeline.loadParameterState(DynamicValues.record("unknown" := "bad")).isLeft)
  }

  test("the explicit record boundary decodes any typed composite") {
    val pipeline  = (draft >>> answer).fromRecords(draftSignature.inputShape)
    val execution = run(ProgramRunner.runRecordJournaled(
      pipeline,
      DynamicValues.record("question" := "From a record")
    ))

    assertEquals(
      execution.outcome.map(_.output),
      Right(Answer("final: From a record [write a draft]"))
    )
  }

  test("record programs update one stable parameter without rebuilding the record boundary") {
    val program = (draft >>> answer).fromRecords(draftSignature.inputShape)
    val updated = program
      .modifyParameter(draftId)(_.copy(instructions = Some("record update")))
      .fold(error => fail(error.message), identity)
    val execution = run(ProgramRunner.runRecordJournaled(
      updated,
      DynamicValues.record("question" := "Q")
    ))

    assertEquals(execution.outcome.map(_.output), Right(Answer("final: Q [record update]")))
    assert(updated.modifyParameter(ParameterId("missing"))(identity).isLeft)
  }

  test("bounded iteration keeps the step program visible") {
    val step = Program.lift[Int, LoopDecision[Int, String]] { state =>
      if state >= 4 then LoopDecision.Done(s"done:$state")
      else LoopDecision.Continue(state + 1)
    }
    val loop      = Program.iterate(step, maxSteps = 5)
    val execution = run(ProgramRunner.runJournaled(loop, 0))

    assertEquals(execution.outcome.map(_.output), Right("done:4"))
    assertEquals(ProgramGraph.from(loop).nodes.map(_.kind), Vector("iterate", "lift"))

    val exhausted = run(ProgramRunner.runJournaled(Program.iterate(step, maxSteps = 2), 0))
    assert(exhausted.outcome.isLeft)
  }

  test("collectAll runs homogeneous members in order and exposes each graph child") {
    val collected = Program.collectAll(Vector(
      Program.lift[Int, Int](_ + 1),
      Program.lift[Int, Int](_ * 2),
      Program.lift[Int, Int](_ - 1)
    ))
    val execution = run(ProgramRunner.runJournaled(collected, 4))
    val graph     = ProgramGraph.from(collected)

    assertEquals(execution.outcome.map(_.output), Right(Vector(5, 8, 3)))
    assertEquals(graph.nodes.map(_.kind), Vector("collect_all", "lift", "lift", "lift"))
    assertEquals(graph.edges.map(_.role), Vector("member_0", "member_1", "member_2"))
    assertEquals(
      run(ProgramRunner.runJournaled(Program.collectAll[Int, Int, Any](Vector.empty), 4)).outcome.map(_.output),
      Right(Vector.empty)
    )

    val failFast = Program.collectAll(Vector(
      Program.liftEither[Int, Int](_ => Left(RuntimeError("first_member", "failed"))),
      Program.liftEither[Int, Int](_ => Left(RuntimeError("second_member", "must not run")))
    ))
    assert(run(ProgramRunner.runJournaled(failFast, 4)).outcome match
      case Left(RuntimeError("first_member", "failed")) => true
      case _                                            => false)
  }

  test("bestOfN is a visible selection node with rollout IDs and early stopping") {
    val selectingBackend = new PredictionBackend:
      def generate(request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
        ZIO.succeed(RawPrediction(DynamicValues.record(
          "draft" := request.rolloutId.getOrElse(-1).toString
        )))
    val selected = Program.bestOfN(draft, attempts = 5, threshold = Some(2.0)) { (_, prediction) =>
      prediction.output.draft.toDoubleOption.toRight(RuntimeError("reward", "not a number"))
    }
    val execution = run(ProgramRunner.runJournaled(selected, Question("Q")), selectingBackend)

    assertEquals(execution.outcome.map(_.output), Right(Draft("2")))
    assertEquals(
      execution.events.collect { case ProgramEvent.Started(_, _, component, _, _) => component },
      Vector("draft_predict", "draft_predict", "draft_predict")
    )
    assertEquals(ProgramGraph.from(selected).nodes.map(_.kind), Vector("best_of_n", "predict"))
    assertEquals(selected.parameters.all.map(_.id), Vector(draftId))
  }

  test("repeatUntil uses visible iteration and exposes prediction evidence") {
    val increment = Program.lift[Int, Int](_ + 1)
    val repeated  = Program.repeatUntil(increment, maxSteps = 4)(
      accept = (_, prediction) => Right(prediction.output >= 3 && prediction.raw.values.fields.isEmpty),
      nextInput = (_, prediction) => Right(prediction.output)
    )
    val execution  = run(ProgramRunner.runJournaled(repeated, 0))
    val graphKinds = ProgramGraph.from(repeated).nodes.map(_.kind)

    assertEquals(execution.outcome.map(_.output), Right(3))
    assert(graphKinds.contains("iterate"))
    assert(graphKinds.contains("with_evidence"))
    assert(graphKinds.contains("lift_either"))
  }

  test("prediction effects and typed failures remain in ZIO") {
    val (execution, calls) = runPure {
      for
        counter      <- Ref.make(0)
        effectBackend = new PredictionBackend:
                          def generate(request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
                            counter.update(_ + 1) *> backend.generate(request)
        result <- ProgramRunner
                    .runJournaled(draft >>> answer, Question("Q"))
                    .provideEnvironment(ZEnvironment(effectBackend))
        count <- counter.get
      yield result -> count
    }

    assert(execution.outcome.isRight)
    assertEquals(calls, 2)

    val failure        = RuntimeError("expected", "typed failure")
    val failingBackend = new PredictionBackend:
      def generate(@annotation.unused request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
        ZIO.fail(failure)
    val outcome = runPure(
      ProgramRunner.run(draft, Question("Q")).either.provideEnvironment(ZEnvironment(failingBackend))
    )
    assertEquals(outcome, Left(failure))
  }

  test("an explicit observer receives the same ordered events as the journal") {
    val (execution, observedEvents) = runPure {
      for
        received <- Ref.make(Vector.empty[ProgramEvent])
        observer  = new ProgramObserver:
                     def onEvent(event: ProgramEvent): UIO[Unit] = received.update(_ :+ event)
        result <- ProgramRunner
                    .runJournaledObserved(draft >>> answer, Question("Q"), observer)
                    .provideEnvironment(ZEnvironment(backend))
        observed <- received.get
      yield result -> observed
    }

    assertEquals(observedEvents, execution.events)
    assertEquals(observedEvents.size, 4)
  }

  test("a streamed chunk remains in the journal when prediction later fails") {
    val failure          = RuntimeError("stream_backend", "failed after output")
    val streamingBackend = new PredictionBackend:
      def generate(@annotation.unused request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
        ZIO.fail(failure)

      override def generateStreaming(
          request: PredictionRequest,
          emit   : PredictionChunk => UIO[Unit]
      ): ZIO[Any, DspyError, RawPrediction] =
        emit(PredictionChunk("draft", "partial")) *> generate(request)

    val execution = run(ProgramRunner.runJournaled(draft, Question("Q")), streamingBackend)

    assertEquals(execution.outcome, Left(failure))
    assertEquals(
      execution.events.map {
        case _: ProgramEvent.Started     => "started"
        case _: ProgramEvent.OutputChunk => "chunk"
        case _: ProgramEvent.Completed   => "completed"
        case _: ProgramEvent.Failed      => "failed"
      },
      Vector("started", "chunk", "failed")
    )
  }
