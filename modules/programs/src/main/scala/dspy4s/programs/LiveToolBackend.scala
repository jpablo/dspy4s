package dspy4s.programs

import dspy4s.core.contracts.{DspyError, NotFoundError}
import dspy4s.programs.contracts.{Tool, ToolCallRequest, ToolCallResult}
import zio.{IO, ZIO}

/** Execute an explicit collection of effectful host tools. */
final class LiveToolBackend(tools: Vector[Tool]) extends ToolBackend:

  def invoke(request: ToolCallRequest): IO[DspyError, ToolCallResult] =
    tools.find(_.name == request.name) match
      case None       => ZIO.fail(NotFoundError("tool", s"Tool '${request.name}' does not exist"))
      case Some(tool) => tool.invoke(request.args).either.map(result => ToolCallResult(request.name, result))
