package dspy4s.programs

import dspy4s.core.contracts.{CodeResult, DspyError, ReplCodeInterpreter, RuntimeError}
import zio.blocks.schema.DynamicValue
import zio.{IO, ZIO, ZLayer}

/** One request to a persistent REPL session. */
final case class ReplExecutionRequest(code: String, variables: Map[String, DynamicValue])

/** Effect boundary for a persistent generated-code session. */
trait ReplExecutionBackend:
  def execute(request: ReplExecutionRequest): IO[DspyError, CodeResult]

/** Blocking bridge to one current `ReplCodeInterpreter`. The enclosing layer owns its lifecycle. */
final class LiveReplExecutionBackend(private val interpreter: ReplCodeInterpreter) extends ReplExecutionBackend:

  def execute(request: ReplExecutionRequest): IO[DspyError, CodeResult] =
    ZIO
      .attemptBlocking(interpreter.execute(request.code, request.variables))
      .mapError(error =>
        RuntimeError(
          "repl_execution",
          Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
        )
      )
      .flatMap(ZIO.fromEither)

object LiveReplExecutionBackend:

  /** Acquire one persistent interpreter for one layer scope and always close it when the scope ends. */
  def layer(acquire: IO[DspyError, ReplCodeInterpreter]): ZLayer[Any, DspyError, ReplExecutionBackend] =
    ZLayer.scoped {
      ZIO
        .acquireRelease(acquire)(interpreter => ZIO.attemptBlocking(interpreter.close()).ignore)
        .map(new LiveReplExecutionBackend(_))
    }
