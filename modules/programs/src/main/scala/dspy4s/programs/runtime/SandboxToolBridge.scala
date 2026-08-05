package dspy4s.programs.runtime

import dspy4s.core.contracts.{RuntimeContext, SandboxTool}
import dspy4s.programs.contracts.ToolFunction

/** Converts program-level tools into host functions callable from a sandboxed code interpreter. */
private[programs] object SandboxToolBridge:
  /** Capture the ambient runtime context and preserve each tool's name and Python-compatible parameter hints. */
  def fromToolFunctions(tools: Vector[ToolFunction])(using ctx: RuntimeContext): Vector[SandboxTool] =
    tools.map { tool =>
      SandboxTool(
        name = tool.name,
        parameters = tool.argSchema.map { case (name, typeRef) => SandboxTool.Param(name, typeRef.pythonTypeName) },
        invoke = kwargs => tool.invoke(kwargs)(using ctx)
      )
    }
