package dspy4s.programs.runtime

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.NotFoundError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.CallbackDispatcher
import dspy4s.programs.contracts.ToolCallRequest
import dspy4s.programs.contracts.ToolCallResult
import dspy4s.programs.contracts.ToolFunction

object ToolExecutor:
  def invoke(
      request: ToolCallRequest,
      tools: Vector[ToolFunction]
  )(using RuntimeContext): Either[DspyError, ToolCallResult] =
    tools.find(_.name == request.name) match
      case None =>
        Left(NotFoundError("tool", s"Tool '${request.name}' does not exist"))
      case Some(tool) =>
        val result = CallbackDispatcher.withTool(
          toolName = request.name,
          args = request.args
        ) {
          tool.invoke(request.args)
        }
        Right(ToolCallResult(name = request.name, result = result))
