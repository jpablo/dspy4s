package dspy4s.programs

import dspy4s.core.contracts.{DspyError, RuntimeError}
import munit.FunSuite
import zio.{Promise, Ref, Runtime, Unsafe, ZEnvironment, ZIO}

final class ParallelProgramSuite extends FunSuite:

  test("collectAllPar runs members concurrently and retains member order") {
    val members = Vector("a", "b", "c").map { code =>
      Program.executeCode.contramap[Unit](_ => code)
    }
    val program = Program.collectAllPar(members, parallelism = 3)

    val result = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          (for
            active     <- Ref.make(0)
            maxActive  <- Ref.make(0)
            allStarted <- Promise.make[Nothing, Unit]
            backend     = new CodeExecutionBackend:
                        def execute(code: String): ZIO[Any, DspyError, CodeExecutionResult] =
                          for
                            current <- active.updateAndGet(_ + 1)
                            _       <- maxActive.update(value => math.max(value, current))
                            _       <- ZIO.when(current == 3)(allStarted.succeed(()))
                            _       <- allStarted.await
                            _       <- active.update(_ - 1)
                          yield CodeExecutionResult.Succeeded(code)
            prediction <- ProgramRunner
                            .run(program, ())
                            .provideEnvironment(ZEnvironment(backend))
                            .timeoutFail(RuntimeError("parallel_test", "members did not run concurrently"))(
                              zio.Duration.fromSeconds(2)
                            )
            observed <- maxActive.get
          yield prediction.output -> observed)
        )
        .getOrThrowFiberFailure()
    }

    assertEquals(
      result._1,
      Vector(
        CodeExecutionResult.Succeeded("a"),
        CodeExecutionResult.Succeeded("b"),
        CodeExecutionResult.Succeeded("c")
      )
    )
    assertEquals(result._2, 3)
    assertEquals(ProgramGraph.from(program).nodes.head.kind, "collect_all_par")
    assertEquals(
      ProgramGraph.from(program).edges.filter(_.from == 0).map(_.role),
      Vector("member_0", "member_1", "member_2")
    )
  }

  test("attempt composes with collectAllPar to retain ordered partial failures") {
    val members = Vector("a", "b", "c").map { code =>
      Program.executeCode.attempt.contramap[Unit](_ => code)
    }
    val program = Program.collectAllPar(members, parallelism = 2)
    val failure = RuntimeError("code", "b failed")
    val backend = new CodeExecutionBackend:
      def execute(code: String): ZIO[Any, DspyError, CodeExecutionResult] =
        if code == "b" then ZIO.fail(failure)
        else ZIO.succeed(CodeExecutionResult.Succeeded(code))

    val result = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ProgramRunner.run(program, ()).provideEnvironment(ZEnvironment(backend)))
        .getOrThrowFiberFailure()
        .output
    }

    assertEquals(result(0), Right(CodeExecutionResult.Succeeded("a")))
    assertEquals(result(1), Left(failure))
    assertEquals(result(2), Right(CodeExecutionResult.Succeeded("c")))
  }

  test("collectAllPar rejects non-positive parallelism") {
    interceptMessage[IllegalArgumentException](
      "requirement failed: Program.collectAllPar parallelism must be positive"
    ) {
      val _ = Program.collectAllPar(Vector(Program.identity[Int]), parallelism = 0)
    }
  }
