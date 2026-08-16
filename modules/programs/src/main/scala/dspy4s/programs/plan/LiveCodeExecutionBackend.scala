package dspy4s.programs.plan

import dspy4s.core.contracts.{CodeInterpreter, DspyError, RuntimeError}
import zio.{IO, ZIO}

/** Blocking bridge from the current code interpreter contract to the functional code capability. */
final class LiveCodeExecutionBackend(interpreter: CodeInterpreter) extends CodeExecutionBackend:

  def execute(code: String): IO[DspyError, CodeExecutionResult] =
    ZIO
      .attemptBlocking(interpreter.execute(code))
      .mapError(error =>
        RuntimeError(
          "code_execution",
          Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
        )
      )
      .flatMap(ZIO.fromEither)
      .map { result =>
        if result.exitCode == 0 then
          CodeExecutionResult.Succeeded(result.finalOutput.getOrElse(result.stdout.stripTrailing))
        else CodeExecutionResult.Failed(result.stderr.stripTrailing)
      }
