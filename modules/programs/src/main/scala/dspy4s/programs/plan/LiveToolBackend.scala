package dspy4s.programs.plan

import dspy4s.core.contracts.{DspyError, NotFoundError, RuntimeContext, RuntimeError}
import dspy4s.programs.contracts.{ToolCallRequest, ToolCallResult, ToolFunction}
import zio.{IO, ZIO}

/** Blocking bridge from current tool functions to the functional tool capability. */
final class LiveToolBackend(tools: Vector[ToolFunction], context: RuntimeContext) extends ToolBackend:

  def invoke(request: ToolCallRequest): IO[DspyError, ToolCallResult] =
    tools.find(_.name == request.name) match
      case None => ZIO.fail(NotFoundError("tool", s"Tool '${request.name}' does not exist"))
      case Some(tool) =>
        ZIO
          .attemptBlocking {
            given RuntimeContext = context
            tool.invoke(request.args)
          }
          .mapError(error =>
            RuntimeError(
              "tool_backend",
              Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
            )
          )
          .map(result => ToolCallResult(request.name, result))
