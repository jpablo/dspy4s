package dspy4s.programs.plan

import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeError, :=}
import dspy4s.core.data.RawPrediction
import dspy4s.programs.optimization.OptimizableParameters
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.{Ref, Runtime, UIO, Unsafe, ZEnvironment, ZIO}

final class ProgramPlanSuite extends FunSuite:

  private final case class Question(question: String) derives CanEqual
  private final case class Draft(draft: String)       derives CanEqual
  private final case class Answer(answer: String)     derives CanEqual

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
      execution.events.collect { case ProgramEvent.Started(_, _, component, _) => component },
      Vector("draft_predict", "answer_predict")
    )
    assertEquals(pipeline.parameters.all.map(_.id), Vector(draftId, answerId))
  }

  test("stable parameter replacement changes execution without rebuilding syntax") {
    val pipeline = draft >>> answer
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
      events.collect { case ProgramEvent.Started(id, parent, component, _) => (id, parent, component) },
      Vector(
        (0, None, "qa"),
        (1, Some(0), "draft_predict"),
        (2, Some(0), "answer_predict")
      )
    )
    assertEquals(
      events.collect { case ProgramEvent.Completed(id, parent, component, _) => (id, parent, component) },
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
    val doubled = Program.lift[Int, Int](_ * 2)
    val shown   = Program.lift[Int, String](_.toString)
    val parsed  = Program.liftEither[String, Int](value => value.toIntOption.toRight(RuntimeError("parse", value)))
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

    val localized = draft.local(options => options.copy(rolloutId = Some(9)))
    val capturingBackend = new PredictionBackend:
      def generate(request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
        if request.rolloutId.contains(9) then backend.generate(request)
        else ZIO.fail(RuntimeError("test_backend", "local options were not applied"))
    assert(run(ProgramRunner.runJournaled(localized, Question("Q")), capturingBackend).outcome.isRight)
  }

  test("complete parameter replacement validates stable IDs") {
    val pipeline = draft >>> answer
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

  test("parameter state round-trips by stable ID without serializing syntax") {
    val pipeline = draft >>> answer
    val updated = pipeline
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
    val pipeline = (draft >>> answer).fromRecords(draftSignature.inputShape)
    val execution = run(ProgramRunner.runRecordJournaled(
      pipeline,
      DynamicValues.record("question" := "From a record")
    ))

    assertEquals(
      execution.outcome.map(_.output),
      Right(Answer("final: From a record [write a draft]"))
    )
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

  test("prediction effects and typed failures remain in ZIO") {
    val (execution, calls) = runPure {
      for
        counter <- Ref.make(0)
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

    val failure = RuntimeError("expected", "typed failure")
    val failingBackend = new PredictionBackend:
      def generate(@annotation.unused request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
        ZIO.fail(failure)
    val outcome = runPure(
      ProgramRunner.run(draft, Question("Q")).either.provideEnvironment(ZEnvironment(failingBackend))
    )
    assertEquals(outcome, Left(failure))
  }
