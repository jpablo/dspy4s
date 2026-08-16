package dspy4s.programs.plan

import dspy4s.core.contracts.{CodeResult, DspyError, ReplCodeInterpreter, RuntimeError}
import dspy4s.signatures.Shape
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, Schema}
import zio.{Runtime, Unsafe, ZIO}

final class LiveReplExecutionBackendSuite extends FunSuite:

  private final case class Question(context: String) derives Schema
  private final case class Answer(answer: String) derives Schema

  private final class TestRepl(result: Either[DspyError, CodeResult]) extends ReplCodeInterpreter:
    var closed: Boolean = false
    var calls: Vector[(String, Map[String, DynamicValue])] = Vector.empty

    def execute(code: String): Either[DspyError, CodeResult] = execute(code, Map.empty)

    def execute(code: String, variables: Map[String, DynamicValue]): Either[DspyError, CodeResult] =
      calls :+= code -> variables
      result

    def close(): Unit = closed = true

  private def program =
    val generator = Program.lift[RLM.ActionInput[Question], RLM.ActionStep](_ =>
      RLM.ActionStep("submit", "SUBMIT(answer='42')")
    )
    val extractor = Program.liftEither[RLM.ExtractInput[Question], Answer](_ =>
      Left(RuntimeError("repl_test", "fallback must not run"))
    )
    RLM(
      generator,
      RLM.replExecutor(Shape.derived[Question], Shape.derived[Answer]),
      extractor,
      maxIterations = 1,
      parseCode = Right(_)
    )

  test("the scoped live REPL persists for the run and closes after success") {
    val repl = TestRepl(Right(CodeResult("", "", 0, Some("""{"answer":"42"}"""))))
    val execution = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          ProgramRunner
            .runJournaled(program, Question("long context"))
            .provideLayer(LiveReplExecutionBackend.layer(ZIO.succeed(repl)))
        )
        .getOrThrowFiberFailure()
    }

    assertEquals(execution.outcome.map(_.output), Right(Answer("42")))
    assertEquals(repl.calls.map(_._1), Vector("SUBMIT(answer='42')"))
    assertEquals(repl.calls.head._2.keySet, Set("context"))
    assert(repl.closed)
  }

  test("the scoped live REPL closes after a typed interpreter failure") {
    val repl = TestRepl(Left(RuntimeError("test_repl", "failed")))
    val execution = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          ProgramRunner
            .runJournaled(program, Question("context"))
            .provideLayer(LiveReplExecutionBackend.layer(ZIO.succeed(repl)))
        )
        .getOrThrowFiberFailure()
    }

    assert(execution.outcome match
      case Left(RuntimeError("test_repl", "failed")) => true
      case _                                          => false)
    assert(repl.closed)
  }
