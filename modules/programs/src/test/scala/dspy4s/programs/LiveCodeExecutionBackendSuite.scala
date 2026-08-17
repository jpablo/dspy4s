package dspy4s.programs

import dspy4s.core.contracts.{CodeInterpreter, CodeResult, DspyError}
import munit.FunSuite
import zio.{Runtime, Unsafe}

final class LiveCodeExecutionBackendSuite extends FunSuite:

  private def interpreter(result: Either[DspyError, CodeResult]): CodeInterpreter =
    new CodeInterpreter:
      def execute(@annotation.unused code: String): Either[DspyError, CodeResult] = result
      def close(): Unit                                                           = ()

  private def run(backend: LiveCodeExecutionBackend): CodeExecutionResult =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(backend.execute("code")).getOrThrowFiberFailure()
    }

  test("live code backend prefers structured final output") {
    val backend = LiveCodeExecutionBackend(interpreter(Right(CodeResult("stdout", "", 0, Some("submitted")))))

    assertEquals(run(backend), CodeExecutionResult.Succeeded("submitted"))
  }

  test("live code backend returns language failures as retryable values") {
    val backend = LiveCodeExecutionBackend(interpreter(Right(CodeResult("", "NameError", 1))))

    assertEquals(run(backend), CodeExecutionResult.Failed("NameError"))
  }
